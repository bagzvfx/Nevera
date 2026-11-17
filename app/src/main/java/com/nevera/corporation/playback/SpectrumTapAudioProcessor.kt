package com.nevera.corporation.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Non-destructive Media3 PCM audio processor feeding decoded stereo/multi-channel PCM
 * into SpectrumAudioBus with zero audio modification and zero microphone permission.
 */
@OptIn(UnstableApi::class)
class SpectrumTapAudioProcessor : BaseAudioProcessor() {

    private var monoScratch = FloatArray(1024)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val sampleRate = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: 44100
        val encoding = inputAudioFormat.encoding

        val dup = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frameBytes = channels * 2
                val frames = remaining / frameBytes
                if (frames > 0) {
                    if (monoScratch.size < frames) monoScratch = FloatArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (ch in 0 until channels) {
                            sum += dup.short.toFloat() / 32768f
                        }
                        monoScratch[f] = sum / channels
                    }
                    SpectrumAudioBus.pushMonoSampleBlock(monoScratch, frames, sampleRate)
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                val frameBytes = channels * 4
                val frames = remaining / frameBytes
                if (frames > 0) {
                    if (monoScratch.size < frames) monoScratch = FloatArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (ch in 0 until channels) {
                            sum += dup.float
                        }
                        monoScratch[f] = sum / channels
                    }
                    SpectrumAudioBus.pushMonoSampleBlock(monoScratch, frames, sampleRate)
                }
            }
        }

        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        SpectrumAudioBus.flushTimeline()
    }

    override fun onReset() {
        SpectrumAudioBus.setIdle()
    }
}
