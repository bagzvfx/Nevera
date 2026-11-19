package com.nevera.corporation.playback

import com.nevera.corporation.data.model.AudioOutputRoute
import com.nevera.corporation.data.model.AudioSignalPathState
import com.nevera.corporation.data.model.AudiophileDspProfile
import com.nevera.corporation.data.model.SourceStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live audio telemetry bus feeding NowPlaying's quality pill and Audio Signal Path Inspector.
 */
object AudioSignalPathBus {

    private val _state = MutableStateFlow(AudioSignalPathState())
    val state: StateFlow<AudioSignalPathState> = _state.asStateFlow()

    fun onSourceResolved(stream: SourceStream) {
        _state.update { cur ->
            cur.copy(
                sourceCodec = stream.codec.ifBlank { "Audio" },
                sourceBitrateKbps = stream.kbps,
                sourceSampleRateHz = if (stream.sampleRateHz > 0) stream.sampleRateHz else cur.sourceSampleRateHz,
                sourceBitDepth = if (stream.bitDepth > 0) stream.bitDepth else cur.sourceBitDepth,
                isLossless = stream.isLossless,
                sourceOriginLabel = stream.originLabel.ifBlank { "Studio Stream" }
            )
        }
    }

    fun onDecoderFormatChanged(
        mimeType: String?,
        sampleRateHz: Int,
        channelCount: Int,
        bitrateBps: Int,
        bitDepth: Int
    ) {
        val detectedCodec = when {
            mimeType == null -> null
            mimeType.contains("flac", true) -> "FLAC"
            mimeType.contains("alac", true) -> "ALAC"
            mimeType.contains("opus", true) -> "Opus"
            mimeType.contains("mp4a", true) || mimeType.contains("aac", true) -> "AAC"
            mimeType.contains("vorbis", true) -> "Vorbis"
            mimeType.contains("mpeg", true) || mimeType.contains("mp3", true) -> "MP3"
            mimeType.contains("raw", true) || mimeType.contains("wav", true) -> "PCM"
            else -> null
        }
        val detectedLossless = detectedCodec in setOf("FLAC", "ALAC", "PCM")
        _state.update { cur ->
            cur.copy(
                sourceCodec = detectedCodec ?: cur.sourceCodec,
                sourceSampleRateHz = if (sampleRateHz > 0) sampleRateHz else cur.sourceSampleRateHz,
                sourceChannels = if (channelCount > 0) channelCount else cur.sourceChannels,
                sourceBitrateKbps = if (bitrateBps > 0) (bitrateBps / 1000) else cur.sourceBitrateKbps,
                sourceBitDepth = if (bitDepth > 0) bitDepth else cur.sourceBitDepth,
                isLossless = detectedLossless || cur.isLossless,
                outputSampleRateHz = if (sampleRateHz > 0) sampleRateHz else cur.outputSampleRateHz
            )
        }
    }

    fun onDspProfileUpdated(profile: AudiophileDspProfile, outputEncodingLabel: String) {
        _state.update { cur ->
            cur.copy(
                dspEngineLabel = if (profile.bitPerfectBypass) {
                    "Bit-Perfect Direct Path"
                } else if (!profile.enabled) {
                    "DSP Bypassed"
                } else {
                    "64-bit Float Nevera Studio DSP"
                },
                dspActiveSummary = profile.activeModulesSummary(),
                bitPerfectBypass = profile.bitPerfectBypass,
                outputEncodingLabel = outputEncodingLabel
            )
        }
    }

    fun onHardwareRouteUpdated(
        route: AudioOutputRoute,
        deviceName: String,
        usbBitPerfectExclusive: Boolean
    ) {
        _state.update { cur ->
            cur.copy(
                outputRoute = route,
                outputDeviceName = deviceName.ifBlank { route.displayLabel },
                usbBitPerfectExclusive = usbBitPerfectExclusive
            )
        }
    }
}
