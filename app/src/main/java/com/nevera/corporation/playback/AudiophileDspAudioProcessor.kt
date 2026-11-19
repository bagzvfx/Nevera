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
 * ExoPlayer 32-bit Float / 64-bit internal Studio Mastering AudioProcessor backed by C++17 JNI.
 * Supports lock-free real-time profile updates and Bit-Perfect Direct Bypass.
 */
@OptIn(UnstableApi::class)
class AudiophileDspAudioProcessor : BaseAudioProcessor() {

    companion object {
        private val liveProfileRef = AtomicReference(AudiophileDspProfile())
        private val configDirty = AtomicBoolean(true)

        fun updateLiveProfile(profile: AudiophileDspProfile) {
            liveProfileRef.set(profile)
            configDirty.set(true)
            val encLabel = if (profile.bitPerfectBypass) "Direct Native PCM" else "32-bit Float PCM"
            AudioSignalPathBus.onDspProfileUpdated(profile, encLabel)
        }

        fun currentProfile(): AudiophileDspProfile = liveProfileRef.get()
    }

    private var nativeHandle: Long = 0L
    private var configuredSampleRate: Int = 0
    private var configuredChannels: Int = 0
    private var scratchInterleaved = FloatArray(2048)

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
        recreateHandleIfNeeded(sr, ch)

        val profile = liveProfileRef.get()
        if (profile.bitPerfectBypass) {
            AudioSignalPathBus.onDspProfileUpdated(profile, encodingLabel(enc))
            return inputAudioFormat
        }

        AudioSignalPathBus.onDspProfileUpdated(profile, "32-bit Float PCM")
        return AudioProcessor.AudioFormat(sr, ch, C.ENCODING_PCM_FLOAT)
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

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val profile = liveProfileRef.get()
        if (configDirty.getAndSet(false) && nativeHandle != 0L) {
            NeveraNativeEngine.dspUpdateProfile(nativeHandle, profile)
        }

        val inEnc = inputAudioFormat.encoding
        val outEnc = outputAudioFormat.encoding
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)

        // Pure zero-copy passthrough when configured in native Bit-Perfect mode
        if (profile.bitPerfectBypass && inEnc == outEnc) {
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
            inputBuffer.position(inputBuffer.limit())
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

        if (nativeHandle != 0L && profile.enabled && !profile.bitPerfectBypass) {
            NeveraNativeEngine.dspProcessFloatInterleaved(nativeHandle, scratchInterleaved, frames)
        }

        if (outEnc == C.ENCODING_PCM_FLOAT) {
            val out = replaceOutputBuffer(totalSamples * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until totalSamples) {
                out.putFloat(scratchInterleaved[i])
            }
            out.flip()
        } else if (outEnc == C.ENCODING_PCM_16BIT) {
            val out = replaceOutputBuffer(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until totalSamples) {
                val s = (scratchInterleaved[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                out.putShort(s)
            }
            out.flip()
        } else {
            val out = replaceOutputBuffer(totalSamples * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until totalSamples) {
                out.putFloat(scratchInterleaved[i])
            }
            out.flip()
        }
    }

    override fun onFlush() {
        if (nativeHandle != 0L) {
            NeveraNativeEngine.dspReset(nativeHandle)
        }
    }

    override fun onReset() {
        destroyHandle()
    }

    private fun destroyHandle() {
        if (nativeHandle != 0L) {
            NeveraNativeEngine.dspDestroy(nativeHandle)
            nativeHandle = 0L
        }
        configuredSampleRate = 0
        configuredChannels = 0
    }

    private fun encodingLabel(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_FLOAT -> "32-bit Float PCM"
        C.ENCODING_PCM_32BIT -> "32-bit Direct PCM"
        C.ENCODING_PCM_24BIT -> "24-bit Direct PCM"
        C.ENCODING_PCM_16BIT -> "16-bit Direct PCM"
        else -> "Direct PCM"
    }
}
