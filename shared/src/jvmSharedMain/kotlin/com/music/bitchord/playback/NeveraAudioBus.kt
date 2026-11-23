package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.LyricLine
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Rich live lyric snapshot delivered to the borderless floating karaoke overlay
 * so it can render 60/120fps word-by-word sweep and per-character wave directly
 * synced to playback without per-frame IPC overhead.
 */
data class OverlayLyricSnapshot(
    val trackTitle: String = "",
    val line: LyricLine? = null,
    val primaryText: String = "",
    val secondaryText: String = "",
    val anchorPositionMs: Long = 0L,
    val anchorElapsedMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBestPart: Boolean = false,
)

/**
 * Shared real-time telemetry and Nevera feature control bus bridging the native
 * C++17 mastering/analyzer DSP, Compose player glow, Reff/Best Part climax,
 * auto-romaji, and borderless floating karaoke lyrics overlay across
 * :shared, :sharedUi, and :app.
 */
object NeveraAudioBus {
    /** Real-time beat-synced low-frequency pulse [0f..1f] for bass-reactive glow. */
    val bassEnergy = MutableStateFlow(0f)

    /** Real-time beat-synced high-frequency sparkle [0f..1f] for treble-reactive shimmer. */
    val trebleEnergy = MutableStateFlow(0f)

    /** Rolling sustained energy [0f..1f] indicating chorus / climax intensity. */
    val sustainEnergy = MutableStateFlow(0f)

    /** Sharp transient kick/snare beat impulse [0f..1f]. */
    val beatHit = MutableStateFlow(0f)

    /** True while the current track is inside its Reff / Best Part climax window. */
    val isBestPartActive = MutableStateFlow(false)

    /** Mastering volume boost in dB [0f..15f] applied with studio loudness maximizer + limiter. */
    val masteringBoostDb = MutableStateFlow(0f)

    /** Enables bass/treble audio-reactive glow on the Now Playing screen. */
    val audioReactiveGlow = MutableStateFlow(true)

    /** Automatically displays Romaji when Japanese script (Hiragana/Katakana) is detected. */
    val autoJapaneseRomaji = MutableStateFlow(true)

    /** Floating borderless karaoke lyrics overlay state above other apps. */
    val floatingLyricsEnabled = MutableStateFlow(false)

    /** Rich live lyric snapshot for the borderless karaoke overlay. */
    val overlaySnapshot = MutableStateFlow(OverlayLyricSnapshot())

    /** Live active lyric primary line for backwards compatibility. */
    val overlayLyricLine = MutableStateFlow("")

    /** Live active lyric secondary line (Romaji / translation). */
    val overlayLyricSub = MutableStateFlow("")

    /** Active track title. */
    val overlayTrackTitle = MutableStateFlow("")

    /** Live playback state for smooth decay when paused. */
    val isPlaying = MutableStateFlow(false)

    fun publishAudioBands(
        bass: Float,
        treble: Float,
        sustain: Float = 0f,
        beat: Float = 0f,
    ) {
        bassEnergy.value = bass.coerceIn(0f, 1f)
        trebleEnergy.value = treble.coerceIn(0f, 1f)
        sustainEnergy.value = sustain.coerceIn(0f, 1f)
        beatHit.value = beat.coerceIn(0f, 1f)
    }

    fun clearAudioBands() {
        bassEnergy.value = 0f
        trebleEnergy.value = 0f
        sustainEnergy.value = 0f
        beatHit.value = 0f
    }

    fun publishOverlaySnapshot(snapshot: OverlayLyricSnapshot) {
        overlaySnapshot.value = snapshot
        overlayTrackTitle.value = snapshot.trackTitle
        overlayLyricLine.value = snapshot.primaryText
        overlayLyricSub.value = snapshot.secondaryText
    }

    fun publishOverlayLyric(trackTitle: String, primary: String, secondary: String = "") {
        overlayTrackTitle.value = trackTitle
        overlayLyricLine.value = primary
        overlayLyricSub.value = secondary
        overlaySnapshot.value = overlaySnapshot.value.copy(
            trackTitle = trackTitle,
            line = null,
            primaryText = primary,
            secondaryText = secondary,
        )
    }
}
