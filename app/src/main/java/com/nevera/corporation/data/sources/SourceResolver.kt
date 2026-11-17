package com.nevera.corporation.data.sources

import com.nevera.corporation.data.model.AppSettingsState
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import com.nevera.corporation.data.model.SourceStream
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.download.OfflineDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Central priority router & verified fallback audio engine.
 * Never exposes internal provider names to UI.
 */
object SourceResolver {

    private val songRegistry = ConcurrentHashMap<String, Song>()

    private val _lastResolvedStreamInfo = MutableStateFlow("Siap")
    val lastResolvedStreamInfo: StateFlow<String> = _lastResolvedStreamInfo.asStateFlow()

    @Volatile
    var currentSettings: AppSettingsState = AppSettingsState()

    fun registerSongs(songs: List<Song>) {
        songs.forEach { songRegistry[it.id] = it }
    }

    fun registerSong(song: Song) {
        songRegistry[song.id] = song
    }

    fun getRegistered(id: String): Song? = songRegistry[id]

    suspend fun resolveForPlayback(song: Song): SourceStream? {
        registerSong(song)

        // 1. Local device audio
        if (song.localUri.isNotBlank()) {
            _lastResolvedStreamInfo.value = "Audio Perangkat"
            return SourceStream(
                url = song.localUri,
                codec = "Local",
                kbps = 320,
                qualityLabel = "Audio Perangkat"
            )
        }

        // 2. Offline downloaded file
        OfflineDownloadManager.getDownloadedFileUri(song.id)?.let { offlineUri ->
            _lastResolvedStreamInfo.value = "Tersimpan Offline"
            return SourceStream(
                url = offlineUri,
                codec = "Offline",
                kbps = 320,
                qualityLabel = "Tersimpan Offline"
            )
        }

        val maxKbps = LibraryStore.effectiveAudioQuality().maxKbps
        val preferHighRes = currentSettings.preferHighResAudio && maxKbps >= 160

        // 3. Direct JioSaavn song
        if (song.source == SourceKind.JIOSAAVN && song.sourceTrackId.isNotBlank()) {
            val stream = JioSaavnService.getStreamById(song.sourceTrackId, maxKbps)
                ?: song.directStreamUrl.takeIf { it.isNotBlank() }?.let {
                    SourceStream(it, "AAC", maxKbps, "HQ Audio • $maxKbps kbps AAC")
                }
            if (stream != null && StreamProbe.isPlayable(stream.url)) {
                _lastResolvedStreamInfo.value = stream.qualityLabel
                return stream
            }
        }

        // 4. High-bitrate upgrade via TrackMatcher when preferred
        if (preferHighRes && song.source != SourceKind.LOCAL) {
            val matched = tryMatchHighRes(song, maxKbps)
            if (matched != null && StreamProbe.isPlayable(matched.url)) {
                _lastResolvedStreamInfo.value = matched.qualityLabel
                return matched
            }
        }

        // 5. YouTube extraction via InnerTubeX + NewPipe failsafe
        val ytVideoId = if (song.source == SourceKind.YOUTUBE && song.sourceTrackId.isNotBlank()) {
            song.sourceTrackId
        } else {
            val query = "${song.title} ${song.artist}".trim()
            val candidates = InnertubeClient.searchSongs(query, songsOnly = true)
            TrackMatcher.pickBestMatch(song.title, song.artist, song.durationSeconds, candidates, minScore = 0.42)
                ?.sourceTrackId
                ?: candidates.firstOrNull()?.sourceTrackId
        }

        if (!ytVideoId.isNullOrBlank()) {
            val ytStream = YouTubeStreamResolver.resolve(ytVideoId, maxKbps)
            if (ytStream != null) {
                _lastResolvedStreamInfo.value = ytStream.qualityLabel
                return ytStream
            }
        }

        // 6. Final high-res fallback
        if (!preferHighRes) {
            val fallback = tryMatchHighRes(song, maxKbps)
            if (fallback != null && StreamProbe.isPlayable(fallback.url)) {
                _lastResolvedStreamInfo.value = fallback.qualityLabel
                return fallback
            }
        }

        return null
    }

    private suspend fun tryMatchHighRes(song: Song, maxKbps: Int): SourceStream? {
        val query = "${TrackMatcher.normalizeTitle(song.title)} ${TrackMatcher.normalizeArtist(song.artist)}".trim()
        if (query.isBlank()) return null
        val candidates = JioSaavnService.search(query, limit = 8)
        val best = TrackMatcher.pickBestMatch(song.title, song.artist, song.durationSeconds, candidates)
            ?: return null
        return best.directStreamUrl.takeIf { it.isNotBlank() }?.let { url ->
            val adjusted = JioSaavnService.selectBestStream(url, supports320 = true, maxKbps = maxKbps)
            val kbps = adjusted?.kbps ?: maxKbps
            SourceStream(
                url = adjusted?.url ?: url,
                codec = "AAC",
                kbps = kbps,
                qualityLabel = "HQ Audio • $kbps kbps AAC"
            )
        }
    }
}
