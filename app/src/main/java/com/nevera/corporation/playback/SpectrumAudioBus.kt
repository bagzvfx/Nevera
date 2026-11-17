package com.nevera.corporation.playback

import com.nevera.corporation.data.native.NeveraNativeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class SpectrumSnapshot(
    val bands: FloatArray = FloatArray(32),
    val bpm: Float = 0f,
    val beatPulse: Float = 0f,
    val bassEnergy: Float = 0f,
    val vocalEnergy: Float = 0f,
    val trebleEnergy: Float = 0f,
    val isActive: Boolean = false,
    val tick: Long = 0L
)

class SyncedAudioSample(
    val bands: FloatArray = FloatArray(32),
    var bassEnergy: Float = 0f,
    var vocalEnergy: Float = 0f,
    var trebleEnergy: Float = 0f,
    var beatPulse: Float = 0f,
    var isActive: Boolean = false
)

/**
 * Shared real-time audio spectrum & energy bus fed from ExoPlayer's decoded PCM stream.
 * Uses a timestamped circular queue with automatic AudioTrack output latency compensation
 * so visual bars and reactive glow align with audible speaker output (~165ms calibrated).
 */
object SpectrumAudioBus {

    private const val FFT_WINDOW = 512
    private const val BANDS_COUNT = 32
    private const val HOP_SAMPLES = 384
    private const val TIMELINE_CAP = 64
    private const val DEFAULT_LATENCY_MS = 165L

    private class TimedFrame {
        var readyAtNanos: Long = 0L
        val bands = FloatArray(BANDS_COUNT)
        var bpm: Float = 0f
        var beatPulse: Float = 0f
        var bassEnergy: Float = 0f
        var vocalEnergy: Float = 0f
        var trebleEnergy: Float = 0f
        var valid: Boolean = false
    }

    private val ringBuffer = FloatArray(FFT_WINDOW)
    private var ringCursor = 0
    private var samplesAccumulated = 0

    private val nativeState = FloatArray(40)
    private val workWindow = FloatArray(FFT_WINDOW)
    private val workBands = FloatArray(BANDS_COUNT)
    private val tickGen = AtomicLong(0L)

    private val timeline = Array(TIMELINE_CAP) { TimedFrame() }
    private var timelineHead = 0
    private var timelineSize = 0
    private var lastAnalyzedReadyNanos = 0L

    @Volatile
    var outputLatencyMs: Long = DEFAULT_LATENCY_MS

    @Volatile
    var latestBands: FloatArray = FloatArray(BANDS_COUNT)
        private set

    @Volatile
    var latestBpm: Float = 0f
        private set

    @Volatile
    var latestBeatPulse: Float = 0f
        private set

    @Volatile
    var latestBassEnergy: Float = 0f
        private set

    @Volatile
    var latestVocalEnergy: Float = 0f
        private set

    @Volatile
    var latestTrebleEnergy: Float = 0f
        private set

    @Volatile
    var isAudioActive: Boolean = false
        private set

    private val _snapshot = MutableStateFlow(SpectrumSnapshot())
    val snapshot: StateFlow<SpectrumSnapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun pushMonoSampleBlock(
        samples: FloatArray,
        count: Int,
        sampleRate: Int,
        nowNanos: Long = System.nanoTime()
    ) {
        if (count <= 0 || sampleRate <= 0) return
        isAudioActive = true

        val latencyNs = outputLatencyMs.coerceIn(0L, 400L) * 1_000_000L
        val totalDurationNs = (count.toDouble() / sampleRate.toDouble() * 1_000_000_000.0).toLong()
        val blockStartNanos = maxOf(
            nowNanos + latencyNs,
            lastAnalyzedReadyNanos
        )

        var processed = 0
        while (processed < count) {
            ringBuffer[ringCursor] = samples[processed]
            ringCursor = (ringCursor + 1) and (FFT_WINDOW - 1)
            samplesAccumulated++
            processed++

            if (samplesAccumulated >= HOP_SAMPLES) {
                samplesAccumulated = 0
                for (i in 0 until FFT_WINDOW) {
                    workWindow[i] = ringBuffer[(ringCursor + i) and (FFT_WINDOW - 1)]
                }
                NeveraNativeEngine.analyzeSpectrumAndBpm(workWindow, sampleRate, workBands, nativeState)

                val progressFrac = processed.toDouble() / count.toDouble()
                val readyAt = blockStartNanos + (totalDurationNs * progressFrac).toLong()
                // Guard runaway drift if decoder buffers multiple seconds ahead
                val maxLead = nowNanos + latencyNs + 350_000_000L
                val clampedReady = readyAt.coerceAtMost(maxLead)
                lastAnalyzedReadyNanos = clampedReady

                val bass = avgBandRange(workBands, 0, 9)
                val vocal = avgBandRange(workBands, 10, 21)
                val treble = avgBandRange(workBands, 22, 31)
                val bpm = nativeState[36]
                val pulse = nativeState[37]

                pushTimelineFrame(clampedReady, workBands, bpm, pulse, bass, vocal, treble)

                if (latencyNs == 0L) {
                    publishFrameValues(workBands.copyOf(), bpm, pulse, bass, vocal, treble)
                }
            }
        }
    }

    private fun pushTimelineFrame(
        readyAtNanos: Long,
        bands: FloatArray,
        bpm: Float,
        beatPulse: Float,
        bass: Float,
        vocal: Float,
        treble: Float
    ) {
        if (timelineSize == TIMELINE_CAP) {
            timelineHead = (timelineHead + 1) % TIMELINE_CAP
            timelineSize--
        }
        val writeIdx = (timelineHead + timelineSize) % TIMELINE_CAP
        val slot = timeline[writeIdx]
        slot.readyAtNanos = readyAtNanos
        System.arraycopy(bands, 0, slot.bands, 0, BANDS_COUNT)
        slot.bpm = bpm
        slot.beatPulse = beatPulse
        slot.bassEnergy = bass
        slot.vocalEnergy = vocal
        slot.trebleEnergy = treble
        slot.valid = true
        timelineSize++
    }

    /**
     * Zero-allocation read synchronized to audible playback time (`nowNanos`).
     */
    @Synchronized
    fun sampleAt(nowNanos: Long, out: SyncedAudioSample): Boolean {
        if (!isAudioActive) {
            out.isActive = false
            out.beatPulse = 0f
            out.bassEnergy = 0f
            out.vocalEnergy = 0f
            out.trebleEnergy = 0f
            return false
        }

        var chosen: TimedFrame? = null
        while (timelineSize > 0) {
            val f = timeline[timelineHead]
            if (f.readyAtNanos <= nowNanos) {
                chosen = f
                timelineHead = (timelineHead + 1) % TIMELINE_CAP
                timelineSize--
            } else {
                break
            }
        }

        if (chosen != null) {
            System.arraycopy(chosen.bands, 0, out.bands, 0, BANDS_COUNT)
            System.arraycopy(chosen.bands, 0, latestBands, 0, BANDS_COUNT)
            out.bassEnergy = chosen.bassEnergy
            out.vocalEnergy = chosen.vocalEnergy
            out.trebleEnergy = chosen.trebleEnergy
            out.beatPulse = chosen.beatPulse
            out.isActive = true

            latestBpm = chosen.bpm
            latestBeatPulse = chosen.beatPulse
            latestBassEnergy = chosen.bassEnergy
            latestVocalEnergy = chosen.vocalEnergy
            latestTrebleEnergy = chosen.trebleEnergy
            return true
        }

        // Between queued hops or right after first buffered decode: reuse latest active snapshot
        System.arraycopy(latestBands, 0, out.bands, 0, BANDS_COUNT)
        out.bassEnergy = latestBassEnergy
        out.vocalEnergy = latestVocalEnergy
        out.trebleEnergy = latestTrebleEnergy
        out.beatPulse = latestBeatPulse
        out.isActive = true
        return true
    }

    private fun publishFrameValues(
        copyBands: FloatArray,
        bpm: Float,
        pulse: Float,
        bass: Float,
        vocal: Float,
        treble: Float
    ) {
        val tick = tickGen.incrementAndGet()
        latestBands = copyBands
        latestBpm = bpm
        latestBeatPulse = pulse
        latestBassEnergy = bass
        latestVocalEnergy = vocal
        latestTrebleEnergy = treble
        _snapshot.value = SpectrumSnapshot(
            bands = copyBands,
            bpm = bpm,
            beatPulse = pulse,
            bassEnergy = bass,
            vocalEnergy = vocal,
            trebleEnergy = treble,
            isActive = true,
            tick = tick
        )
    }

    @Synchronized
    fun flushTimeline() {
        timelineHead = 0
        timelineSize = 0
        lastAnalyzedReadyNanos = 0L
        samplesAccumulated = 0
        ringBuffer.fill(0f)
        nativeState.fill(0f)
        latestBands = FloatArray(BANDS_COUNT)
        latestBeatPulse = 0f
        latestBassEnergy = 0f
        latestVocalEnergy = 0f
        latestTrebleEnergy = 0f
    }

    @Synchronized
    fun setIdle() {
        isAudioActive = false
        flushTimeline()
        _snapshot.value = _snapshot.value.copy(
            beatPulse = 0f,
            bassEnergy = 0f,
            vocalEnergy = 0f,
            trebleEnergy = 0f,
            isActive = false,
            tick = tickGen.incrementAndGet()
        )
    }

    private fun avgBandRange(bands: FloatArray, start: Int, endInclusive: Int): Float {
        var sum = 0f
        var cnt = 0
        for (i in start..endInclusive.coerceAtMost(bands.lastIndex)) {
            sum += bands[i]
            cnt++
        }
        return if (cnt > 0) (sum / cnt).coerceIn(0f, 1f) else 0f
    }
}
