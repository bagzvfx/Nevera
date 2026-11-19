package com.nevera.corporation.data.sources

import android.util.Base64
import com.nevera.corporation.data.model.NetworkServerConfig
import com.nevera.corporation.data.model.NetworkServerType
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import com.nevera.corporation.data.model.SourceStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ServerBrowseEntry(
    val isDirectory: Boolean,
    val name: String,
    val path: String,
    val song: Song? = null
)

/**
 * Pure-OkHttp lossless network music client supporting:
 * - Subsonic / OpenSubsonic (Navidrome, Jellyfin, Gonic, Airsonic) with raw lossless streaming
 * - WebDAV (PROPFIND) & Direct HTTP/HTTPS music folders with HTTP Basic Auth & byte-range streaming
 */
object NetworkStorageClient {

    private val AUDIO_EXTS = setOf("flac", "wav", "alac", "aiff", "aif", "m4a", "opus", "ogg", "mp3", "aac", "wv")
    private val LOSSLESS_EXTS = setOf("flac", "wav", "alac", "aiff", "aif", "wv")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    internal fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    internal fun buildSubsonicAuthQuery(
        username: String,
        password: String,
        salt: String = UUID.randomUUID().toString().replace("-", "").take(10)
    ): String {
        val u = URLEncoder.encode(username, "UTF-8")
        val token = md5Hex(password + salt)
        return "u=$u&t=$token&s=$salt&v=1.16.1&c=Nevera&f=json"
    }

    fun buildBasicAuthHeaders(config: NetworkServerConfig): Map<String, String> {
        if (config.username.isBlank() && config.password.isBlank()) return emptyMap()
        val raw = "${config.username}:${config.password}".toByteArray(Charsets.UTF_8)
        val encoded = (try {
            Base64.encodeToString(raw, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        })?.takeIf { it.isNotBlank() } ?: java.util.Base64.getEncoder().encodeToString(raw)
        return mapOf("Authorization" to "Basic $encoded")
    }

    private fun cleanBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    suspend fun testConnection(config: NetworkServerConfig): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = cleanBaseUrl(config.baseUrl)
        try {
            when (config.type) {
                NetworkServerType.SUBSONIC -> {
                    val auth = buildSubsonicAuthQuery(config.username, config.password)
                    val req = Request.Builder().url("$base/rest/ping?$auth").get().build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) return@withContext false to "HTTP ${resp.code}"
                        val sub = JSONObject(body).optJSONObject("subsonic-response")
                        val status = sub?.optString("status").orEmpty()
                        if (status.equals("ok", true)) {
                            val ver = sub?.optString("serverVersion").orEmpty()
                            true to if (ver.isNotBlank()) "Connected ($ver)" else "Connected"
                        } else {
                            val errMsg = sub?.optJSONObject("error")?.optString("message").orEmpty()
                            false to errMsg.ifBlank { "Authentication failed" }
                        }
                    }
                }

                NetworkServerType.WEBDAV -> {
                    val headers = buildBasicAuthHeaders(config)
                    val reqBuilder = Request.Builder()
                        .url("$base/")
                        .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
                        .header("Depth", "0")
                    headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        if (resp.code in 200..299 || resp.code == 207) {
                            return@withContext true to "WebDAV Connected"
                        }
                    }
                    // Fallback to direct HTTP GET index check
                    val getBuilder = Request.Builder().url("$base/").get()
                    headers.forEach { (k, v) -> getBuilder.header(k, v) }
                    client.newCall(getBuilder.build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            true to "HTTP Index Connected"
                        } else {
                            false to "HTTP ${resp.code}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: "Connection failed")
        }
    }

    suspend fun browseServer(config: NetworkServerConfig, subPath: String = ""): List<ServerBrowseEntry> =
        withContext(Dispatchers.IO) {
            when (config.type) {
                NetworkServerType.SUBSONIC -> browseSubsonicLibrary(config).map { song ->
                    ServerBrowseEntry(isDirectory = false, name = song.title, path = song.remotePath, song = song)
                }
                NetworkServerType.WEBDAV -> browseWebDavOrHttp(config, subPath)
            }
        }

    suspend fun searchSubsonic(config: NetworkServerConfig, query: String, limit: Int = 15): List<Song> =
        withContext(Dispatchers.IO) {
            if (config.type != NetworkServerType.SUBSONIC || query.isBlank()) return@withContext emptyList()
            val base = cleanBaseUrl(config.baseUrl)
            val auth = buildSubsonicAuthQuery(config.username, config.password)
            val qEnc = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$base/rest/search3?query=$qEnc&songCount=$limit&artistCount=0&albumCount=0&$auth"
            try {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val root = JSONObject(resp.body?.string().orEmpty())
                        .optJSONObject("subsonic-response")
                        ?.optJSONObject("searchResult3")
                    val arr = root?.optJSONArray("song") ?: return@withContext emptyList()
                    parseSubsonicSongsArray(arr, config, base)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    private fun browseSubsonicLibrary(config: NetworkServerConfig): List<Song> {
        val base = cleanBaseUrl(config.baseUrl)
        val auth = buildSubsonicAuthQuery(config.username, config.password)
        val url = "$base/rest/getRandomSongs?size=60&$auth"
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val root = JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("subsonic-response")
                    ?.optJSONObject("randomSongs")
                val arr = root?.optJSONArray("song") ?: return emptyList()
                parseSubsonicSongsArray(arr, config, base)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseSubsonicSongsArray(
        arr: JSONArray,
        config: NetworkServerConfig,
        base: String
    ): List<Song> {
        val auth = buildSubsonicAuthQuery(config.username, config.password)
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val trackId = obj.optString("id").trim()
            if (trackId.isBlank()) continue
            val title = obj.optString("title").ifBlank { "Untitled Track" }
            val artist = obj.optString("artist").ifBlank { "Unknown Artist" }
            val album = obj.optString("album")
            val dur = obj.optLong("duration", 0L)
            val suffix = obj.optString("suffix").uppercase(Locale.US).ifBlank { "FLAC" }
            val bitRate = obj.optInt("bitRate", 0)
            val sampleRate = obj.optInt("samplingRate", 0)
            val bitDepth = obj.optInt("bitDepth", 0)
            val coverArtId = obj.optString("coverArt")
            val artUrl = if (coverArtId.isNotBlank()) {
                "$base/rest/getCoverArt?id=${URLEncoder.encode(coverArtId, "UTF-8")}&size=600&$auth"
            } else ""
            val streamUrl = "$base/rest/stream?id=${URLEncoder.encode(trackId, "UTF-8")}&format=raw&$auth"

            out.add(
                Song(
                    id = "srv_${config.id}_$trackId",
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = dur,
                    thumbnailUrl = artUrl,
                    source = SourceKind.NETWORK_SERVER,
                    sourceTrackId = trackId,
                    directStreamUrl = streamUrl,
                    codecHint = suffix,
                    sampleRateHz = sampleRate,
                    bitDepth = bitDepth,
                    bitrateKbps = bitRate,
                    serverId = config.id,
                    remotePath = trackId
                )
            )
        }
        return out
    }

    private fun browseWebDavOrHttp(config: NetworkServerConfig, subPath: String): List<ServerBrowseEntry> {
        val base = cleanBaseUrl(config.baseUrl)
        val normalizedSub = subPath.trimStart('/')
        val targetUrl = if (normalizedSub.isBlank()) "$base/" else "$base/$normalizedSub".let {
            if (it.endsWith("/")) it else "$it/"
        }
        val headers = buildBasicAuthHeaders(config)

        // 1. Try WebDAV PROPFIND Depth: 1
        try {
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
                .header("Depth", "1")
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 207 || (resp.isSuccessful && resp.body?.contentType()?.subtype?.contains("xml", true) == true)) {
                    val xml = resp.body?.string().orEmpty()
                    val entries = parseWebDavXml(xml, targetUrl, config)
                    if (entries.isNotEmpty()) return entries
                }
            }
        } catch (_: Exception) {
        }

        // 2. Fallback to standard HTTP directory HTML index
        return try {
            val reqBuilder = Request.Builder().url(targetUrl).get()
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val html = resp.body?.string().orEmpty()
                parseHtmlDirectoryIndex(html, targetUrl, config)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val davResponseRegex = Regex("""(?is)<(?:\w+:)?response\b[^>]*>(.*?)</(?:\w+:)?response>""")
    private val davHrefRegex = Regex("""(?is)<(?:\w+:)?href\b[^>]*>(.*?)</(?:\w+:)?href>""")
    private val davCollectionRegex = Regex("""(?is)<(?:\w+:)?collection\s*/?>""")

    internal fun parseWebDavXml(
        xml: String,
        folderUrl: String,
        config: NetworkServerConfig
    ): List<ServerBrowseEntry> {
        val baseUri = runCatching { URI(folderUrl) }.getOrNull() ?: return emptyList()
        val selfPath = baseUri.path.trimEnd('/')
        val out = mutableListOf<ServerBrowseEntry>()

        for (match in davResponseRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val rawHref = davHrefRegex.find(block)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val resolved = runCatching { baseUri.resolve(rawHref) }.getOrNull() ?: continue
            val resolvedPath = resolved.path.trimEnd('/')
            if (resolvedPath == selfPath || resolvedPath.isBlank()) continue

            val isDir = davCollectionRegex.containsMatchIn(block) || rawHref.endsWith("/")
            val decodedSegment = decodePathSegment(resolvedPath.substringAfterLast('/'))
            if (decodedSegment.isBlank() || decodedSegment.startsWith(".")) continue

            val relativeSub = computeRelativeSubPath(config.baseUrl, resolved.toString())
            if (isDir) {
                out.add(ServerBrowseEntry(isDirectory = true, name = decodedSegment, path = relativeSub))
            } else {
                val ext = decodedSegment.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext in AUDIO_EXTS) {
                    val song = buildFileSong(config, decodedSegment, ext, resolved.toString(), relativeSub)
                    out.add(ServerBrowseEntry(isDirectory = false, name = song.title, path = relativeSub, song = song))
                }
            }
        }
        return out.sortedWith(compareByDescending<ServerBrowseEntry> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
    }

    private val htmlAnchorRegex = Regex("""(?is)<a\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*>""")

    internal fun parseHtmlDirectoryIndex(
        html: String,
        folderUrl: String,
        config: NetworkServerConfig
    ): List<ServerBrowseEntry> {
        val baseUri = runCatching { URI(folderUrl) }.getOrNull() ?: return emptyList()
        val selfPath = baseUri.path.trimEnd('/')
        val seen = HashSet<String>()
        val out = mutableListOf<ServerBrowseEntry>()

        for (m in htmlAnchorRegex.findAll(html)) {
            val href = m.groupValues[1].trim()
            if (href.isBlank() || href.startsWith("?") || href.startsWith("#") || href == "../" || href == "/") continue
            val resolved = runCatching { baseUri.resolve(href) }.getOrNull() ?: continue
            val resolvedPath = resolved.path.trimEnd('/')
            if (resolvedPath == selfPath || !seen.add(resolvedPath)) continue

            val isDir = href.endsWith("/")
            val segment = decodePathSegment(resolvedPath.substringAfterLast('/'))
            if (segment.isBlank() || segment.startsWith(".")) continue

            val relativeSub = computeRelativeSubPath(config.baseUrl, resolved.toString())
            if (isDir) {
                out.add(ServerBrowseEntry(isDirectory = true, name = segment, path = relativeSub))
            } else {
                val ext = segment.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext in AUDIO_EXTS) {
                    val song = buildFileSong(config, segment, ext, resolved.toString(), relativeSub)
                    out.add(ServerBrowseEntry(isDirectory = false, name = song.title, path = relativeSub, song = song))
                }
            }
        }
        return out.sortedWith(compareByDescending<ServerBrowseEntry> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
    }

    private fun buildFileSong(
        config: NetworkServerConfig,
        fileName: String,
        ext: String,
        fileUrl: String,
        relativePath: String
    ): Song {
        val stem = fileName.substringBeforeLast('.')
        val parts = stem.split(" - ", limit = 2)
        val artist = if (parts.size == 2) parts[0].trim() else config.name
        val title = if (parts.size == 2) parts[1].trim() else stem.trim()
        val codec = ext.uppercase(Locale.US)
        val isLossless = ext in LOSSLESS_EXTS
        return Song(
            id = "srv_${config.id}_${md5Hex(relativePath).take(12)}",
            title = title.ifBlank { fileName },
            artist = artist.ifBlank { config.name },
            album = config.name,
            source = SourceKind.NETWORK_SERVER,
            sourceTrackId = relativePath,
            directStreamUrl = fileUrl,
            codecHint = codec,
            bitDepth = if (isLossless) 16 else 0,
            bitrateKbps = if (isLossless) 1000 else 320,
            serverId = config.id,
            remotePath = relativePath
        )
    }

    fun buildStreamForSong(song: Song, config: NetworkServerConfig): SourceStream? {
        val base = cleanBaseUrl(config.baseUrl)
        val codec = song.codecHint.ifBlank { "FLAC" }.uppercase(Locale.US)
        val lossless = song.isLosslessHint || codec in setOf("FLAC", "WAV", "ALAC", "AIFF", "WV")
        return when (config.type) {
            NetworkServerType.SUBSONIC -> {
                val trackId = song.sourceTrackId.ifBlank { song.remotePath }
                if (trackId.isBlank()) return null
                val auth = buildSubsonicAuthQuery(config.username, config.password)
                val url = "$base/rest/stream?id=${URLEncoder.encode(trackId, "UTF-8")}&format=raw&$auth"
                SourceStream(
                    url = url,
                    codec = codec,
                    kbps = song.bitrateKbps.takeIf { it > 0 } ?: if (lossless) 1000 else 320,
                    qualityLabel = if (lossless) "Lossless $codec · ${config.name}" else "$codec · ${config.name}",
                    sampleRateHz = song.sampleRateHz,
                    bitDepth = song.bitDepth,
                    isLossless = lossless,
                    originLabel = "Lossless Server (${config.name})"
                )
            }

            NetworkServerType.WEBDAV -> {
                val url = song.directStreamUrl.takeIf { it.isNotBlank() }
                    ?: "$base/${song.remotePath.trimStart('/')}"
                SourceStream(
                    url = url,
                    codec = codec,
                    kbps = song.bitrateKbps.takeIf { it > 0 } ?: if (lossless) 1000 else 320,
                    qualityLabel = if (lossless) "Lossless $codec · ${config.name}" else "$codec · ${config.name}",
                    headers = buildBasicAuthHeaders(config),
                    sampleRateHz = song.sampleRateHz,
                    bitDepth = song.bitDepth,
                    isLossless = lossless,
                    originLabel = "Network Storage (${config.name})"
                )
            }
        }
    }

    private fun decodePathSegment(raw: String): String {
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    private fun computeRelativeSubPath(baseRaw: String, fullUrl: String): String {
        val base = cleanBaseUrl(baseRaw)
        return fullUrl.removePrefix(base).trimStart('/')
    }
}
