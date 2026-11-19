package com.nevera.corporation.data.sources

import com.nevera.corporation.data.model.AppSettingsState
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import com.nevera.corporation.data.model.SourceStream
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.data.store.NetworkStorageRepository
import com.nevera.corporation.download.OfflineDownloadManager
import com.nevera.corporation.playback.AudioSignalPathBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Central priority router & verified fallback audio engine.
 * Prioritizes:
 * 1. Local Hi-Res Device Audio
 * 2. Offline Vault
 * 3. Direct Personal Lossless Server track (Subsonic/OpenSubsonic/WebDAV)
 * 4. Optional transparent lossless upgrade from user's connected personal server
 * 5. Highest-bitrate Master Studio Stream & verified fallbacks
 * Never exposes internal catalog provider names to UI.
 */
object SourceResolver {

    private val songRegistry = ConcurrentHashMap<String, Song>()

    private val _lastResolvedStreamInfo = MutableStateFlow("Studio Audio")
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

    private fun publishResolved(stream: SourceStream, updateSignalBus: Boolean): SourceStream {
        if (updateSignalBus) {
            _lastResolvedStreamInfo.value = stream.qualityLabel
            AudioSignalPathBus.onSourceResolved(stream)
        }
        return stream
    }

    suspend fun resolveForPlayback(song: Song, updateSignalBus: Boolean = true): SourceStream? {
        registerSong(song)

        // 1. Local device audio (FLAC / WAV / ALAC / Hi-Res or MP3/AAC)
        if (song.localUri.isNotBlank()) {
            val codec = song.codecHint.ifBlank { "Local" }
            val isLossless = song.isLosslessHint
            val stream = SourceStream(
                url = song.localUri,
                codec = codec,
                kbps = song.bitrateKbps.takeIf { it > 0 } ?: if (isLossless) 1000 else 320,
                qualityLabel = if (isLossless) "Local Lossless · $codec" else "Local Audio · $codec",
                sampleRateHz = song.sampleRateHz,
                bitDepth = song.bitDepth,
                isLossless = isLossless,
                originLabel = if (isLossless) "Local Hi-Res Vault" else "Local Device Storage"
            )
            return publishResolved(stream, updateSignalBus)
        }

        // 2. Offline downloaded file
        OfflineDownloadManager.getDownloadedFileUri(song.id)?.let { offlineUri ->
            val stream = SourceStream(
                url = offlineUri,
                codec = song.codecHint.ifBlank { "Offline" },
                kbps = song.bitrateKbps.takeIf { it > 0 } ?: 320,
                qualityLabel = "Offline Vault",
                sampleRateHz = song.sampleRateHz,
                bitDepth = song.bitDepth,
                isLossless = song.isLosslessHint,
                originLabel = "Offline Vault"
            )
            return publishResolved(stream, updateSignalBus)
        }

        // 3. Direct Personal Lossless Network Server track (Subsonic / WebDAV)
        if (song.source == SourceKind.NETWORK_SERVER) {
            NetworkStorageRepository.resolveDirectServerSong(song)?.let { srvStream ->
                return publishResolved(srvStream, updateSignalBus)
            }
        }

        val maxKbps = LibraryStore.effectiveAudioQuality().maxKbps
        val preferHighRes = currentSettings.preferHighResAudio && maxKbps >= 160

        // 4. Transparent upgrade to user's own connected Lossless Subsonic/OpenSubsonic server
        if (currentSettings.preferPersonalLosslessServer && maxKbps >= 160) {
            NetworkStorageRepository.tryMatchLosslessUpgrade(song)?.let { losslessStream ->
                if (StreamProbe.isPlayable(losslessStream.url, losslessStream.headers)) {
                    return publishResolved(losslessStream, updateSignalBus)
                }
            }
        }

        // 5. Direct high-bitrate track
        if (song.source == SourceKind.JIOSAAVN && song.sourceTrackId.isNotBlank()) {
            val stream = JioSaavnService.getStreamById(song.sourceTrackId, maxKbps)
                ?: song.directStreamUrl.takeIf { it.isNotBlank() }?.let {
                    SourceStream(
                        url = it,
                        codec = "AAC",
                        kbps = maxKbps,
                        qualityLabel = "Studio AAC • $maxKbps kbps",
                        sampleRateHz = 44100,
                        originLabel = "Master Studio Stream"
                    )
                }
            if (stream != null && StreamProbe.isPlayable(stream.url)) {
                val enriched = stream.copy(
                    sampleRateHz = stream.sampleRateHz.takeIf { it > 0 } ?: 44100,
                    originLabel = "Master Studio Stream"
                )
                return publishResolved(enriched, updateSignalBus)
            }
        }

        // 6. High-bitrate master match when preferred
        if (preferHighRes && song.source != SourceKind.LOCAL) {
            val matched = tryMatchHighRes(song, maxKbps)
            if (matched != null && StreamProbe.isPlayable(matched.url)) {
                return publishResolved(matched, updateSignalBus)
            }
        }

        // 7. Primary Studio Opus / AAC stream extraction + failsafe
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
                return publishResolved(ytStream, updateSignalBus)
            }
        }

        // 8. Final verified high-bitrate fallback
        if (!preferHighRes) {
            val fallback = tryMatchHighRes(song, maxKbps)
            if (fallback != null && StreamProbe.isPlayable(fallback.url)) {
                return publishResolved(fallback, updateSignalBus)
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
                qualityLabel = "Studio AAC • $kbps kbps",
                sampleRateHz = 44100,
                isLossless = false,
                originLabel = "Master Studio Stream"
            )
        }
    }
}
