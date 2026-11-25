package com.music.bitchord.data.playlist

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyImporter

/**
 * Turns a pasted playlist link into songs, for the welcome pages' import.
 *
 * One entry point behind one interface, as the two sources allow:
 *
 * - **Spotify** — end to end. [SpotifyImporter] reads the public playlist
 *   off Spotify's embed page and matches every track to a YouTube Music
 *   song, the same machinery as the Library's Spotify import.
 * - **YouTube / YouTube Music** — end to end. The `list=` id becomes the
 *   `VL…` browse id and [YtMusicRepository.browseSongs] walks it, following
 *   continuations so a long playlist arrives whole (capped at
 *   [MAX_PAGES] pages so a pathological link can't page forever).
 *
 * Anything else is refused with a message the caller can show verbatim.
 */
object PlaylistUrlImporter {

    data class Imported(val title: String, val songs: List<Song>)

    private const val MAX_PAGES = 10

    private val YOUTUBE_LIST_ID = Regex("""[?&]list=([A-Za-z0-9_-]+)""")

    suspend fun import(url: String): Result<Imported> = runCatching {
        val link = url.trim()
        require(link.isNotEmpty()) { "Paste a playlist link first." }

        SpotifyImporter.extractPlaylistId(link)?.let { playlistId ->
            val (name, tracks) = SpotifyImporter.fetchPlaylistTracks(playlistId)
            require(tracks.isNotEmpty()) { "That Spotify playlist looks empty — is it public?" }
            val (songs, _) = SpotifyImporter.resolveToSongs(tracks, language = null) { _, _ -> }
            require(songs.isNotEmpty()) { "None of that playlist's songs could be matched." }
            return@runCatching Imported(title = name, songs = songs)
        }

        val listId = extractYouTubeListId(link)
            ?: error("That doesn't look like a Spotify or YouTube playlist link.")
        var page = YtMusicRepository.browseSongs("VL$listId").getOrThrow()
        val songs = page.songs.toMutableList()
        val title = page.header?.title?.ifBlank { null } ?: "Imported YouTube Playlist"
        var pages = 1
        while (page.continuation != null && pages < MAX_PAGES) {
            page = YtMusicRepository.moreSongs(page.continuation!!).getOrThrow()
            songs += page.songs
            pages++
        }
        require(songs.isNotEmpty()) { "That YouTube playlist looks empty — is it public?" }
        Imported(title = title, songs = songs)
    }

    /**
     * The playlist id out of a youtube.com / music.youtube.com link, or a
     * bare `PL…`/`VL…` id pasted on its own. Null for anything else.
     */
    fun extractYouTubeListId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.startsWith("VL")) return trimmed.removePrefix("VL")
        if (trimmed.startsWith("PL") && trimmed.none { it == '/' || it == '?' || it == '.' }) {
            return trimmed
        }
        val host = runCatching { java.net.URI(trimmed).host?.lowercase().orEmpty() }.getOrDefault("")
        if (!host.endsWith("youtube.com")) return null
        return YOUTUBE_LIST_ID.find(trimmed)?.groupValues?.get(1)?.removePrefix("VL")
    }
}
