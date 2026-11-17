package com.nevera.corporation.data.sources

import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Resolves public Spotify track, playlist, and album URLs into playable Song items
 * by extracting metadata and matching to JioSaavn / YouTube Music audio streams.
 */
object SpotifyResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isSpotifyUrl(input: String): Boolean {
        return input.contains("open.spotify.com/", ignoreCase = true) ||
            input.startsWith("spotify:", ignoreCase = true)
    }

    suspend fun resolveUrl(rawUrl: String): List<Song> = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        val match = Regex("""open\.spotify\.com/(?:intl-[^/]+/)?(track|playlist|album)/([a-zA-Z0-9]+)""")
            .find(url) ?: return@withContext emptyList()

        val type = match.groupValues[1]
        val id = match.groupValues[2]

        // 1. Try Spotify embed page JSON (__NEXT_DATA__)
        try {
            val embedUrl = "https://open.spotify.com/embed/$type/$id"
            val req = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    val nextDataMatch = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""")
                        .find(html)
                    if (nextDataMatch != null) {
                        val root = json.parseToJsonElement(nextDataMatch.groupValues[1]).jsonObject
                        val entity = root["props"]?.jsonObject
                            ?.get("pageProps")?.jsonObject
                            ?.get("state")?.jsonObject
                            ?.get("data")?.jsonObject
                            ?.get("entity")?.jsonObject

                        if (entity != null) {
                            if (type == "track") {
                                val title = entity["name"]?.jsonPrimitive?.content ?: entity["title"]?.jsonPrimitive?.content.orEmpty()
                                val artists = entity["artists"]?.jsonArray?.joinToString(", ") {
                                    it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                                } ?: entity["subtitle"]?.jsonPrimitive?.content.orEmpty()
                                val durMs = entity["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                val cover = entity["visualIdentity"]?.jsonObject
                                    ?.get("image")?.jsonArray?.lastOrNull()?.jsonObject
                                    ?.get("url")?.jsonPrimitive?.content
                                    ?: entity["coverArt"]?.jsonObject?.get("sources")?.jsonArray?.lastOrNull()?.jsonObject
                                        ?.get("url")?.jsonPrimitive?.content.orEmpty()

                                if (title.isNotBlank()) {
                                    return@withContext listOf(
                                        Song(
                                            id = "sp_$id",
                                            title = title,
                                            artist = artists.ifBlank { "Spotify Artist" },
                                            durationSeconds = durMs / 1000L,
                                            thumbnailUrl = cover,
                                            source = SourceKind.SPOTIFY,
                                            sourceTrackId = id
                                        )
                                    )
                                }
                            } else {
                                // Playlist or Album trackList
                                val cover = entity["coverArt"]?.jsonObject?.get("sources")?.jsonArray
                                    ?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content.orEmpty()
                                val trackList = entity["trackList"]?.jsonArray.orEmpty()
                                val tracks = trackList.mapIndexedNotNull { idx, item ->
                                    val obj = item.jsonObject
                                    val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
                                    val artist = obj["subtitle"]?.jsonPrimitive?.content.orEmpty()
                                    val durMs = obj["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                    val uri = obj["uri"]?.jsonPrimitive?.content.orEmpty()
                                    val tid = uri.substringAfterLast(":").ifBlank { "${id}_$idx" }
                                    if (title.isBlank()) null else Song(
                                        id = "sp_$tid",
                                        title = title,
                                        artist = artist.ifBlank { "Spotify Artist" },
                                        durationSeconds = durMs / 1000L,
                                        thumbnailUrl = cover,
                                        source = SourceKind.SPOTIFY,
                                        sourceTrackId = tid
                                    )
                                }
                                if (tracks.isNotEmpty()) return@withContext tracks
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Fallback to Spotify oEmbed for single track title
        try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val req = Request.Builder()
                .url("https://open.spotify.com/oembed?url=$encoded")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                    val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
                    val thumb = obj["thumbnail_url"]?.jsonPrimitive?.content.orEmpty()
                    if (title.isNotBlank()) {
                        return@withContext listOf(
                            Song(
                                id = "sp_$id",
                                title = title,
                                artist = "Spotify",
                                thumbnailUrl = thumb,
                                source = SourceKind.SPOTIFY,
                                sourceTrackId = id
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        emptyList()
    }
}
