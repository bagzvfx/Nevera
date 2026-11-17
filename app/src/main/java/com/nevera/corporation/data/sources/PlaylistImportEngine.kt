package com.nevera.corporation.data.sources

import com.nevera.corporation.data.model.CollectionType
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.store.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

data class ImportedPlaylistResult(
    val name: String,
    val songs: List<Song>,
    val totalRequested: Int
)

/**
 * Multi-service Playlist Importer supporting:
 * - Spotify public playlists / albums
 * - YouTube & YouTube Music playlists (?list=...)
 * - Apple Music & SoundCloud public links (JSON-LD / OpenGraph scraping + TrackMatcher)
 */
object PlaylistImportEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun extractYouTubePlaylistId(url: String): String? {
        val m = Regex("""[?&]list=([a-zA-Z0-9_-]+)""").find(url.trim())
        return m?.groupValues?.get(1)
    }

    fun isSpotifyPlaylistOrAlbum(url: String): Boolean {
        return url.contains("open.spotify.com/playlist/", true) ||
            url.contains("open.spotify.com/album/", true) ||
            url.startsWith("spotify:playlist:", true)
    }

    suspend fun importFromUrl(
        rawUrl: String,
        onProgress: (matched: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportedPlaylistResult? = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        if (url.isBlank()) return@withContext null

        // 1. YouTube / YouTube Music playlist link
        extractYouTubePlaylistId(url)?.let { listId ->
            val seed = Song(
                id = listId,
                title = "Imported Playlist",
                artist = "YouTube",
                browseId = if (listId.startsWith("VL")) listId else "VL$listId",
                browseType = CollectionType.PLAYLIST,
                sourceTrackId = ""
            )
            val detail = InnertubeClient.browseCollection(seed)
            if (detail != null && detail.tracks.isNotEmpty()) {
                val res = ImportedPlaylistResult(
                    name = detail.title.ifBlank { "Imported Playlist" },
                    songs = detail.tracks,
                    totalRequested = detail.tracks.size
                )
                LibraryStore.saveImportedPlaylist(res.name, res.songs, sourceUrl = url)
                onProgress(res.songs.size, res.totalRequested)
                return@withContext res
            }
        }

        // 2. Spotify public playlist/album
        if (isSpotifyPlaylistOrAlbum(url)) {
            val (plName, spTracks) = extractSpotifyPlaylistTitleAndTracks(url)
            if (spTracks.isNotEmpty()) {
                val matched = resolveTracksConcurrently(spTracks, onProgress)
                if (matched.isNotEmpty()) {
                    LibraryStore.saveImportedPlaylist(plName, matched, sourceUrl = url)
                    return@withContext ImportedPlaylistResult(plName, matched, spTracks.size)
                }
            }
        }

        // 3. Generic Apple Music / SoundCloud / JSON-LD page extractor
        val (title, genericTracks) = scrapeGenericMusicPage(url)
        if (genericTracks.isNotEmpty()) {
            val matched = resolveTracksConcurrently(genericTracks, onProgress)
            if (matched.isNotEmpty()) {
                LibraryStore.saveImportedPlaylist(title, matched, sourceUrl = url)
                return@withContext ImportedPlaylistResult(title, matched, genericTracks.size)
            }
        }

        null
    }

    private suspend fun resolveTracksConcurrently(
        seeds: List<Pair<String, String>>,
        onProgress: (Int, Int) -> Unit
    ): List<Song> = coroutineScope {
        val semaphore = Semaphore(4)
        var doneCount = 0
        val total = seeds.size
        val jobs = seeds.take(60).map { (title, artist) ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val query = "$title $artist".trim()
                    val yt = InnertubeClient.searchSongs(query, songsOnly = true)
                    val picked = TrackMatcher.pickBestMatch(
                        targetTitle = title,
                        targetArtist = artist,
                        targetDurationSec = 0L,
                        candidates = yt
                    ) ?: yt.firstOrNull()
                    synchronized(this@coroutineScope) {
                        doneCount++
                        onProgress(doneCount, total)
                    }
                    picked
                }
            }
        }
        jobs.awaitAll().filterNotNull()
    }

    private fun extractSpotifyPlaylistTitleAndTracks(rawUrl: String): Pair<String, List<Pair<String, String>>> {
        val normalizedUrl = if (rawUrl.startsWith("spotify:playlist:")) {
            "https://open.spotify.com/playlist/${rawUrl.removePrefix("spotify:playlist:")}"
        } else rawUrl

        val match = Regex("""open\.spotify\.com/(?:intl-[^/]+/)?(playlist|album)/([a-zA-Z0-9]+)""")
            .find(normalizedUrl) ?: return "Imported Playlist" to emptyList()
        val type = match.groupValues[1]
        val id = match.groupValues[2]

        return try {
            val req = Request.Builder()
                .url("https://open.spotify.com/embed/$type/$id")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "Imported Playlist" to emptyList()
                val html = resp.body?.string().orEmpty()
                val script = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.get(1) ?: return "Imported Playlist" to emptyList()
                val root = json.parseToJsonElement(script).jsonObject
                val entity = root["props"]?.jsonObject
                    ?.get("pageProps")?.jsonObject
                    ?.get("state")?.jsonObject
                    ?.get("data")?.jsonObject
                    ?.get("entity")?.jsonObject
                    ?: return "Imported Playlist" to emptyList()
                val name = entity["name"]?.jsonPrimitive?.contentOrNull
                    ?: entity["title"]?.jsonPrimitive?.contentOrNull
                    ?: "Imported Playlist"
                val trackList = entity["trackList"]?.jsonArray ?: JsonArray(emptyList())
                val pairs = trackList.mapNotNull { el ->
                    val obj = el.jsonObject
                    val t = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val a = obj["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (t.isBlank()) null else (t to a)
                }
                name to pairs
            }
        } catch (_: Exception) {
            "Imported Playlist" to emptyList()
        }
    }

    private fun scrapeGenericMusicPage(url: String): Pair<String, List<Pair<String, String>>> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "Imported Playlist" to emptyList()
        if (uri.scheme !in setOf("http", "https")) return "Imported Playlist" to emptyList()
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "Imported Playlist" to emptyList()
                val html = resp.body?.string().orEmpty()
                val pageTitle = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.trim() ?: "Imported Playlist"
                val pairs = mutableListOf<Pair<String, String>>()

                // Parse any application/ld+json MusicPlaylist / MusicRecording scripts
                val ldRegex = Regex("""<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                for (m in ldRegex.findAll(html)) {
                    runCatching {
                        val el = json.parseToJsonElement(m.groupValues[1])
                        collectJsonLdTracks(el, pairs)
                    }
                }
                pageTitle to pairs.distinct()
            }
        } catch (_: Exception) {
            "Imported Playlist" to emptyList()
        }
    }

    private fun collectJsonLdTracks(el: kotlinx.serialization.json.JsonElement, out: MutableList<Pair<String, String>>) {
        when (el) {
            is JsonObject -> {
                val type = el["@type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (type.equals("MusicRecording", true)) {
                    val name = el["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val artist = (el["byArtist"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (name.isNotBlank()) out.add(name to artist)
                }
                el.values.forEach { collectJsonLdTracks(it, out) }
            }
            is JsonArray -> el.forEach { collectJsonLdTracks(it, out) }
            else -> Unit
        }
    }
}
