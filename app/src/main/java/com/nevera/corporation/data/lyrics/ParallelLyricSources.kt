package com.nevera.corporation.data.lyrics

import com.nevera.corporation.data.model.LyricLine
import com.nevera.corporation.data.sources.InnertubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

internal data class SourceCandidate(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val hasNativeWordSync: Boolean
)

/**
 * Parallel multi-source timed lyric fetching stack (never exposed to UI):
 * SimpMusic (videoId + search fallback), BiniLyrics (TTML), BetterLyrics (TTML),
 * Portato QQ Karaoke, LyricsPlus v2 mirrors, NetEase YRC/LRC, YouTube Music timedLyrics,
 * Paxsenix Apple/Musixmatch, KuGou Mobile, and LRCLIB.
 */
internal object ParallelLyricSources {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val fastestLyricsPlusMirror = AtomicReference<String?>(null)

    private val LYRICS_PLUS_MIRRORS = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app"
    )

    private fun httpGet(url: String, extraHeaders: Map<String, String> = emptyMap()): String? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Nevera/6.1")
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 1a. SimpMusic database matched strictly by exact videoId + duration tolerance. */
    suspend fun fetchSimpMusic(videoId: String, durationSec: Long): SourceCandidate? = withContext(Dispatchers.IO) {
        val cleanId = videoId.removePrefix("yt_").removePrefix("saavn_").trim()
        if (cleanId.isBlank() || cleanId.startsWith("sp_") || cleanId.startsWith("local_")) return@withContext null
        val raw = httpGet("https://api-lyrics.simpmusic.org/v1/$cleanId") ?: return@withContext null
        parseSimpMusicPayload(raw, durationSec)
    }

    /** 1b. SimpMusic catalog search fallback when videoId lookup isn't indexed. */
    suspend fun fetchSimpMusicSearch(title: String, artist: String, durationSec: Long): SourceCandidate? =
        withContext(Dispatchers.IO) {
            val url = "https://api-lyrics.simpmusic.org/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$title $artist".trim())
                .build()
            val raw = httpGet(url.toString()) ?: return@withContext null
            parseSimpMusicPayload(raw, durationSec)
        }

    internal fun parseSimpMusicPayload(raw: String, durationSec: Long): SourceCandidate? {
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["success"]?.jsonPrimitive?.contentOrNull != "true") return null
            val list = root["data"]?.jsonArray ?: return null
            val chosen = list
                .map { it.jsonObject }
                .filter { obj ->
                    val d = obj["duration"]?.jsonPrimitive?.intOrNull ?: 0
                    durationSec <= 0L || d <= 0 || abs(d - durationSec.toInt()) <= 10
                }
                .minByOrNull { obj ->
                    val d = obj["duration"]?.jsonPrimitive?.intOrNull ?: durationSec.toInt()
                    abs(d - durationSec.toInt())
                } ?: return null

            val rich = chosen["richSyncLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            if (rich.isNotBlank()) {
                val lines = NeveraLyricAiEngine.parseLrcOrEnhanced(rich)
                if (lines.isNotEmpty()) {
                    return SourceCandidate(lines, isSynced = true, hasNativeWordSync = lines.any { it.words.isNotEmpty() })
                }
            }

            val synced = chosen["syncedLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (synced.isNotBlank()) {
                val lines = NeveraLyricAiEngine.parseLrcOrEnhanced(synced)
                if (lines.isNotEmpty()) {
                    return SourceCandidate(lines, isSynced = true, hasNativeWordSync = false)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 2. BiniLyrics ISRC/catalogue lookup returning Apple TTML word-timed lyrics. */
    suspend fun fetchBiniLyrics(title: String, artist: String, durationSec: Long): SourceCandidate? = withContext(Dispatchers.IO) {
        val searchUrl = "https://lyrics-api.binimum.org/".toHttpUrl().newBuilder()
            .addQueryParameter("track", title)
            .addQueryParameter("artist", artist)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        val body = httpGet(searchUrl.toString()) ?: return@withContext null
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val docUrl = root["results"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("lyrics")?.jsonPrimitive?.contentOrNull
                ?: root["results"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("lyricsUrl")?.jsonPrimitive?.contentOrNull
                ?: return@withContext null
            val ttml = httpGet(docUrl) ?: return@withContext null
            val lines = NeveraLyricAiEngine.parseTtml(ttml)
            if (lines.isEmpty()) null
            else SourceCandidate(lines, isSynced = true, hasNativeWordSync = lines.any { it.words.isNotEmpty() })
        } catch (_: Exception) {
            null
        }
    }

    /** 3 & 4. BetterLyrics TTML + Portato QQ Karaoke. */
    suspend fun fetchBetterLyrics(title: String, artist: String, durationSec: Long, portato: Boolean = false): SourceCandidate? =
        withContext(Dispatchers.IO) {
            val base = if (portato) "https://lyrics-api.boidu.dev/qq/getLyrics" else "https://lyrics-api.boidu.dev/getLyrics"
            val url = base.toHttpUrl().newBuilder()
                .addQueryParameter("s", title)
                .addQueryParameter("a", artist)
                .apply { if (durationSec > 0) addQueryParameter("d", durationSec.toString()) }
                .build()
            val body = httpGet(url.toString()) ?: return@withContext null
            val lines = NeveraLyricAiEngine.parseFlexiblePayload(body) ?: return@withContext null
            SourceCandidate(lines, isSynced = true, hasNativeWordSync = lines.any { it.words.isNotEmpty() })
        }

    /** 5. LyricsPlus v2 parallel mirror race with fastest-host memory. */
    suspend fun fetchLyricsPlusRace(title: String, artist: String, durationSec: Long): SourceCandidate? = coroutineScope {
        val hosts = fastestLyricsPlusMirror.get()?.let { best ->
            listOf(best) + LYRICS_PLUS_MIRRORS.filterNot { it == best }
        } ?: LYRICS_PLUS_MIRRORS

        val pending = hosts.map { host ->
            host to async(Dispatchers.IO) {
                val url = "$host/v2/lyrics/get".toHttpUrl().newBuilder()
                    .addQueryParameter("title", title)
                    .addQueryParameter("artist", artist)
                    .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
                    .build()
                val body = httpGet(url.toString()) ?: return@async null
                val lines = NeveraLyricAiEngine.parseLyricsPlusJson(body) ?: return@async null
                SourceCandidate(lines, isSynced = true, hasNativeWordSync = lines.any { it.words.isNotEmpty() })
            }
        }.toMutableList()

        try {
            while (pending.isNotEmpty()) {
                val (host, cand) = select {
                    pending.forEach { (h, job) -> job.onAwait { h to it } }
                }
                pending.removeAll { it.first == host }
                if (cand != null && cand.lines.isNotEmpty()) {
                    fastestLyricsPlusMirror.set(host)
                    return@coroutineScope cand
                }
            }
            null
        } finally {
            pending.forEach { it.second.cancel() }
        }
    }

    /** 6. NetEase Cloud Music (`yrc` word-timed + `lrc` synced fallback) with duration scoring. */
    suspend fun fetchNetEase(title: String, artist: String, durationSec: Long): SourceCandidate? = withContext(Dispatchers.IO) {
        val searchUrl = "https://music.163.com/api/search/get/web".toHttpUrl().newBuilder()
            .addQueryParameter("s", "$title $artist".trim())
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", "6")
            .addQueryParameter("offset", "0")
            .build()
        val headers = mapOf(
            "Referer" to "https://music.163.com/",
            "Origin" to "https://music.163.com"
        )
        val rawSearch = httpGet(searchUrl.toString(), headers) ?: return@withContext null
        val songIds = try {
            val songs = json.parseToJsonElement(rawSearch).jsonObject["result"]
                ?.jsonObject?.get("songs")?.jsonArray ?: return@withContext null
            songs.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val durMs = obj["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                val durDiff = if (durationSec > 0 && durMs > 0) abs((durMs / 1000L) - durationSec) else 0L
                if (durationSec > 0 && durMs > 0 && durDiff > 10L) return@mapNotNull null
                id to durDiff
            }.sortedBy { it.second }.map { it.first }
        } catch (_: Exception) {
            emptyList()
        }

        for (id in songIds.take(2)) {
            val lyricUrl = "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1&rv=1&yv=1"
            val rawLyric = httpGet(lyricUrl, headers) ?: continue
            parseNetEaseLyricPayload(rawLyric)?.let { return@withContext it }
        }
        null
    }

    internal fun parseNetEaseLyricPayload(raw: String): SourceCandidate? {
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val yrcRaw = root["yrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (yrcRaw.isNotBlank()) {
                val yrcLines = NeveraLyricAiEngine.parseNetEaseYrc(yrcRaw)
                if (yrcLines.isNotEmpty()) {
                    return SourceCandidate(yrcLines, isSynced = true, hasNativeWordSync = true)
                }
            }
            val lrcRaw = root["lrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (lrcRaw.isNotBlank()) {
                val lrcLines = NeveraLyricAiEngine.parseLrcOrEnhanced(lrcRaw)
                if (lrcLines.isNotEmpty()) {
                    return SourceCandidate(lrcLines, isSynced = true, hasNativeWordSync = lrcLines.any { it.words.isNotEmpty() })
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 7. Paxsenix word-timed mirror (TTML / Enhanced LRC / LyricsPlus format). */
    suspend fun fetchPaxsenix(title: String, artist: String, endpointPath: String): SourceCandidate? =
        withContext(Dispatchers.IO) {
            val url = "https://lyrics.paxsenix.org/$endpointPath".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$title $artist".trim())
                .build()
            val body = httpGet(url.toString()) ?: return@withContext null
            val lines = NeveraLyricAiEngine.parseLyricsPlusJson(body)
                ?: NeveraLyricAiEngine.parseFlexiblePayload(body)
                ?: return@withContext null
            if (lines.isEmpty()) null
            else SourceCandidate(lines, isSynced = true, hasNativeWordSync = lines.any { it.words.isNotEmpty() })
        }

    /** 8. Native timed/description lyrics directly from YouTube Music `next` -> `browse`. */
    suspend fun fetchYouTubeTimed(videoId: String): SourceCandidate? {
        val pair = InnertubeClient.getTimedLyrics(videoId) ?: return null
        val (lines, synced) = pair
        if (lines.isEmpty()) return null
        return SourceCandidate(lines, isSynced = synced, hasNativeWordSync = false)
    }

    /** 9. KuGou mobile audio-hash/keyword LRC fetcher (excels for Asian/J-Pop/C-Pop/Pop hits). */
    suspend fun fetchKuGou(title: String, artist: String, durationSec: Long): SourceCandidate? = withContext(Dispatchers.IO) {
        val keyword = "$title $artist".trim()
        val searchUrl = "https://mobileservice.kugou.com/api/v3/search/song".toHttpUrl().newBuilder()
            .addQueryParameter("version", "9108")
            .addQueryParameter("plat", "0")
            .addQueryParameter("pagesize", "6")
            .addQueryParameter("keyword", keyword)
            .build()
        val hashes = try {
            val raw = httpGet(searchUrl.toString()).orEmpty()
            val arr = json.parseToJsonElement(raw).jsonObject["data"]?.jsonObject?.get("info")?.jsonArray
            arr?.map { it.jsonObject }
                ?.filter { durationSec <= 0L || abs((it["duration"]?.jsonPrimitive?.intOrNull ?: 0) - durationSec.toInt()) <= 8 }
                ?.mapNotNull { it["hash"]?.jsonPrimitive?.contentOrNull }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        val candidate = hashes.firstNotNullOfOrNull { hash ->
            val lrcSearch = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("ver", "1")
                .addQueryParameter("man", "yes")
                .addQueryParameter("client", "pc")
                .addQueryParameter("hash", hash)
                .build()
            extractKuGouCandidate(httpGet(lrcSearch.toString()))
        } ?: run {
            val kwSearch = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("ver", "1")
                .addQueryParameter("man", "yes")
                .addQueryParameter("client", "pc")
                .addQueryParameter("keyword", keyword)
                .apply { if (durationSec > 0) addQueryParameter("duration", (durationSec * 1000).toString()) }
                .build()
            extractKuGouCandidate(httpGet(kwSearch.toString()))
        } ?: return@withContext null

        val dlUrl = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .addQueryParameter("client", "pc")
            .addQueryParameter("ver", "1")
            .addQueryParameter("id", candidate.first)
            .addQueryParameter("accesskey", candidate.second)
            .build()
        val lrcText = parseKuGouDownloadPayload(httpGet(dlUrl.toString()).orEmpty()) ?: return@withContext null
        val lines = NeveraLyricAiEngine.parseLrcOrEnhanced(lrcText)
        if (lines.isEmpty()) null
        else SourceCandidate(lines, isSynced = true, hasNativeWordSync = false)
    }

    private fun extractKuGouCandidate(raw: String?): Pair<String, String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val first = json.parseToJsonElement(raw).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val id = first["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val key = first["accesskey"]?.jsonPrimitive?.contentOrNull ?: return null
            id to key
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseKuGouDownloadPayload(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val b64 = json.parseToJsonElement(raw).jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: return null
            String(Base64.getDecoder().decode(b64), Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** 10. LRCLIB synced/plain fallback sorted by closest track duration. */
    suspend fun fetchLrcLib(title: String, artist: String, durationSec: Long): SourceCandidate? = withContext(Dispatchers.IO) {
        val directUrl = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        httpGet(directUrl.toString())?.let { body ->
            parseLrcLibObj(body)?.let { return@withContext it }
        }

        val searchUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$title $artist".trim())
            .build()
        val rawArr = httpGet(searchUrl.toString()) ?: return@withContext null
        try {
            val arr = json.parseToJsonElement(rawArr).jsonArray
                .map { it.jsonObject }
                .sortedBy { obj ->
                    val d = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
                    if (durationSec > 0 && d > 0) abs(d - durationSec) else 0L
                }
            for (obj in arr) {
                val d = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
                if (durationSec > 0 && d > 0 && abs(d - durationSec) > 15L) continue
                val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty()
                parseLrcOrPlainCandidate(synced, plain)?.let { return@withContext it }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLrcLibObj(raw: String): SourceCandidate? {
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty()
            parseLrcOrPlainCandidate(synced, plain)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLrcOrPlainCandidate(synced: String, plain: String): SourceCandidate? {
        if (synced.isNotBlank()) {
            val lines = NeveraLyricAiEngine.parseLrcOrEnhanced(synced)
            if (lines.isNotEmpty()) {
                return SourceCandidate(lines, isSynced = true, hasNativeWordSync = lines.any { it.words.isNotEmpty() })
            }
        }
        if (plain.isNotBlank()) {
            val lines = plain.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapIndexed { idx, s -> LyricLine(timeMs = idx * 3200L, text = s) }
            if (lines.isNotEmpty()) {
                return SourceCandidate(lines, isSynced = false, hasNativeWordSync = false)
            }
        }
        return null
    }
}
