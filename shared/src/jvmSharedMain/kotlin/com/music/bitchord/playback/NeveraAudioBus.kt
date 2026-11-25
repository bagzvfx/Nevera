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
    val nextLineTimeMs: Long? = null,
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

    /**
     * Output-latency compensation, in milliseconds, for the visual glow/waveform.
     *
     * Set from the active audio route (see AudioOutputStatus in :app): phone
     * speaker, wired, USB, HDMI and Bluetooth all deliver sound with different
     * delays, so no single fixed value can line the glow up on every route. The
     * mastering DSP delays publication of the analyzed bands by this much so
     * pulses land when the sound is actually heard.
     *
     * This is only the *static* estimate. The mastering processor resolves the
     * value it actually uses per block through [GlowLatencyPolicy], preferring
     * the measured values below whenever they exist — the route table alone
     * was the previous fix, and it was not enough, because the dominant term
     * is not the output route at all: it is how much audio the sink is
     * holding in flight ahead of the speaker (see [dspLeadMs]).
     */
    val glowLatencyCompensationMs = MutableStateFlow(65L)

    /**
     * Measured in-flight lead of the DSP chain over the audible position, in
     * milliseconds: (media the chain has processed) − (media the listener has
     * heard), i.e. exactly the delay between the PCM the analyzer sees and
     * the sound leaving the device. Published by the sink layer, which
     * already computes this figure for its transition filters
     * (`PrecisionAudioSink.getCurrentPositionUs` → `leadUs`). Null until the
     * sink reports it; [GlowLatencyPolicy] then falls back gracefully.
     */
    val dspLeadMs = MutableStateFlow<Long?>(null)

    /**
     * Duration of the active AudioTrack's own buffer, in milliseconds,
     * derived by AudioOutputStatus from the track's reported buffer size,
     * sample rate and encoding. This is the part of the sink lead that can
     * be known without the sink's position clock: Media3 sizes this buffer
     * generously (on float routes, where the AudioTrack applies playback
     * speed itself, it is sized for 8x speed and can hold seconds of audio),
     * and the renderer keeps it close to full, so its duration tracks the
     * real lead far better than any per-route constant. Null until the
     * first AudioTrack is initialized.
     */
    val trackBufferMs = MutableStateFlow<Long?>(null)

    /**
     * The post-sink tail of the active route, in milliseconds: HAL/DAC,
     * HDMI, or Bluetooth codec + air time that happens *after* audio leaves
     * the AudioTrack buffer. Written by AudioOutputStatus alongside
     * [glowLatencyCompensationMs]; [GlowLatencyPolicy] adds it on top of a
     * measured sink lead instead of conflating the two.
     */
    val glowRouteTailMs = MutableStateFlow(20L)

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

/**
 * Pure resolution of the glow/waveform latency compensation, in milliseconds.
 *
 * The delay that has to be compensated is the time between the PCM the
 * analyzer sees and the same samples leaving the device. That has two parts:
 *
 *  1. the **sink lead** — audio the DSP chain has already processed that is
 *     still queued inside the sink / AudioTrack, inaudible for now. This is
 *     by far the larger and more variable term (hundreds of ms on a normal
 *     route; seconds on a float route, whose AudioTrack buffer is sized for
 *     8x playback speed), and it is why a per-route constant table could
 *     never line the glow up: it modelled only part 2.
 *  2. the **route tail** — HAL/DAC/Bluetooth codec + air time after the
 *     AudioTrack buffer.
 *
 * Preference order: the sink's own measured lead ([measuredLeadMs]) when it
 * is being published, then the AudioTrack buffer duration
 * ([trackBufferMs]) as a measured stand-in for the sink lead, then the
 * legacy per-route total ([legacyTotalMs]) as the last resort.
 */
object GlowLatencyPolicy {

    /** Hard ceiling: float-route sink leads can legitimately reach seconds. */
    const val MAX_COMPENSATION_MS = 4_000L

    fun resolveCompensationMs(
        measuredLeadMs: Long?,
        trackBufferMs: Long?,
        routeTailMs: Long,
        legacyTotalMs: Long,
    ): Long {
        val resolved = when {
            measuredLeadMs != null -> measuredLeadMs + routeTailMs
            trackBufferMs != null -> trackBufferMs + routeTailMs
            else -> legacyTotalMs
        }
        return resolved.coerceIn(0L, MAX_COMPENSATION_MS)
    }

    /**
     * Duration, in milliseconds, of an AudioTrack buffer of [bufferSizeBytes]
     * at [sampleRateHz] with [bytesPerSample] per sample and [channelCount]
     * channels. Null when any input is unusable (unknown size/rate/format).
     */
    fun trackBufferDurationMs(
        bufferSizeBytes: Int,
        sampleRateHz: Int,
        bytesPerSample: Int,
        channelCount: Int,
    ): Long? {
        if (bufferSizeBytes <= 0 || sampleRateHz <= 0 || bytesPerSample <= 0 || channelCount <= 0) {
            return null
        }
        val bytesPerFrame = bytesPerSample.toLong() * channelCount.toLong()
        val frames = bufferSizeBytes.toLong() / bytesPerFrame
        if (frames <= 0L) return null
        return frames * 1_000L / sampleRateHz.toLong()
    }

    /** Bytes per sample for the PCM encodings the player publishes. */
    fun bytesPerSampleForEncoding(encoding: Int): Int = when (encoding) {
        // android.media.AudioFormat values, duplicated as constants so this
        // policy stays platform-free and JVM-testable in :shared.
        ENCODING_PCM_16BIT -> 2
        ENCODING_PCM_8BIT -> 1
        ENCODING_PCM_24BIT_PACKED -> 3
        ENCODING_PCM_32BIT -> 4
        ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_8BIT = 3
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22
    private const val ENCODING_PCM_FLOAT = 4
}
