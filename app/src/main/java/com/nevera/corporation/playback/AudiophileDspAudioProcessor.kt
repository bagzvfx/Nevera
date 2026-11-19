package com.nevera.corporation.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.nevera.corporation.data.model.AudiophileDspProfile
import com.nevera.corporation.data.native.NeveraNativeEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ExoPlayer 64-bit internal Studio Mastering AudioProcessor backed by C++17 JNI.
 * Always preserves inputAudioFormat.encoding on output so downstream Media3 processors
 * (SilenceSkippingAudioProcessor & SonicAudioProcessor) remain 100% compatible across all Android HALs,
 * while applying TPDF dithering when quantizing 64-bit DSP output back to 16-bit PCM.
 */
@OptIn(UnstableApi::class)
class AudiophileDspAudioProcessor : BaseAudioProcessor() {

    companion object {
        private val liveProfileRef = AtomicReference(AudiophileDspProfile())
        private val configDirty = AtomicBoolean(true)

        fun updateLiveProfile(profile: AudiophileDspProfile) {
            liveProfileRef.set(profile)
            configDirty.set(true)
            val encLabel = if (profile.bitPerfectBypass) {
                "Direct Native PCM"
            } else {
                "64-bit Studio DSP → TPDF PCM"
            }
            AudioSignalPathBus.onDspProfileUpdated(profile, encLabel)
        }

        fun currentProfile(): AudiophileDspProfile = liveProfileRef.get()
    }

    private var nativeHandle: Long = 0L
    private var configuredSampleRate: Int = 0
    private var configuredChannels: Int = 0
    private var scratchInterleaved = FloatArray(2048)
    // Fast xorshift32 PRNG for lightweight TPDF dithering (zero allocations on audio thread)
    private var ditherSeed: Int = 0x5A17C3E9

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT &&
            enc != C.ENCODING_PCM_FLOAT &&
            enc != C.ENCODING_PCM_24BIT &&
            enc != C.ENCODING_PCM_32BIT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        val sr = inputAudioFormat.sampleRate
        val ch = inputAudioFormat.channelCount
        try {
            recreateHandleIfNeeded(sr, ch)
        } catch (_: Throwable) {
            destroyHandle()
        }

        val profile = liveProfileRef.get()
        val label = if (profile.bitPerfectBypass) {
            encodingLabel(enc)
        } else if (enc == C.ENCODING_PCM_16BIT) {
            "64-bit DSP → 16-bit TPDF PCM"
        } else {
            "64-bit DSP → ${encodingLabel(enc)}"
        }
        AudioSignalPathBus.onDspProfileUpdated(profile, label)

        // Strictly preserve inputAudioFormat so downstream SilenceSkipping / Sonic processors never fail
        return inputAudioFormat
    }

    private fun recreateHandleIfNeeded(sampleRate: Int, channels: Int) {
        if (nativeHandle != 0L && configuredSampleRate == sampleRate && configuredChannels == channels) {
            NeveraNativeEngine.dspUpdateProfile(nativeHandle, liveProfileRef.get())
            configDirty.set(false)
            return
        }
        destroyHandle()
        configuredSampleRate = sampleRate
        configuredChannels = channels
        nativeHandle = NeveraNativeEngine.dspCreate(sampleRate, channels)
        NeveraNativeEngine.dspUpdateProfile(nativeHandle, liveProfileRef.get())
        configDirty.set(false)
    }

    private inline fun nextTpdfLsb(): Float {
        var x = ditherSeed
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        val r1 = (x ushr 8).toFloat() / 16777216f // [0, 1)
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        ditherSeed = x
        val r2 = (x ushr 8).toFloat() / 16777216f // [0, 1)
        return r1 - r2 // Triangular PDF in (-1, +1) LSB
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val profile = liveProfileRef.get()
        if (configDirty.getAndSet(false) && nativeHandle != 0L) {
            try {
                NeveraNativeEngine.dspUpdateProfile(nativeHandle, profile)
            } catch (_: Throwable) {
            }
        }

        val inEnc = inputAudioFormat.encoding
        val outEnc = outputAudioFormat.encoding
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)

        // Zero-copy passthrough when Bit-Perfect Direct Bypass is active or DSP disabled / uninitialized
        if (profile.bitPerfectBypass || !profile.enabled || nativeHandle == 0L) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val bytesPerSampleIn = when (inEnc) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> {
                val out = replaceOutputBuffer(remaining)
                out.put(inputBuffer)
                out.flip()
                return
            }
        }

        val frameBytesIn = bytesPerSampleIn * channels
        val frames = remaining / frameBytesIn
        if (frames <= 0) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val totalSamples = frames * channels
        if (scratchInterleaved.size < totalSamples) {
            scratchInterleaved = FloatArray(totalSamples)
        }

        val leIn = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        when (inEnc) {
            C.ENCODING_PCM_16BIT -> {
                for (i in 0 until totalSamples) {
                    scratchInterleaved[i] = leIn.short.toFloat() / 32768f
                }
            }
            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until totalSamples) {
                    val b0 = leIn.get().toInt() and 0xFF
                    val b1 = leIn.get().toInt() and 0xFF
                    val b2 = leIn.get().toInt()
                    val v = (b2 shl 16) or (b1 shl 8) or b0
                    scratchInterleaved[i] = v.toFloat() / 8388608f
                }
            }
            C.ENCODING_PCM_32BIT -> {
                for (i in 0 until totalSamples) {
                    scratchInterleaved[i] = leIn.int.toFloat() / 2147483648f
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                for (i in 0 until totalSamples) {
                    scratchInterleaved[i] = leIn.float
                }
            }
        }

        try {
            NeveraNativeEngine.dspProcessFloatInterleaved(nativeHandle, scratchInterleaved, frames)
        } catch (_: Throwable) {
            // Keep decoded float samples unmodified if native engine fails
        }

        when (outEnc) {
            C.ENCODING_PCM_16BIT -> {
                val out = replaceOutputBuffer(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until totalSamples) {
                    val scaled = scratchInterleaved[i].coerceIn(-1f, 1f) * 32767f + nextTpdfLsb()
                    val s = scaled.toInt().coerceIn(-32768, 32767).toShort()
                    out.putShort(s)
                }
                out.flip()
            }
            C.ENCODING_PCM_24BIT -> {
                val out = replaceOutputBuffer(totalSamples * 3).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until totalSamples) {
                    val v = (scratchInterleaved[i].coerceIn(-1f, 1f) * 8388607f).toInt().coerceIn(-8388608, 8388607)
                    out.put((v and 0xFF).toByte())
                    out.put(((v ushr 8) and 0xFF).toByte())
                    out.put(((v ushr 16) and 0xFF).toByte())
                }
                out.flip()
            }
            C.ENCODING_PCM_32BIT -> {
                val out = replaceOutputBuffer(totalSamples * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until totalSamples) {
                    val v = (scratchInterleaved[i].coerceIn(-1f, 1f).toDouble() * 2147483647.0).toInt()
                    out.putInt(v)
                }
                out.flip()
            }
            C.ENCODING_PCM_FLOAT -> {
                val out = replaceOutputBuffer(totalSamples * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until totalSamples) {
                    out.putFloat(scratchInterleaved[i])
                }
                out.flip()
            }
            else -> {
                val out = replaceOutputBuffer(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until totalSamples) {
                    val s = (scratchInterleaved[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    out.putShort(s)
                }
                out.flip()
            }
        }
    }

    override fun onFlush() {
        if (nativeHandle != 0L) {
            try {
                NeveraNativeEngine.dspReset(nativeHandle)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onReset() {
        destroyHandle()
    }

    private fun destroyHandle() {
        if (nativeHandle != 0L) {
            try {
                NeveraNativeEngine.dspDestroy(nativeHandle)
            } catch (_: Throwable) {
            }
            nativeHandle = 0L
        }
        configuredSampleRate = 0
        configuredChannels = 0
    }

    private fun encodingLabel(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_FLOAT -> "32-bit Float PCM"
        C.ENCODING_PCM_32BIT -> "32-bit PCM"
        C.ENCODING_PCM_24BIT -> "24-bit PCM"
        C.ENCODING_PCM_16BIT -> "16-bit PCM"
        else -> "Direct PCM"
    }
}
