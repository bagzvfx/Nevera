package com.music.bitchord.playback.audio

import com.music.bitchord.playback.NeveraAudioBus
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Native C++17 Studio Mastering Loudness Maximizer + soft-knee true-peak limiter
 * + beat-onset bass/treble rhythm analyzer riding at the tail of [DspChain].
 *
 * Includes an output-latency compensation ring buffer so the visual glow
 * pulses in lock-step with the sound the listener actually hears. The delay
 * is not a fixed guess: it follows the active output route via
 * [NeveraAudioBus.glowLatencyCompensationMs], which AudioOutputStatus sets
 * per route — phone speaker, wired, USB, HDMI and Bluetooth all deliver
 * sound with different latencies, Bluetooth by far the most.
 *
 * Self-bypasses cleanly when mastering boost is 0 dB and glow analysis is off,
 * preserving bit-exact PCM output. Falls back to pure Kotlin in JVM unit tests.
 */
class NeveraMasteringProcessor {

    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private val bandScratch = FloatArray(4)
    private var lastPublishNs: Long = 0L

    // Tiny lock-free delay ring buffer compensating for output latency by the
    // route-based delay in NeveraAudioBus.glowLatencyCompensationMs, so visual
    // beats align tightly with audible kicks on whatever route is active.
    private val ringReadyNs = LongArray(RING_SIZE)
    private val ringBass = FloatArray(RING_SIZE)
    private val ringTreble = FloatArray(RING_SIZE)
    private val ringSustain = FloatArray(RING_SIZE)
    private val ringBeat = FloatArray(RING_SIZE)
    private var ringHead = 0
    private var ringTail = 0

    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        clearRing()
    }

    fun flush() {
        clearRing()
        NeveraAudioBus.clearAudioBands()
    }

    fun reset() {
        sampleRate = 0
        channelCount = 0
        clearRing()
        NeveraAudioBus.clearAudioBands()
    }

    private fun clearRing() {
        ringHead = 0
        ringTail = 0
    }

    fun process(block: AudioBlock) {
        val frames = block.frameCount
        val channels = channelCount
        if (frames <= 0 || channels <= 0 || sampleRate <= 0) return

        val boostDb = NeveraAudioBus.masteringBoostDb.value.coerceIn(0f, MAX_BOOST_DB)
        val analyze = NeveraAudioBus.audioReactiveGlow.value
        if (boostDb <= 0.05f && !analyze) return

        if (nativeLoaded) {
            nativeProcess(
                samples = block.samples,
                frameCount = frames,
                channelCount = channels,
                sampleRate = sampleRate,
                boostDb = boostDb,
                analyzeBands = analyze,
                outBands = bandScratch,
            )
        } else {
            fallbackProcess(block.samples, frames, channels, boostDb, analyze)
        }

        if (analyze) {
            val now = System.nanoTime()
            if (now - lastPublishNs >= PUBLISH_INTERVAL_NS) {
                lastPublishNs = now
                enqueueAndPublishDelayed(
                    nowNs = now,
                    bass = bandScratch[0],
                    treble = bandScratch[1],
                    sustain = bandScratch[2],
                    beat = bandScratch[3],
                )
            }
        }
    }

    private fun enqueueAndPublishDelayed(
        nowNs: Long,
        bass: Float,
        treble: Float,
        sustain: Float,
        beat: Float,
    ) {
        val nextHead = (ringHead + 1) and RING_MASK
        if (nextHead == ringTail) {
            ringTail = (ringTail + 1) and RING_MASK
        }
        val compensationNs = NeveraAudioBus.glowLatencyCompensationMs.value
            .coerceIn(0L, MAX_GLOW_LATENCY_COMPENSATION_MS) * NS_PER_MS
        ringReadyNs[ringHead] = nowNs + compensationNs
        ringBass[ringHead] = bass
        ringTreble[ringHead] = treble
        ringSustain[ringHead] = sustain
        ringBeat[ringHead] = beat
        ringHead = nextHead

        var emitIndex = -1
        while (ringTail != ringHead && ringReadyNs[ringTail] <= nowNs) {
            emitIndex = ringTail
            ringTail = (ringTail + 1) and RING_MASK
        }
        if (emitIndex >= 0) {
            NeveraAudioBus.publishAudioBands(
                bass = ringBass[emitIndex],
                treble = ringTreble[emitIndex],
                sustain = ringSustain[emitIndex],
                beat = ringBeat[emitIndex],
            )
        } else if (!nativeLoaded) {
            // Unit-test path: publish immediately without waiting out the compensation delay
            NeveraAudioBus.publishAudioBands(bass, treble, sustain, beat)
        }
    }

    private fun fallbackProcess(
        samples: FloatArray,
        frames: Int,
        channels: Int,
        boostDb: Float,
        analyze: Boolean,
    ) {
        val gain = if (boostDb > 0.05f) 10.0.pow(boostDb / 20.0).toFloat() else 1f
        var energySum = 0f
        val total = frames * channels
        for (i in 0 until total) {
            var s = samples[i]
            if (gain > 1f) {
                s *= gain
                val a = abs(s)
                if (a > 0.80f) {
                    val shaped = 0.80f + 0.185f * tanh(((a - 0.80f) * 5.4f).toDouble()).toFloat()
                    s = if (s >= 0f) shaped else -shaped
                }
                samples[i] = s
            }
            if (analyze) energySum += s * s
        }
        if (analyze && total > 0) {
            val rms = sqrt(energySum / total).coerceIn(0f, 1f)
            bandScratch[0] = (rms * 2.8f).coerceIn(0f, 1f)
            bandScratch[1] = (rms * 2.2f).coerceIn(0f, 1f)
            bandScratch[2] = (rms * 2.5f).coerceIn(0f, 1f)
            bandScratch[3] = (rms * 2.4f).coerceIn(0f, 1f)
        }
    }

    private external fun nativeProcess(
        samples: FloatArray,
        frameCount: Int,
        channelCount: Int,
        sampleRate: Int,
        boostDb: Float,
        analyzeBands: Boolean,
        outBands: FloatArray,
    )

    companion object {
        const val MAX_BOOST_DB = 15f
        private const val PUBLISH_INTERVAL_NS = 12_000_000L // ~83 Hz ultra-responsive
        // The compensation itself is route-based and lives in
        // NeveraAudioBus.glowLatencyCompensationMs; this is only its sane ceiling.
        private const val MAX_GLOW_LATENCY_COMPENSATION_MS = 400L
        private const val NS_PER_MS = 1_000_000L
        private const val RING_SIZE = 32
        private const val RING_MASK = RING_SIZE - 1

        private val nativeLoaded: Boolean = try {
            System.loadLibrary("nevera_mastering")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}
