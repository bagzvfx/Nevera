package com.music.bitchord.playback.audio

import com.music.bitchord.playback.GlowLatencyPolicy
import com.music.bitchord.playback.NeveraAudioBus
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Native C++17 Studio Mastering Loudness Maximizer + soft-knee true-peak limiter
 * + beat-onset bass/treble rhythm analyzer riding at the tail of [DspChain].
 *
 * ## Glow/waveform synchronization
 *
 * The analyzer sees PCM long before the listener hears it: the sink queues
 * audio ahead of the speaker (its "lead"), and that lead — not the output
 * route — is the dominant, most variable part of the delay. A fixed
 * per-route delay therefore cannot line the glow up, which is why the
 * previous route-table compensation still left the glow off the bass.
 *
 * Two mechanisms now combine:
 *  - The compensation itself is resolved per block by [GlowLatencyPolicy]
 *    from the best measurement available: the sink's measured lead
 *    ([NeveraAudioBus.dspLeadMs]), else the AudioTrack buffer duration
 *    ([NeveraAudioBus.trackBufferMs]), else the route table.
 *  - Analyzed bands are scheduled on the **media timeline**, not the
 *    wall-clock arrival timeline (see [GlowFrameClock]). Blocks reach this
 *    processor in bursts — a burst can carry a second of audio processed in
 *    a millisecond — so stamping frames "now + delay" piles a whole burst
 *    onto one instant and lets stale frames win. Stamping each frame with
 *    the wall time its media position will be *heard* keeps bursts spaced
 *    exactly as the music is, and the drain always publishes the latest
 *    frame whose heard-time has arrived, dropping anything staler.
 *
 * Self-bypasses cleanly when mastering boost is 0 dB and glow analysis is off,
 * preserving bit-exact PCM output. Falls back to pure Kotlin in JVM unit tests.
 */
class NeveraMasteringProcessor {

    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private val bandScratch = FloatArray(4)

    /** Frames analyzed since the last (re)configure/flush: the media timeline. */
    private var mediaFramesProcessed: Long = 0L
    private val frameClock = GlowFrameClock()

    // Delay ring holding analyzed frames between analysis and their
    // media-scheduled publication time. Sized for the worst legitimate
    // compensation (float-route sink leads can reach seconds) at the
    // finest block cadence the chain produces; when full, the oldest —
    // stalest — frame is overwritten first.
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
        resetVisualTimeline()
    }

    fun flush() {
        resetVisualTimeline()
        NeveraAudioBus.clearAudioBands()
    }

    fun reset() {
        sampleRate = 0
        channelCount = 0
        resetVisualTimeline()
        NeveraAudioBus.clearAudioBands()
    }

    private fun resetVisualTimeline() {
        ringHead = 0
        ringTail = 0
        mediaFramesProcessed = 0L
        frameClock.reset()
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
            // Every analyzed block is enqueued — no wall-clock throttle.
            // The old 12 ms throttle silently discarded most blocks of a
            // burst, so the glow ended up showing whichever single block
            // happened to pass the gate, up to a burst-length stale.
            val blockStartFrames = mediaFramesProcessed
            mediaFramesProcessed += frames
            enqueueAndPublishDelayed(
                nowNs = System.nanoTime(),
                blockStartMediaNs = framesToNs(blockStartFrames),
                blockEndMediaNs = framesToNs(mediaFramesProcessed),
                bass = bandScratch[0],
                treble = bandScratch[1],
                sustain = bandScratch[2],
                beat = bandScratch[3],
            )
        }
    }

    private fun framesToNs(frames: Long): Long =
        frames * NS_PER_SECOND / sampleRate

    private fun enqueueAndPublishDelayed(
        nowNs: Long,
        blockStartMediaNs: Long,
        blockEndMediaNs: Long,
        bass: Float,
        treble: Float,
        sustain: Float,
        beat: Float,
    ) {
        val compensationMs = GlowLatencyPolicy.resolveCompensationMs(
            measuredLeadMs = NeveraAudioBus.dspLeadMs.value,
            trackBufferMs = NeveraAudioBus.trackBufferMs.value,
            routeTailMs = NeveraAudioBus.glowRouteTailMs.value,
            legacyTotalMs = NeveraAudioBus.glowLatencyCompensationMs.value,
        )
        val readyNs = frameClock.readyTimeNs(
            blockStartMediaNs = blockStartMediaNs,
            blockEndMediaNs = blockEndMediaNs,
            arrivalWallNs = nowNs,
            compensationNs = compensationMs * NS_PER_MS,
        )
        if (frameClock.consumeAnchorChange()) {
            // The media→wall mapping was just re-established (seek, starved
            // delivery, collapsed lead…): frames still queued were stamped
            // on the old mapping and would either fire at wrong times or —
            // worse — sit in front of this frame in the FIFO with far-future
            // ready times and block it. They are stale by definition now.
            ringHead = 0
            ringTail = 0
        }

        val nextHead = (ringHead + 1) and RING_MASK
        if (nextHead == ringTail) {
            ringTail = (ringTail + 1) and RING_MASK
        }
        ringReadyNs[ringHead] = readyNs
        ringBass[ringHead] = bass
        ringTreble[ringHead] = treble
        ringSustain[ringHead] = sustain
        ringBeat[ringHead] = beat
        ringHead = nextHead

        // Latest-wins drain: publish the newest frame whose heard-time has
        // arrived and drop every older one. Stale frames never accumulate,
        // so the glow can never fall behind by more than one block.
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
        private const val NS_PER_MS = 1_000_000L
        private const val NS_PER_SECOND = 1_000_000_000L
        private const val RING_SIZE = 1024
        private const val RING_MASK = RING_SIZE - 1

        private val nativeLoaded: Boolean = try {
            System.loadLibrary("nevera_mastering")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}

/**
 * Maps media positions to the wall-clock times at which they will be heard.
 *
 * The mapping is a single anchor — (media position ↔ wall time) — plus the
 * assumption that media then advances 1:1 with the wall clock. The anchor is
 * (re)established from a block's arrival plus the current compensation
 * whenever the existing anchor can no longer describe reality:
 *  - the first block after a reset,
 *  - media jumping backwards (seek / track change without a flush),
 *  - the projection falling meaningfully behind arrival (delivery starved
 *    or the lead shrank: keeping the old anchor would dump a backlog of
 *    instantly-ready frames and then leave the glow early), or
 *  - the projection landing absurdly far in the future (the lead estimate
 *    collapsed, e.g. a float route's multi-second buffer was replaced by a
 *    shallow one: keeping the old anchor would hold the glow back for
 *    seconds after the sound has already changed).
 *
 * Pure and allocation-free; unit-tested on the JVM.
 */
internal class GlowFrameClock {

    private var anchorWallNs: Long = Long.MIN_VALUE
    private var anchorMediaNs: Long = 0L
    private var lastBlockEndMediaNs: Long = Long.MIN_VALUE
    private var anchorChanged: Boolean = false

    fun reset() {
        anchorWallNs = Long.MIN_VALUE
        anchorMediaNs = 0L
        lastBlockEndMediaNs = Long.MIN_VALUE
        anchorChanged = false
    }

    /**
     * True once per (re)anchor performed by [readyTimeNs], so the caller can
     * drop frames that were scheduled on the previous mapping.
     */
    fun consumeAnchorChange(): Boolean {
        val changed = anchorChanged
        anchorChanged = false
        return changed
    }

    /**
     * Wall-clock time at which the middle of the block spanning
     * [[blockStartMediaNs], [blockEndMediaNs]] will be heard, given the
     * block arrived at [arrivalWallNs] and the current end-to-end
     * compensation is [compensationNs]. The block middle is used because
     * the analyzed bands summarize the whole block.
     */
    fun readyTimeNs(
        blockStartMediaNs: Long,
        blockEndMediaNs: Long,
        arrivalWallNs: Long,
        compensationNs: Long,
    ): Long {
        if (anchorWallNs == Long.MIN_VALUE ||
            blockStartMediaNs < lastBlockEndMediaNs - TOLERANCE_NS
        ) {
            anchor(arrivalWallNs, compensationNs, blockStartMediaNs)
        } else {
            val projectedStartNs = anchorWallNs + (blockStartMediaNs - anchorMediaNs)
            if (projectedStartNs < arrivalWallNs - TOLERANCE_NS ||
                projectedStartNs > arrivalWallNs + compensationNs + FAR_FUTURE_TOLERANCE_NS
            ) {
                anchor(arrivalWallNs, compensationNs, blockStartMediaNs)
            }
        }
        lastBlockEndMediaNs = blockEndMediaNs
        val midMediaNs = (blockStartMediaNs + blockEndMediaNs) / 2
        return anchorWallNs + (midMediaNs - anchorMediaNs)
    }

    private fun anchor(arrivalWallNs: Long, compensationNs: Long, blockStartMediaNs: Long) {
        anchorWallNs = arrivalWallNs + compensationNs
        anchorMediaNs = blockStartMediaNs
        anchorChanged = true
    }

    companion object {
        /** Scheduling drift tolerated before the anchor is re-established. */
        private const val TOLERANCE_NS = 25_000_000L

        /** How far ahead of (arrival + compensation) a projection may sit. */
        private const val FAR_FUTURE_TOLERANCE_NS = 250_000_000L
    }
}
