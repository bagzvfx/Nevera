package com.nevera.corporation.data.store

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nevera.corporation.data.model.AppSettingsState
import com.nevera.corporation.data.model.AudioQuality
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.UserPlaylist
import com.nevera.corporation.data.sources.SourceResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PersistedLibraryData(
    val likedSongs: List<Song> = emptyList(),
    val recentlyPlayed: List<Song> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
    val settings: AppSettingsState = AppSettingsState(),
    val artistPlayCounts: Map<String, Int> = emptyMap(),
    val songPlayCounts: Map<String, Int> = emptyMap()
)

/**
 * Persistent store for Liked Songs, Listening Affinities, History, Playlists, and Settings.
 */
object LibraryStore {

    private lateinit var storeFile: File
    private lateinit var appContext: Context
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _likedSongs = MutableStateFlow<List<Song>>(emptyList())
    val likedSongs: StateFlow<List<Song>> = _likedSongs.asStateFlow()

    private val _recentlyPlayed = MutableStateFlow<List<Song>>(emptyList())
    val recentlyPlayed: StateFlow<List<Song>> = _recentlyPlayed.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    private val _settings = MutableStateFlow(AppSettingsState())
    val settings: StateFlow<AppSettingsState> = _settings.asStateFlow()

    private val _artistPlayCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val artistPlayCounts: StateFlow<Map<String, Int>> = _artistPlayCounts.asStateFlow()

    private val _songPlayCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val songPlayCounts: StateFlow<Map<String, Int>> = _songPlayCounts.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        storeFile = File(context.filesDir, "nevera_library.json")
        if (storeFile.exists()) {
            try {
                val parsed = json.decodeFromString<PersistedLibraryData>(storeFile.readText())
                _likedSongs.value = parsed.likedSongs
                _recentlyPlayed.value = parsed.recentlyPlayed
                _searchHistory.value = parsed.searchHistory
                _playlists.value = parsed.playlists
                _settings.value = parsed.settings
                _artistPlayCounts.value = parsed.artistPlayCounts
                _songPlayCounts.value = parsed.songPlayCounts
                SourceResolver.currentSettings = parsed.settings
                SourceResolver.registerSongs(parsed.likedSongs + parsed.recentlyPlayed)
            } catch (_: Exception) {
            }
        }
    }

    fun isOnWifi(): Boolean {
        if (!::appContext.isInitialized) return true
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            true
        }
    }

    fun effectiveAudioQuality(): AudioQuality {
        val s = _settings.value
        return if (isOnWifi()) s.wifiQuality else s.cellularQuality
    }

    @Synchronized
    private fun persist() {
        if (!::storeFile.isInitialized) return
        try {
            val payload = PersistedLibraryData(
                likedSongs = _likedSongs.value,
                recentlyPlayed = _recentlyPlayed.value,
                searchHistory = _searchHistory.value,
                playlists = _playlists.value,
                settings = _settings.value,
                artistPlayCounts = _artistPlayCounts.value,
                songPlayCounts = _songPlayCounts.value
            )
            storeFile.writeText(json.encodeToString(payload))
        } catch (_: Exception) {
        }
    }

    fun isLiked(songId: String): Boolean = _likedSongs.value.any { it.id == songId }

    fun toggleLike(song: Song): Boolean {
        val currentlyLiked = isLiked(song.id)
        _likedSongs.value = if (currentlyLiked) {
            _likedSongs.value.filterNot { it.id == song.id }
        } else {
            val updatedArtists = _artistPlayCounts.value.toMutableMap()
            val primaryArtist = song.artist.substringBefore(",").substringBefore("&").trim()
            if (primaryArtist.isNotBlank()) {
                updatedArtists[primaryArtist] = (updatedArtists[primaryArtist] ?: 0) + 2
                _artistPlayCounts.value = updatedArtists
            }
            listOf(song) + _likedSongs.value
        }
        persist()
        return !currentlyLiked
    }

    fun recordPlayed(song: Song) {
        if (song.isCollection) return
        val updated = listOf(song) + _recentlyPlayed.value.filterNot { it.id == song.id }
        _recentlyPlayed.value = updated.take(50)

        val primaryArtist = song.artist.substringBefore(",").substringBefore("&").trim()
        if (primaryArtist.isNotBlank() && !primaryArtist.equals("Artis", true)) {
            val aCounts = _artistPlayCounts.value.toMutableMap()
            aCounts[primaryArtist] = (aCounts[primaryArtist] ?: 0) + 1
            _artistPlayCounts.value = aCounts
        }

        val sCounts = _songPlayCounts.value.toMutableMap()
        sCounts[song.id] = (sCounts[song.id] ?: 0) + 1
        _songPlayCounts.value = sCounts

        persist()
    }

    fun recordCompletionBoost(song: Song) {
        if (song.isCollection) return
        val primaryArtist = song.artist.substringBefore(",").substringBefore("&").trim()
        if (primaryArtist.isNotBlank()) {
            val aCounts = _artistPlayCounts.value.toMutableMap()
            aCounts[primaryArtist] = (aCounts[primaryArtist] ?: 0) + 3
            _artistPlayCounts.value = aCounts
            persist()
        }
    }

    fun clearPlayHistory() {
        _recentlyPlayed.value = emptyList()
        _artistPlayCounts.value = emptyMap()
        _songPlayCounts.value = emptyMap()
        persist()
    }

    fun addSearchQuery(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        val updated = listOf(q) + _searchHistory.value.filterNot { it.equals(q, ignoreCase = true) }
        _searchHistory.value = updated.take(15)
        persist()
    }

    fun removeSearchQuery(query: String) {
        _searchHistory.value = _searchHistory.value.filterNot { it == query }
        persist()
    }

    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        persist()
    }

    fun createPlaylist(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val newList = UserPlaylist(id = "pl_${System.currentTimeMillis()}", name = clean)
        _playlists.value = _playlists.value + newList
        persist()
    }

    fun saveImportedPlaylist(name: String, songs: List<Song>, sourceUrl: String = "", fixedId: String? = null) {
        val clean = name.trim().ifBlank { "Imported Playlist" }
        val targetId = fixedId ?: "imp_${System.currentTimeMillis()}"
        SourceResolver.registerSongs(songs)
        val existingIdx = _playlists.value.indexOfFirst {
            (fixedId != null && it.id == fixedId) ||
                (sourceUrl.isNotBlank() && it.sourceUrl == sourceUrl)
        }
        val playlist = UserPlaylist(
            id = if (existingIdx >= 0) _playlists.value[existingIdx].id else targetId,
            name = clean,
            songs = songs.distinctBy { it.id },
            sourceUrl = sourceUrl
        )
        _playlists.value = if (existingIdx >= 0) {
            _playlists.value.toMutableList().also { it[existingIdx] = playlist }
        } else {
            listOf(playlist) + _playlists.value
        }
        persist()
    }

    fun deletePlaylist(playlistId: String) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        persist()
    }

    fun addSongToPlaylist(playlistId: String, song: Song) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == playlistId && pl.songs.none { it.id == song.id }) {
                pl.copy(songs = pl.songs + song)
            } else pl
        }
        persist()
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == playlistId) pl.copy(songs = pl.songs.filterNot { it.id == songId }) else pl
        }
        persist()
    }

    fun updateSettings(transform: (AppSettingsState) -> AppSettingsState) {
        val updated = transform(_settings.value)
        _settings.value = updated
        SourceResolver.currentSettings = updated
        persist()
    }
}
