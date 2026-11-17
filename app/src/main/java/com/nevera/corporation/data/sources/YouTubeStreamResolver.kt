package com.nevera.corporation.data.sources

import com.nevera.corporation.data.model.SourceStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves playable audio streams using Nevera's two-layer pipeline:
 * 1. InnerTubeX multi-client cipher solver + StreamProbe
 * 2. NewPipeExtractor v0.26.3 failsafe + StreamProbe
 */
object YouTubeStreamResolver {

    private const val NEXT_ENDPOINT = "/youtubei/v1/next"
    private const val EMPTY_NEXT_RESPONSE =
        """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""
    private const val CACHE_TTL_MS = 3 * 60 * 60 * 1000L

    private data class CachedStream(val stream: SourceStream, val cachedAtMs: Long)

    private val cache = ConcurrentHashMap<String, CachedStream>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var initialized = false

    private class OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            if (NEXT_ENDPOINT in request.url()) {
                return Response(200, "OK", emptyMap(), EMPTY_NEXT_RESPONSE, request.url())
            }

            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())

            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) {
                    hasUserAgent = true
                }
                if (values.size == 1) {
                    builder.header(name, values[0])
                } else if (values.size > 1) {
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }
            }
            if (!hasUserAgent) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
                )
            }

            client.newCall(builder.build()).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                return Response(
                    resp.code,
                    resp.message,
                    resp.headers.toMultimap(),
                    bodyStr,
                    resp.request.url.toString()
                )
            }
        }
    }

    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        NewPipe.init(OkHttpDownloader(), Localization("id", "ID"))
        initialized = true
    }

    suspend fun resolve(videoId: String, maxKbps: Int = 320): SourceStream? = withContext(Dispatchers.IO) {
        val cleanId = videoId.removePrefix("yt_").trim()
        if (cleanId.isBlank()) return@withContext null

        val cached = cache[cleanId]
        if (cached != null && (System.currentTimeMillis() - cached.cachedAtMs) < CACHE_TTL_MS) {
            return@withContext cached.stream
        }

        // 1. InnerTubeX primary multi-client resolver (verified with StreamProbe)
        InnerTubeXResolver.extractVerified(cleanId, maxKbps)?.let { verified ->
            cache[cleanId] = CachedStream(verified, System.currentTimeMillis())
            return@withContext verified
        }

        // 2. NewPipeExtractor v0.26.3 failsafe
        try {
            ensureInitialized()
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$cleanId")
            extractor.fetchPage()

            val streams = extractor.audioStreams
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.isNotBlank() }
                .sortedWith(
                    compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> {
                        it.averageBitrate <= maxKbps
                    }.thenByDescending {
                        it.format == MediaFormat.M4A || it.format == MediaFormat.WEBMA_OPUS
                    }.thenByDescending { it.averageBitrate }
                )

            for (candidate in streams) {
                if (StreamProbe.isPlayable(candidate.content)) {
                    val codecLabel = when (candidate.format) {
                        MediaFormat.M4A -> "AAC"
                        MediaFormat.WEBMA_OPUS -> "Opus"
                        else -> "Audio"
                    }
                    val kbps = candidate.averageBitrate.takeIf { it > 0 } ?: 128
                    val result = SourceStream(
                        url = candidate.content,
                        codec = codecLabel,
                        kbps = kbps,
                        qualityLabel = "HQ Audio • $kbps kbps $codecLabel"
                    )
                    cache[cleanId] = CachedStream(result, System.currentTimeMillis())
                    return@withContext result
                }
            }
        } catch (_: Exception) {
        }

        null
    }

    fun invalidate(videoId: String) {
        val clean = videoId.removePrefix("yt_").trim()
        cache.remove(clean)
        InnerTubeXResolver.invalidate(clean)
    }
}
