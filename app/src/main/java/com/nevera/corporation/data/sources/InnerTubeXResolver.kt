package com.nevera.corporation.data.sources

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.YouTubeLocale
import com.nevera.corporation.data.model.SourceStream
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-client YouTube stream extractor powered by InnerTubeX + zemer-cipher.
 */
object InnerTubeXResolver {

    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
    private const val MAX_ATTEMPTS = 3

    private var prefs: SharedPreferences? = null
    private var playerDir: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val excludedByTrack = ConcurrentHashMap<String, MutableSet<String>>()

    private val http by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 25_000
                connectTimeoutMillis = 12_000
                socketTimeoutMillis = 18_000
            }
            expectSuccess = false
        }
    }

    private val logger = InnerTubeLogger { _ -> }

    private val repository = object : PlayerConfigRepository {
        override val enabled: Boolean get() = prefs != null
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() = prefs?.getString("json", "").orEmpty()
            set(value) { prefs?.edit()?.putString("json", value)?.apply() }
        override var cachedAtMs: Long
            get() = prefs?.getLong("cached_at_ms", 0L) ?: 0L
            set(value) { prefs?.edit()?.putLong("cached_at_ms", value)?.apply() }
        override var cachedSourceUrl: String
            get() = prefs?.getString("source_url", "").orEmpty()
            set(value) { prefs?.edit()?.putString("source_url", value)?.apply() }
        override var cachedEtag: String
            get() = prefs?.getString("etag", "").orEmpty()
            set(value) { prefs?.edit()?.putString("etag", value)?.apply() }
    }

    private val noPoTokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(providers = emptySet(), usesWebView = false)
        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? = null
        override suspend fun close() {}
    }

    private val innerTube by lazy { InnerTube(http, logger = logger) }
    private val remoteStore by lazy { RemotePlayerConfigStore(http, repository, logger) }
    private val cipherService by lazy { YouTubeCipherService(http, remoteStore, logger) }
    private val extractor by lazy {
        InnerTubeExtractor(
            configParser = YtConfigParserImpl(http, innerTube, remoteStore, logger),
            cipherService = cipherService,
            innerTube = innerTube,
            tokenProvider = noPoTokenProvider,
            logger = logger
        )
    }

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("nevera_itx_cfg", Context.MODE_PRIVATE)
        playerDir = File(context.filesDir, "itx_players").apply { mkdirs() }
        scope.launch {
            delay(1500)
            runCatching {
                cipherService.setPreprocessedPlayerCache(::readPlayer, ::writePlayer)
                innerTube.locale = YouTubeLocale(gl = "ID", hl = "id")
                extractor.prewarm()
            }
        }
    }

    private fun readPlayer(key: String): String? =
        playerDir?.let { File(it, key) }?.takeIf { it.isFile }?.readText()

    private fun writePlayer(key: String, value: String?) {
        val dir = playerDir ?: return
        val file = File(dir, key)
        if (value == null) {
            file.delete()
            return
        }
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(value)
        tmp.renameTo(file)
        dir.listFiles()?.filter { !it.name.endsWith(".tmp") }?.sortedByDescending { it.lastModified() }
            ?.drop(3)?.forEach { it.delete() }
    }

    suspend fun extractVerified(videoId: String, maxKbps: Int): SourceStream? {
        val cleanId = videoId.trim()
        if (cleanId.isBlank()) return null

        val skipped = excludedByTrack.getOrPut(cleanId) { ConcurrentHashMap.newKeySet() }

        repeat(MAX_ATTEMPTS) { attempt ->
            val quality = when {
                maxKbps <= 96 -> AudioQuality.LOW
                maxKbps >= 160 && attempt == 0 -> AudioQuality.HIGH
                else -> AudioQuality.AUTO
            }
            val stream = try {
                innerTube.locale = YouTubeLocale(gl = "ID", hl = "id")
                extractor.extract(
                    videoId = cleanId,
                    hints = ContentHints()
                        .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
                    excludedClients = skipped.toSet(),
                    audioQuality = quality,
                    clientPlaybackNonce = generateClientPlaybackNonce()
                )
            } catch (_: Exception) {
                null
            }
            if (stream == null) {
                // Retry with AUTO quality if HIGH attempt returned null
                if (attempt == 0 && quality == AudioQuality.HIGH) {
                    return@repeat
                }
                return null
            }

            if (stream.sabrBootstrap == null && stream.audioUrl.isNotBlank()) {
                val ok = StreamProbe.isPlayable(stream.audioUrl, stream.headers)
                if (ok) {
                    val kbps = ((stream.bitrate ?: 0) / 1000).takeIf { it > 0 } ?: 128
                    val mime = stream.mimeType.orEmpty()
                    val codec = when {
                        mime.contains("mp4", true) || mime.contains("aac", true) -> "AAC"
                        mime.contains("opus", true) -> "Opus"
                        else -> "Audio"
                    }
                    return SourceStream(
                        url = stream.audioUrl,
                        codec = codec,
                        kbps = kbps,
                        qualityLabel = "Studio $codec • $kbps kbps",
                        headers = stream.headers,
                        sampleRateHz = if (codec == "Opus") 48000 else 44100,
                        bitDepth = 0,
                        isLossless = false,
                        originLabel = "Master Studio Stream"
                    )
                }
            }
            skipped.add(stream.profileId)
        }
        return null
    }

    fun invalidate(videoId: String) {
        excludedByTrack.remove(videoId.trim())
    }
}
