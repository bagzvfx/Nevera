package com.nevera.corporation.data.sources

import com.nevera.corporation.data.model.CollectionDetail
import com.nevera.corporation.data.model.CollectionType
import com.nevera.corporation.data.model.HomeShelf
import com.nevera.corporation.data.model.LyricLine
import com.nevera.corporation.data.model.MoodGenre
import com.nevera.corporation.data.model.SearchFilter
import com.nevera.corporation.data.model.ShelfLayout
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * InnerTube WEB_REMIX API client for Home, Explore/Charts, Filtered Search (Songs/Artists/Albums),
 * Collections, Up-Next recommendations, and authenticated Library Sync.
 */
object InnertubeClient {

    private const val BASE_URL = "https://music.youtube.com/youtubei/v1"
    private const val CLIENT_VERSION = "1.20241106.01.00"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    val defaultMoodGenres = listOf(
        MoodGenre("Pop Hits", "Pop Top Hits", 0xFF1DB954),
        MoodGenre("Lofi & Focus", "Lofi hip hop study beats", 0xFF6C63FF),
        MoodGenre("J-Pop & Anime", "J-Pop trending hits", 0xFFEC4899),
        MoodGenre("Chill Acoustic", "Acoustic indie chill", 0xFFE67E22),
        MoodGenre("R&B & Soul", "RnB smooth hits", 0xFFE91E63),
        MoodGenre("Rock & Indie", "Alternative rock hits", 0xFFE53935)
    )

    private fun buildContextBody(extra: Map<String, String>): String {
        val obj = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "ID")
                }
            }
            extra.forEach { (k, v) -> put(k, v) }
        }
        return obj.toString()
    }

    private fun post(endpoint: String, extra: Map<String, String>, authenticated: Boolean = false): JsonObject? {
        return try {
            val body = buildContextBody(extra)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val reqBuilder = Request.Builder()
                .url("$BASE_URL/$endpoint?prettyPrint=false")
                .post(body)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
                )
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")

            if (authenticated) {
                AccountSyncManager.authHeaders().forEach { (k, v) ->
                    reqBuilder.header(k, v)
                }
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return null
                json.parseToJsonElement(raw).jsonObject
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Strictly Home feed only (no explore mixing!). */
    suspend fun getHomeFeedOnly(): List<HomeShelf> = withContext(Dispatchers.IO) {
        val root = post("browse", mapOf("browseId" to "FEmusic_home"), authenticated = AccountSyncManager.isSignedIn.value)
            ?: return@withContext emptyList()
        parseBrowseSections(root, isHome = true)
    }

    /** Strictly Explore, Charts & New Releases feed. */
    suspend fun getExploreShelves(): List<HomeShelf> = withContext(Dispatchers.IO) {
        val exploreRoot = post("browse", mapOf("browseId" to "FEmusic_explore"))
        val chartsRoot = post("browse", mapOf("browseId" to "FEmusic_charts"))
        val shelves = mutableListOf<HomeShelf>()
        if (chartsRoot != null) {
            shelves.addAll(parseBrowseSections(chartsRoot, isHome = false))
        }
        if (exploreRoot != null) {
            shelves.addAll(parseBrowseSections(exploreRoot, isHome = false))
        }
        shelves.distinctBy { it.title }
    }

    suspend fun getUserAccountPlaylists(): List<Song> = withContext(Dispatchers.IO) {
        if (!AccountSyncManager.isSignedIn.value) return@withContext emptyList()
        val root = post("browse", mapOf("browseId" to "FEmusic_liked_playlists"), authenticated = true)
            ?: return@withContext emptyList()
        val out = mutableListOf<Song>()
        collectTwoRowItems(root, out)
        out.filter { it.isCollection }.distinctBy { it.browseId }
    }

    suspend fun browseCollection(seed: Song): CollectionDetail? = withContext(Dispatchers.IO) {
        val bId = seed.browseId.ifBlank { return@withContext null }
        val normalizedBrowse = if (bId.startsWith("PL") || bId.startsWith("LM") || bId.startsWith("OLAK")) {
            if (bId.startsWith("VL")) bId else "VL$bId"
        } else bId

        val root = post("browse", mapOf("browseId" to normalizedBrowse), authenticated = AccountSyncManager.isSignedIn.value)
            ?: post("browse", mapOf("browseId" to bId))
            ?: return@withContext null

        val header = root["header"]?.objOrNull()
        val detailHeader = header?.get("musicDetailHeaderRenderer")?.objOrNull()
            ?: header?.get("musicResponsiveHeaderRenderer")?.objOrNull()
            ?: header?.get("musicImmersiveHeaderRenderer")?.objOrNull()

        val title = detailHeader?.get("title")?.runsText().orEmpty().ifBlank { seed.title }
        val subtitle = detailHeader?.get("subtitle")?.runsText().orEmpty().ifBlank { seed.artist }
        val thumb = detailHeader?.get("thumbnail")?.objOrNull()
            ?.get("croppedSquareThumbnailRenderer")?.objOrNull()
            ?.get("thumbnail")?.objOrNull()
            ?.get("thumbnails")?.arrOrNull()?.lastOrNull()?.objOrNull()?.get("url")?.strOrNull()
            ?.upgradeThumb()
            ?: detailHeader?.get("thumbnail")?.objOrNull()
                ?.get("musicThumbnailRenderer")?.objOrNull()
                ?.get("thumbnail")?.objOrNull()
                ?.get("thumbnails")?.arrOrNull()?.lastOrNull()?.objOrNull()?.get("url")?.strOrNull()
                ?.upgradeThumb()
            ?: seed.thumbnailUrl

        val tracks = mutableListOf<Song>()
        collectResponsiveItems(root, tracks, fallbackThumb = thumb, fallbackArtist = seed.artist)

        val playableTracks = tracks.filterNot { it.isCollection }
        val finalTracks = if (playableTracks.isNotEmpty()) {
            playableTracks
        } else {
            val q = if (seed.browseType == CollectionType.ARTIST) seed.title else "${seed.title} ${seed.artist}"
            searchSongs(q, songsOnly = true)
        }

        CollectionDetail(
            browseId = bId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumb,
            type = seed.browseType ?: CollectionType.ALBUM,
            tracks = finalTracks
        )
    }

    suspend fun search(query: String, filter: SearchFilter): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val extra = buildMap {
            put("query", query)
            filter.params?.let { put("params", it) }
        }
        val root = post("search", extra) ?: return@withContext emptyList()

        val results = mutableListOf<Song>()
        val tabs = root["contents"]?.objOrNull()
            ?.get("tabbedSearchResultsRenderer")?.objOrNull()
            ?.get("tabs")?.arrOrNull() ?: return@withContext emptyList()

        val sections = tabs.firstOrNull()?.objOrNull()
            ?.get("tabRenderer")?.objOrNull()
            ?.get("content")?.objOrNull()
            ?.get("sectionListRenderer")?.objOrNull()
            ?.get("contents")?.arrOrNull() ?: return@withContext emptyList()

        for (sec in sections) {
            val secObj = sec.objOrNull() ?: continue
            // Card shelf (top result)
            secObj["musicCardShelfRenderer"]?.objOrNull()?.let { card ->
                val title = card["title"]?.runsText().orEmpty()
                val nav = card["title"]?.objOrNull()?.get("runs")?.arrOrNull()?.firstOrNull()?.objOrNull()
                    ?.get("navigationEndpoint")?.objOrNull()
                    ?: card["onTap"]?.objOrNull()
                parseNavigationEndpointItem(
                    title = title,
                    subtitle = card["subtitle"]?.runsText().orEmpty(),
                    thumb = card["thumbnail"]?.objOrNull()?.get("musicThumbnailRenderer")?.objOrNull()
                        ?.get("thumbnail")?.objOrNull()?.get("thumbnails")?.arrOrNull()?.lastOrNull()
                        ?.objOrNull()?.get("url")?.strOrNull()?.upgradeThumb().orEmpty(),
                    nav = nav
                )?.let { item ->
                    if (matchesFilter(item, filter)) results.add(item)
                }
            }

            val shelf = secObj["musicShelfRenderer"]?.objOrNull() ?: continue
            val contents = shelf["contents"]?.arrOrNull() ?: continue
            for (item in contents) {
                val renderer = item.objOrNull()?.get("musicResponsiveListItemRenderer")?.objOrNull() ?: continue
                parseResponsiveItem(renderer, filterOverride = filter)?.let { parsed ->
                    if (matchesFilter(parsed, filter)) results.add(parsed)
                }
            }
        }
        results.distinctBy { it.id }
    }

    private fun matchesFilter(song: Song, filter: SearchFilter): Boolean = when (filter) {
        SearchFilter.ALL -> true
        SearchFilter.SONGS -> !song.isCollection
        SearchFilter.ARTISTS -> song.browseType == CollectionType.ARTIST
        SearchFilter.ALBUMS -> song.browseType == CollectionType.ALBUM
    }

    suspend fun searchSongs(query: String, songsOnly: Boolean = true): List<Song> {
        return search(query, if (songsOnly) SearchFilter.SONGS else SearchFilter.ALL)
            .filterNot { it.isCollection }
    }

    suspend fun getUpNext(videoId: String): List<Song> = withContext(Dispatchers.IO) {
        val cleanId = videoId.removePrefix("yt_").trim()
        if (cleanId.isBlank()) return@withContext emptyList()
        val root = post("next", mapOf("videoId" to cleanId, "playlistId" to "RDAMVM$cleanId"))
            ?: return@withContext emptyList()

        val tabs = root["contents"]?.objOrNull()
            ?.get("singleColumnMusicWatchNextResultsRenderer")?.objOrNull()
            ?.get("tabbedRenderer")?.objOrNull()
            ?.get("watchNextTabbedResultsRenderer")?.objOrNull()
            ?.get("tabs")?.arrOrNull() ?: return@withContext emptyList()

        val contents = tabs.firstOrNull()?.objOrNull()
            ?.get("tabRenderer")?.objOrNull()
            ?.get("content")?.objOrNull()
            ?.get("musicQueueRenderer")?.objOrNull()
            ?.get("content")?.objOrNull()
            ?.get("playlistPanelRenderer")?.objOrNull()
            ?.get("contents")?.arrOrNull() ?: return@withContext emptyList()

        contents.mapNotNull { el ->
            val r = el.objOrNull()?.get("playlistPanelVideoRenderer")?.objOrNull() ?: return@mapNotNull null
            val vid = r["videoId"]?.strOrNull() ?: return@mapNotNull null
            val title = r["title"]?.runsText().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val artist = r["longBylineText"]?.runsText()?.substringBefore(" • ")?.trim()
                ?: r["shortBylineText"]?.runsText().orEmpty()
            val durStr = r["lengthText"]?.runsText().orEmpty()
            val thumb = r["thumbnail"]?.objOrNull()?.get("thumbnails")?.arrOrNull()
                ?.lastOrNull()?.objOrNull()?.get("url")?.strOrNull()
                ?.upgradeThumb().orEmpty()

            Song(
                id = vid,
                title = title,
                artist = artist.ifBlank { "Artist" },
                durationSeconds = parseDurationToSec(durStr),
                thumbnailUrl = thumb,
                source = SourceKind.YOUTUBE,
                sourceTrackId = vid
            )
        }
    }

    /**
     * Fetches YouTube Music's official timed lyrics (`timedLyricsRenderer`) or plain lyrics shelf
     * for a track via `next` -> `browse` without exposing any provider names.
     */
    suspend fun getTimedLyrics(videoId: String): Pair<List<LyricLine>, Boolean>? = withContext(Dispatchers.IO) {
        val cleanId = videoId.removePrefix("yt_").removePrefix("saavn_").trim()
        if (cleanId.isBlank() || cleanId.startsWith("sp_") || cleanId.startsWith("local_")) {
            return@withContext null
        }

        val nextRoot = post("next", mapOf("videoId" to cleanId)) ?: return@withContext null
        val tabs = nextRoot["contents"]?.objOrNull()
            ?.get("singleColumnMusicWatchNextResultsRenderer")?.objOrNull()
            ?.get("tabbedRenderer")?.objOrNull()
            ?.get("watchNextTabbedResultsRenderer")?.objOrNull()
            ?.get("tabs")?.arrOrNull() ?: return@withContext null

        val lyricsBrowseId = tabs.firstNotNullOfOrNull { el ->
            val tr = el.objOrNull()?.get("tabRenderer")?.objOrNull() ?: return@firstNotNullOfOrNull null
            val bId = tr["endpoint"]?.objOrNull()
                ?.get("browseEndpoint")?.objOrNull()
                ?.get("browseId")?.strOrNull().orEmpty()
            val title = tr["title"]?.strOrNull().orEmpty()
            if (bId.startsWith("MPLYt") || (bId.isNotBlank() && title.equals("Lyrics", ignoreCase = true))) bId else null
        } ?: return@withContext null

        val browseRoot = post("browse", mapOf("browseId" to lyricsBrowseId)) ?: return@withContext null

        val syncedOut = mutableListOf<LyricLine>()
        extractTimedLyricsModel(browseRoot, syncedOut)
        if (syncedOut.isNotEmpty()) {
            return@withContext syncedOut.sortedBy { it.timeMs } to true
        }

        val plainText = extractPlainLyricsDescription(browseRoot)
        if (!plainText.isNullOrBlank()) {
            val lines = plainText.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapIndexed { idx, line -> LyricLine(timeMs = idx * 3200L, text = line) }
            if (lines.isNotEmpty()) {
                return@withContext lines to false
            }
        }
        null
    }

    private fun extractTimedLyricsModel(el: JsonElement, out: MutableList<LyricLine>) {
        when (el) {
            is JsonObject -> {
                val model = el["timedLyricsModel"]?.objOrNull()
                if (model != null) {
                    val data = model["lyricsData"]?.arrOrNull() ?: return
                    for (entry in data) {
                        val obj = entry.objOrNull() ?: continue
                        val text = obj["lyricLine"]?.strOrNull()?.trim().orEmpty()
                        val cue = obj["cueRange"]?.objOrNull() ?: continue
                        val startMs = cue["startTimeMilliseconds"]?.strOrNull()?.toLongOrNull() ?: continue
                        val endMs = cue["endTimeMilliseconds"]?.strOrNull()?.toLongOrNull() ?: 0L
                        if (text.isNotBlank()) {
                            out.add(LyricLine(timeMs = startMs, text = text, endTimeMs = endMs))
                        }
                    }
                    return
                }
                el.values.forEach {
                    if (out.isEmpty()) extractTimedLyricsModel(it, out)
                }
            }
            is JsonArray -> el.forEach {
                if (out.isEmpty()) extractTimedLyricsModel(it, out)
            }
            else -> Unit
        }
    }

    private fun extractPlainLyricsDescription(el: JsonElement): String? {
        return when (el) {
            is JsonObject -> {
                el["musicDescriptionShelfRenderer"]?.objOrNull()
                    ?.get("description")?.runsText()?.takeIf { it.isNotBlank() }
                    ?: el.values.firstNotNullOfOrNull { extractPlainLyricsDescription(it) }
            }
            is JsonArray -> el.firstNotNullOfOrNull { extractPlainLyricsDescription(it) }
            else -> null
        }
    }

    private fun collectResponsiveItems(
        el: JsonElement,
        out: MutableList<Song>,
        fallbackThumb: String,
        fallbackArtist: String
    ) {
        when (el) {
            is JsonObject -> {
                el["musicResponsiveListItemRenderer"]?.objOrNull()?.let { r ->
                    parseResponsiveItem(r)?.let { song ->
                        out.add(
                            song.copy(
                                thumbnailUrl = song.thumbnailUrl.ifBlank { fallbackThumb },
                                artist = song.artist.ifBlank { fallbackArtist }
                            )
                        )
                    }
                    return
                }
                el.values.forEach { collectResponsiveItems(it, out, fallbackThumb, fallbackArtist) }
            }
            is JsonArray -> el.forEach { collectResponsiveItems(it, out, fallbackThumb, fallbackArtist) }
            else -> Unit
        }
    }

    private fun collectTwoRowItems(el: JsonElement, out: MutableList<Song>) {
        when (el) {
            is JsonObject -> {
                el["musicTwoRowItemRenderer"]?.objOrNull()?.let { r ->
                    parseTwoRowItem(r)?.let { out.add(it) }
                    return
                }
                el.values.forEach { collectTwoRowItems(it, out) }
            }
            is JsonArray -> el.forEach { collectTwoRowItems(it, out) }
            else -> Unit
        }
    }

    private fun parseBrowseSections(root: JsonObject, isHome: Boolean): List<HomeShelf> {
        val shelves = mutableListOf<HomeShelf>()
        val sections = root["contents"]?.objOrNull()
            ?.get("singleColumnBrowseResultsRenderer")?.objOrNull()
            ?.get("tabs")?.arrOrNull()?.firstOrNull()?.objOrNull()
            ?.get("tabRenderer")?.objOrNull()
            ?.get("content")?.objOrNull()
            ?.get("sectionListRenderer")?.objOrNull()
            ?.get("contents")?.arrOrNull() ?: return emptyList()

        for ((idx, sec) in sections.withIndex()) {
            val carousel = sec.objOrNull()?.get("musicCarouselShelfRenderer")?.objOrNull() ?: continue
            val header = carousel["header"]?.objOrNull()
                ?.get("musicCarouselShelfBasicHeaderRenderer")?.objOrNull()
                ?: continue
            val title = header["title"]?.runsText().orEmpty()
            if (title.isBlank()) continue

            val items = carousel["contents"]?.arrOrNull() ?: continue
            val parsedSongs = items.mapNotNull { el ->
                val obj = el.objOrNull() ?: return@mapNotNull null
                obj["musicResponsiveListItemRenderer"]?.objOrNull()?.let { parseResponsiveItem(it) }
                    ?: obj["musicTwoRowItemRenderer"]?.objOrNull()?.let { parseTwoRowItem(it) }
            }

            if (parsedSongs.isNotEmpty()) {
                val isList = items.firstOrNull()?.objOrNull()?.containsKey("musicResponsiveListItemRenderer") == true
                val layout = when {
                    isList -> ShelfLayout.FOUR_ROW_PAGER
                    isHome && idx == 0 -> ShelfLayout.TOP_PICKS_PORTRAIT
                    else -> ShelfLayout.CAROUSEL
                }
                shelves.add(
                    HomeShelf(
                        id = "shelf_${idx}_${title.hashCode()}",
                        title = title,
                        layout = layout,
                        items = parsedSongs
                    )
                )
            }
        }
        return shelves
    }

    private fun parseNavigationEndpointItem(
        title: String,
        subtitle: String,
        thumb: String,
        nav: JsonObject?,
        filterOverride: SearchFilter? = null
    ): Song? {
        if (title.isBlank() || nav == null) return null
        val vid = nav["watchEndpoint"]?.objOrNull()?.get("videoId")?.strOrNull()
        val browseEp = nav["browseEndpoint"]?.objOrNull()
        val bId = browseEp?.get("browseId")?.strOrNull().orEmpty()
        if (vid == null && bId.isBlank()) return null

        val pageType = browseEp?.get("browseEndpointContextSupportedConfigs")?.objOrNull()
            ?.get("browseEndpointContextMusicConfig")?.objOrNull()
            ?.get("pageType")?.strOrNull().orEmpty()

        val bType = when {
            pageType.contains("ARTIST", true) || bId.startsWith("UC") -> CollectionType.ARTIST
            pageType.contains("ALBUM", true) || bId.startsWith("MPRE") -> CollectionType.ALBUM
            pageType.contains("PLAYLIST", true) || bId.startsWith("VL") || bId.startsWith("PL") -> CollectionType.PLAYLIST
            filterOverride == SearchFilter.ARTISTS -> CollectionType.ARTIST
            filterOverride == SearchFilter.ALBUMS -> CollectionType.ALBUM
            else -> CollectionType.ALBUM
        }

        return Song(
            id = vid ?: bId,
            title = title,
            artist = subtitle.ifBlank { title },
            thumbnailUrl = thumb,
            source = SourceKind.YOUTUBE,
            sourceTrackId = vid ?: "",
            browseId = if (vid == null) bId else "",
            browseType = if (vid == null) bType else null
        )
    }

    private fun parseResponsiveItem(r: JsonObject, filterOverride: SearchFilter? = null): Song? {
        val videoId = r["playlistItemData"]?.objOrNull()?.get("videoId")?.strOrNull()
            ?: r["overlay"]?.objOrNull()
                ?.get("musicItemThumbnailOverlayRenderer")?.objOrNull()
                ?.get("content")?.objOrNull()
                ?.get("musicPlayButtonRenderer")?.objOrNull()
                ?.get("playNavigationEndpoint")?.objOrNull()
                ?.get("watchEndpoint")?.objOrNull()
                ?.get("videoId")?.strOrNull()

        val navEp = r["navigationEndpoint"]?.objOrNull()
        val browseEp = navEp?.get("browseEndpoint")?.objOrNull()
        val browseId = browseEp?.get("browseId")?.strOrNull().orEmpty()

        if (videoId == null && browseId.isBlank()) return null

        val cols = r["flexColumns"]?.arrOrNull() ?: return null
        val title = cols.getOrNull(0)?.objOrNull()
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.objOrNull()
            ?.get("text")?.runsText().orEmpty()
        if (title.isBlank()) return null

        val col1Runs = cols.getOrNull(1)?.objOrNull()
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.objOrNull()
            ?.get("text")?.objOrNull()?.get("runs")?.arrOrNull()

        val subtitleParts = col1Runs
            ?.mapNotNull { it.objOrNull()?.get("text")?.strOrNull() }
            ?.filter { it != " • " && it != " & " && it != ", " }
            .orEmpty()

        val cleanParts = subtitleParts.filterNot {
            it.equals("Lagu", true) || it.equals("Song", true) || it.equals("Video", true) ||
                it.equals("Artist", true) || it.equals("Artis", true) || it.equals("Album", true)
        }
        val artist = cleanParts.firstOrNull().orEmpty().ifBlank { if (browseId.isNotBlank()) subtitleParts.joinToString(" ") else "Artist" }
        val durationSec = cleanParts.lastOrNull()?.let { parseDurationToSec(it) } ?: 0L
        val album = if (cleanParts.size >= 3) cleanParts[1] else ""

        val thumb = r["thumbnail"]?.objOrNull()
            ?.get("musicThumbnailRenderer")?.objOrNull()
            ?.get("thumbnail")?.objOrNull()
            ?.get("thumbnails")?.arrOrNull()
            ?.lastOrNull()?.objOrNull()?.get("url")?.strOrNull()
            ?.upgradeThumb().orEmpty()

        if (videoId != null) {
            return Song(
                id = videoId,
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSec,
                thumbnailUrl = thumb,
                source = SourceKind.YOUTUBE,
                sourceTrackId = videoId
            )
        }

        val pageType = browseEp?.get("browseEndpointContextSupportedConfigs")?.objOrNull()
            ?.get("browseEndpointContextMusicConfig")?.objOrNull()
            ?.get("pageType")?.strOrNull().orEmpty()

        val browseType = when {
            pageType.contains("ARTIST", true) || browseId.startsWith("UC") -> CollectionType.ARTIST
            pageType.contains("ALBUM", true) || browseId.startsWith("MPRE") -> CollectionType.ALBUM
            pageType.contains("PLAYLIST", true) || browseId.startsWith("VL") || browseId.startsWith("PL") -> CollectionType.PLAYLIST
            filterOverride == SearchFilter.ARTISTS -> CollectionType.ARTIST
            filterOverride == SearchFilter.ALBUMS -> CollectionType.ALBUM
            else -> CollectionType.ALBUM
        }

        return Song(
            id = browseId,
            title = title,
            artist = artist,
            thumbnailUrl = thumb,
            source = SourceKind.YOUTUBE,
            sourceTrackId = "",
            browseId = browseId,
            browseType = browseType
        )
    }

    private fun parseTwoRowItem(r: JsonObject): Song? {
        val nav = r["navigationEndpoint"]?.objOrNull()
        val videoId = nav?.get("watchEndpoint")?.objOrNull()?.get("videoId")?.strOrNull()
            ?: r["thumbnailOverlay"]?.objOrNull()
                ?.get("musicItemThumbnailOverlayRenderer")?.objOrNull()
                ?.get("content")?.objOrNull()
                ?.get("musicPlayButtonRenderer")?.objOrNull()
                ?.get("playNavigationEndpoint")?.objOrNull()
                ?.get("watchEndpoint")?.objOrNull()
                ?.get("videoId")?.strOrNull()

        val browseEndpoint = nav?.get("browseEndpoint")?.objOrNull()
        val browseId = browseEndpoint?.get("browseId")?.strOrNull().orEmpty()
        val pageType = browseEndpoint?.get("browseEndpointContextSupportedConfigs")?.objOrNull()
            ?.get("browseEndpointContextMusicConfig")?.objOrNull()
            ?.get("pageType")?.strOrNull().orEmpty()

        val browseType = when {
            pageType.contains("ALBUM", true) -> CollectionType.ALBUM
            pageType.contains("PLAYLIST", true) -> CollectionType.PLAYLIST
            pageType.contains("ARTIST", true) -> CollectionType.ARTIST
            browseId.startsWith("MPRE") -> CollectionType.ALBUM
            browseId.startsWith("VL") || browseId.startsWith("PL") -> CollectionType.PLAYLIST
            browseId.startsWith("UC") -> CollectionType.ARTIST
            else -> null
        }

        val title = r["title"]?.runsText().orEmpty()
        if (title.isBlank()) return null

        val subtitleParts = r["subtitle"]?.objOrNull()?.get("runs")?.arrOrNull()
            ?.mapNotNull { it.objOrNull()?.get("text")?.strOrNull() }
            ?.filter { it != " • " }
            .orEmpty()
        val artist = subtitleParts.lastOrNull().orEmpty().ifBlank { title }

        val thumb = r["thumbnailRenderer"]?.objOrNull()
            ?.get("musicThumbnailRenderer")?.objOrNull()
            ?.get("thumbnail")?.objOrNull()
            ?.get("thumbnails")?.arrOrNull()
            ?.lastOrNull()?.objOrNull()?.get("url")?.strOrNull()
            ?.upgradeThumb().orEmpty()

        val effectiveId = videoId ?: browseId.ifBlank { "col_${title.hashCode()}" }
        return Song(
            id = effectiveId,
            title = title,
            artist = artist,
            thumbnailUrl = thumb,
            source = SourceKind.YOUTUBE,
            sourceTrackId = videoId ?: "",
            browseId = if (videoId == null) browseId else "",
            browseType = if (videoId == null) browseType else null
        )
    }

    internal fun parseDurationToSec(text: String): Long {
        val clean = text.trim().replace(".", ":")
        val parts = clean.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }

    private fun String.upgradeThumb(): String {
        return replace(Regex("""=w\d+-h\d+[^=]*$"""), "=w544-h544-l90-rj")
    }

    private fun JsonElement.objOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.arrOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement.strOrNull(): String? = jsonPrimitive.contentOrNull
    private fun JsonElement.runsText(): String {
        val runs = objOrNull()?.get("runs")?.arrOrNull() ?: return ""
        return runs.joinToString("") { it.objOrNull()?.get("text")?.strOrNull().orEmpty() }
    }
}
