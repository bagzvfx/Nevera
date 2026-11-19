package com.nevera.corporation.data.store

import android.content.Context
import com.nevera.corporation.data.model.NetworkServerConfig
import com.nevera.corporation.data.model.NetworkServerType
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceStream
import com.nevera.corporation.data.sources.NetworkStorageClient
import com.nevera.corporation.data.sources.ServerBrowseEntry
import com.nevera.corporation.data.sources.TrackMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists user-configured personal lossless servers (Subsonic/OpenSubsonic & WebDAV/HTTP)
 * in app-private storage and provides seamless streaming & lossless track matching.
 */
object NetworkStorageRepository {

    private lateinit var storeFile: File
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _servers = MutableStateFlow<List<NetworkServerConfig>>(emptyList())
    val servers: StateFlow<List<NetworkServerConfig>> = _servers.asStateFlow()

    fun init(context: Context) {
        storeFile = File(context.applicationContext.filesDir, "nevera_network_servers.json")
        if (storeFile.exists()) {
            try {
                _servers.value = json.decodeFromString<List<NetworkServerConfig>>(storeFile.readText())
            } catch (_: Exception) {
                _servers.value = emptyList()
            }
        }
    }

    @Synchronized
    private fun persist() {
        if (!::storeFile.isInitialized) return
        try {
            storeFile.writeText(json.encodeToString(_servers.value))
        } catch (_: Exception) {
        }
    }

    fun addOrUpdateServer(config: NetworkServerConfig) {
        val clean = config.copy(
            name = config.name.trim().ifBlank { "My Lossless Server" },
            baseUrl = config.baseUrl.trim()
        )
        val existingIdx = _servers.value.indexOfFirst { it.id == clean.id }
        _servers.value = if (existingIdx >= 0) {
            _servers.value.toMutableList().also { it[existingIdx] = clean }
        } else {
            _servers.value + clean
        }
        persist()
    }

    fun removeServer(serverId: String) {
        _servers.value = _servers.value.filterNot { it.id == serverId }
        persist()
    }

    fun getServer(serverId: String): NetworkServerConfig? = _servers.value.firstOrNull { it.id == serverId }

    suspend fun testConnection(config: NetworkServerConfig): Pair<Boolean, String> =
        NetworkStorageClient.testConnection(config)

    suspend fun browse(config: NetworkServerConfig, subPath: String = ""): List<ServerBrowseEntry> =
        NetworkStorageClient.browseServer(config, subPath)

    fun resolveDirectServerSong(song: Song): SourceStream? {
        val cfg = getServer(song.serverId) ?: return null
        return NetworkStorageClient.buildStreamForSong(song, cfg)
    }

    /**
     * When user has connected a Subsonic / OpenSubsonic personal lossless server,
     * transparently checks if the exact track exists on their server to stream raw FLAC/Hi-Res!
     */
    suspend fun tryMatchLosslessUpgrade(song: Song): SourceStream? {
        val activeSubsonic = _servers.value.filter { it.enabled && it.type == NetworkServerType.SUBSONIC }
        if (activeSubsonic.isEmpty()) return null

        val cleanQuery = "${TrackMatcher.normalizeTitle(song.title)} ${TrackMatcher.normalizeArtist(song.artist)}".trim()
        if (cleanQuery.isBlank()) return null

        for (server in activeSubsonic) {
            val candidates = NetworkStorageClient.searchSubsonic(server, cleanQuery, limit = 10)
            if (candidates.isEmpty()) continue
            val best = TrackMatcher.pickBestMatch(
                targetTitle = song.title,
                targetArtist = song.artist,
                targetDurationSec = song.durationSeconds,
                candidates = candidates,
                minScore = 0.68
            )
            if (best != null) {
                return NetworkStorageClient.buildStreamForSong(best, server)
            }
        }
        return null
    }
}
