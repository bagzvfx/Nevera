package com.nevera.corporation.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import com.nevera.corporation.data.sources.InnertubeClient
import com.nevera.corporation.data.sources.RecommendationEngine
import com.nevera.corporation.data.sources.SourceResolver
import com.nevera.corporation.data.sources.YouTubeStreamResolver
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.ui.player.PlaybackPlayhead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class QueuePlayMode {
    SMART_RADIO,
    ORDERED_COLLECTION
}

class PlayerConnection(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingAction: ((MediaController) -> Unit)? = null
    private val audioFxBridge = NativeAudioFxBridge()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs.asStateFlow()

    private val _selectedEqPreset = MutableStateFlow(0)
    val selectedEqPreset: StateFlow<Int> = _selectedEqPreset.asStateFlow()

    private val _eqPresets = MutableStateFlow<List<String>>(
        listOf("Normal", "Pop", "Rock", "Bass Boost", "Jazz", "Classic", "Vocal Boost")
    )
    val eqPresets: StateFlow<List<String>> = _eqPresets.asStateFlow()

    private var discontinuityCounter = 0L
    private val _playhead = MutableStateFlow(PlaybackPlayhead())
    val playhead: StateFlow<PlaybackPlayhead> = _playhead.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private var positionTickerJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var queueFetchJob: Job? = null

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future

        future.addListener({
            try {
                val ctrl = future.get()
                controller = ctrl
                attachListeners(ctrl)
                pendingAction?.invoke(ctrl)
                pendingAction = null
            } catch (e: Exception) {
                _errorBanner.value = "Pemutar audio belum siap: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun attachListeners(ctrl: MediaController) {
        _isPlaying.value = ctrl.isPlaying
        _repeatMode.value = ctrl.repeatMode
        _shuffleEnabled.value = ctrl.shuffleModeEnabled
        audioFxBridge.attachSession(0)
        audioFxBridge.applyLoudness(LibraryStore.settings.value.loudnessNormalization)

        ctrl.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                val pos = ctrl.currentPosition.coerceAtLeast(0L)
                _playhead.value = PlaybackPlayhead(
                    positionMs = pos,
                    sampledAtMs = System.nanoTime() / 1_000_000.0,
                    isPlaying = playing,
                    discontinuityToken = discontinuityCounter
                )
                if (playing) {
                    ctrl.volume = 1f
                    startPositionTicker()
                } else {
                    stopPositionTicker()
                    SpectrumAudioBus.setIdle()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                _isBuffering.value = (state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    _durationMs.value = ctrl.duration.coerceAtLeast(0L)
                    ctrl.volume = 1f
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    _currentSong.value?.let { LibraryStore.recordCompletionBoost(it) }
                }
                val idx = ctrl.currentMediaItemIndex
                _currentIndex.value = idx
                val song = _queue.value.getOrNull(idx)
                discontinuityCounter++
                _playhead.value = PlaybackPlayhead(
                    positionMs = 0L,
                    sampledAtMs = System.nanoTime() / 1_000_000.0,
                    isPlaying = ctrl.isPlaying,
                    discontinuityToken = discontinuityCounter
                )
                if (song != null) {
                    _currentSong.value = song
                    LibraryStore.recordPlayed(song)
                    _durationMs.value = ctrl.duration.takeIf { it > 0 } ?: song.durationMs
                    maybeFetchUpNext(song, idx)
                    applyCrossfadeIn(ctrl)
                }
            }

            override fun onRepeatModeChanged(mode: Int) {
                _repeatMode.value = mode
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                _shuffleEnabled.value = enabled
            }

            override fun onPlayerError(error: PlaybackException) {
                _isBuffering.value = false
                _isPlaying.value = false
                _currentSong.value?.let { YouTubeStreamResolver.invalidate(it.sourceTrackId) }
                _errorBanner.value = "Audio tidak dapat dimuat (${error.errorCodeName})"
                if (ctrl.hasNextMediaItem()) {
                    ctrl.seekToNextMediaItem()
                    ctrl.prepare()
                    ctrl.play()
                }
            }
        })

        if (ctrl.isPlaying) startPositionTicker()
    }

    private fun applyCrossfadeIn(ctrl: MediaController) {
        val fadeSec = LibraryStore.settings.value.crossfadeSeconds
        if (fadeSec <= 0) {
            ctrl.volume = 1f
            return
        }
        scope.launch {
            val steps = 12
            val stepDelay = (fadeSec.coerceAtMost(4) * 250L) / steps
            for (i in 1..steps) {
                ctrl.volume = i.toFloat() / steps.toFloat()
                delay(stepDelay)
            }
            ctrl.volume = 1f
        }
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = scope.launch {
            while (true) {
                controller?.let { ctrl ->
                    val pos = ctrl.currentPosition.coerceAtLeast(0L)
                    val dur = ctrl.duration.coerceAtLeast(0L)
                    _positionMs.value = pos
                    _playhead.value = PlaybackPlayhead(
                        positionMs = pos,
                        sampledAtMs = System.nanoTime() / 1_000_000.0,
                        isPlaying = ctrl.isPlaying,
                        discontinuityToken = discontinuityCounter
                    )
                    if (dur > 0) {
                        _durationMs.value = dur
                        val fadeMs = LibraryStore.settings.value.crossfadeSeconds * 1000L
                        if (fadeMs > 0 && ctrl.hasNextMediaItem() && dur > fadeMs + 2000L) {
                            val remaining = dur - pos
                            if (remaining in 1..fadeMs) {
                                ctrl.volume = (remaining.toFloat() / fadeMs.toFloat()).coerceIn(0.08f, 1f)
                            }
                        }
                    }
                }
                delay(150)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    fun clearError() {
        _errorBanner.value = null
    }

    fun selectEqualizerPreset(index: Int) {
        _selectedEqPreset.value = index
        audioFxBridge.applyEqualizerPreset(index)
    }

    private fun Song.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setSubtitle(artist)
            .setArtist(artist)
            .setAlbumArtist(artist)
            .setAlbumTitle(album.ifBlank { "Nevera Music" })
            .setDurationMs(durationMs.takeIf { it > 0L })
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(thumbnailUrl.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(playbackUri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun runOrQueue(action: (MediaController) -> Unit) {
        val ctrl = controller
        if (ctrl != null) action(ctrl) else pendingAction = action
    }

    fun playSong(
        song: Song,
        contextQueue: List<Song> = listOf(song),
        playMode: QueuePlayMode = if (contextQueue.size <= 1) QueuePlayMode.SMART_RADIO else QueuePlayMode.ORDERED_COLLECTION
    ) {
        if (song.isCollection) return
        queueFetchJob?.cancel()
        SpectrumAudioBus.flushTimeline()

        val initialQueue = if (playMode == QueuePlayMode.SMART_RADIO) {
            listOf(song)
        } else {
            val playableQueue = contextQueue.filterNot { it.isCollection }
            if (playableQueue.any { it.id == song.id }) playableQueue else listOf(song) + playableQueue
        }
        SourceResolver.registerSongs(initialQueue)

        val startIndex = initialQueue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        _queue.value = initialQueue
        _currentIndex.value = startIndex
        _currentSong.value = song
        _isBuffering.value = true
        _errorBanner.value = null
        LibraryStore.recordPlayed(song)

        val items = initialQueue.map { it.toMediaItem() }
        runOrQueue { ctrl ->
            ctrl.volume = 1f
            ctrl.setMediaItems(items, startIndex, 0L)
            ctrl.prepare()
            ctrl.play()
        }

        if (playMode == QueuePlayMode.SMART_RADIO) {
            fetchSmartQueueTail(seedSong = song, expectedSeedId = song.id)
        } else {
            maybeFetchUpNext(song, startIndex)
        }
    }

    private fun fetchSmartQueueTail(seedSong: Song, expectedSeedId: String) {
        if (seedSong.source == SourceKind.LOCAL) return
        queueFetchJob = scope.launch(Dispatchers.IO) {
            val existingIds = _queue.value.map { it.id }.toSet()
            val recommended = RecommendationEngine.buildSmartUpNextQueue(
                seedSong = seedSong,
                recentlyPlayed = LibraryStore.recentlyPlayed.value,
                likedSongs = LibraryStore.likedSongs.value,
                artistPlayCounts = LibraryStore.artistPlayCounts.value,
                existingQueueIds = existingIds,
                limit = 18
            )
            if (recommended.isEmpty()) return@launch
            SourceResolver.registerSongs(recommended)
            scope.launch(Dispatchers.Main) {
                val activeSongId = _currentSong.value?.id
                if (activeSongId != expectedSeedId && _queue.value.none { it.id == expectedSeedId }) {
                    return@launch
                }
                val currentQueueIds = _queue.value.map { it.id }.toSet()
                val deduped = recommended.filterNot { it.id in currentQueueIds }
                if (deduped.isNotEmpty()) {
                    _queue.value = _queue.value + deduped
                    controller?.addMediaItems(deduped.map { it.toMediaItem() })
                }
            }
        }
    }

    private fun maybeFetchUpNext(song: Song, index: Int) {
        if (song.source == SourceKind.LOCAL) return
        if (index >= _queue.value.size - 4 && song.source != SourceKind.LOCAL) {
            val anchor = _queue.value.lastOrNull { it.sourceTrackId.isNotBlank() } ?: song
            fetchSmartQueueTail(seedSong = anchor, expectedSeedId = anchor.id)
        }
    }

    fun playNextInQueue(song: Song) {
        SourceResolver.registerSong(song)
        val currentIdx = _currentIndex.value.coerceAtLeast(-1)
        val insertIdx = (currentIdx + 1).coerceAtMost(_queue.value.size)
        val mutable = _queue.value.toMutableList()
        mutable.add(insertIdx, song)
        _queue.value = mutable
        controller?.addMediaItem(insertIdx, song.toMediaItem())
    }

    fun addToQueue(song: Song) {
        SourceResolver.registerSong(song)
        if (_queue.value.isEmpty()) {
            playSong(song)
            return
        }
        _queue.value = _queue.value + song
        controller?.addMediaItem(song.toMediaItem())
    }

    fun jumpToQueueIndex(index: Int) {
        val song = _queue.value.getOrNull(index) ?: return
        _currentIndex.value = index
        _currentSong.value = song
        controller?.let { ctrl ->
            ctrl.seekToDefaultPosition(index)
            ctrl.play()
        }
    }

    fun removeFromQueue(index: Int) {
        if (index !in _queue.value.indices || index == _currentIndex.value) return
        val mutable = _queue.value.toMutableList()
        mutable.removeAt(index)
        _queue.value = mutable
        if (index < _currentIndex.value) {
            _currentIndex.value -= 1
        }
        controller?.removeMediaItem(index)
    }

    fun togglePlayPause() {
        controller?.let { ctrl ->
            if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
        }
    }

    fun skipNext() {
        controller?.let { ctrl ->
            if (ctrl.hasNextMediaItem()) {
                ctrl.seekToNextMediaItem()
                ctrl.play()
            } else if (_queue.value.isNotEmpty()) {
                ctrl.seekToDefaultPosition(0)
                ctrl.play()
            }
        }
    }

    fun skipPrevious() {
        controller?.let { ctrl ->
            if (ctrl.currentPosition > 3500L) {
                ctrl.seekTo(0L)
            } else if (ctrl.hasPreviousMediaItem()) {
                ctrl.seekToPreviousMediaItem()
                ctrl.play()
            } else {
                ctrl.seekTo(0L)
            }
        }
    }

    fun seekTo(ms: Long) {
        val clamped = ms.coerceAtLeast(0L)
        SpectrumAudioBus.flushTimeline()
        _positionMs.value = clamped
        discontinuityCounter++
        _playhead.value = PlaybackPlayhead(
            positionMs = clamped,
            sampledAtMs = System.nanoTime() / 1_000_000.0,
            isPlaying = _isPlaying.value,
            discontinuityToken = discontinuityCounter
        )
        controller?.seekTo(clamped)
    }

    fun cycleRepeatMode() {
        val next = when (_repeatMode.value) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        _repeatMode.value = next
        controller?.repeatMode = next
    }

    fun toggleShuffle() {
        val next = !_shuffleEnabled.value
        _shuffleEnabled.value = next
        controller?.shuffleModeEnabled = next
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _playbackSpeed.value = clamped
        controller?.setPlaybackSpeed(clamped)
    }

    fun startSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _sleepTimerRemainingMs.value = null
            return
        }
        var remaining = minutes * 60_000L
        _sleepTimerRemainingMs.value = remaining
        sleepTimerJob = scope.launch {
            while (remaining > 0) {
                delay(1000L)
                remaining -= 1000L
                _sleepTimerRemainingMs.value = remaining
            }
            _sleepTimerRemainingMs.value = null
            controller?.pause()
        }
    }

    fun release() {
        positionTickerJob?.cancel()
        sleepTimerJob?.cancel()
        audioFxBridge.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }
}
