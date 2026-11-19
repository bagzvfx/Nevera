package com.nevera.corporation.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AudioOutputRoute(val displayLabel: String) {
    PHONE_SPEAKER("Phone Speaker"),
    WIRED_IEM("Wired IEM / Headphones"),
    BLUETOOTH_AUDIO("Bluetooth Audio"),
    USB_DAC("USB DAC")
}

@Serializable
enum class CrossfeedMode(val label: String, val fcutHz: Float, val feedDb: Float) {
    OFF("Off", 0f, 0f),
    STUDIO_DEFAULT("Studio", 700f, 4.5f),
    CHU_MOY("Chu Moy", 700f, 6.0f),
    JAN_MEIER("Meier", 650f, 9.5f)
}

@Serializable
data class EqBandSpec(
    val frequencyHz: Float,
    val gainDb: Float = 0f,
    val qFactor: Float = 1.41f
)

@Serializable
data class AudiophileDspProfile(
    val enabled: Boolean = true,
    val bitPerfectBypass: Boolean = false,
    val presetName: String = "Flat Reference",
    val bands: List<EqBandSpec> = DEFAULT_TEN_BANDS,
    val preampDb: Float = 0f,
    val autoPreamp: Boolean = true,
    val crossfeedMode: CrossfeedMode = CrossfeedMode.OFF,
    val stereoWidth: Float = 1.0f,
    val channelBalance: Float = 0f,
    val tubeWarmth: Float = 0f,
    val lufsNormalization: Boolean = true,
    val targetLufs: Float = -14f
) {
    fun normalizedBands(): List<EqBandSpec> {
        if (bands.size == DEFAULT_TEN_BANDS.size) return bands
        return DEFAULT_TEN_BANDS.mapIndexed { idx, fallback ->
            bands.getOrNull(idx) ?: fallback
        }
    }

    fun isActivelyProcessing(): Boolean {
        if (!enabled || bitPerfectBypass) return false
        val hasEq = normalizedBands().any { kotlin.math.abs(it.gainDb) > 0.05f }
        return hasEq ||
            crossfeedMode != CrossfeedMode.OFF ||
            kotlin.math.abs(stereoWidth - 1.0f) > 0.02f ||
            kotlin.math.abs(channelBalance) > 0.02f ||
            tubeWarmth > 0.02f ||
            lufsNormalization ||
            kotlin.math.abs(preampDb) > 0.05f
    }

    fun activeModulesSummary(): String {
        if (bitPerfectBypass) return "Bit-Perfect Direct Bypass"
        if (!enabled) return "DSP Off"
        val mods = mutableListOf<String>()
        if (normalizedBands().any { kotlin.math.abs(it.gainDb) > 0.05f }) {
            mods.add("64-bit PEQ ($presetName)")
        }
        if (crossfeedMode != CrossfeedMode.OFF) mods.add("BS2B ${crossfeedMode.label}")
        if (lufsNormalization) mods.add("EBU R128")
        if (kotlin.math.abs(stereoWidth - 1.0f) > 0.02f) mods.add("M/S Width")
        if (tubeWarmth > 0.02f) mods.add("Tube Warmth")
        if (mods.isEmpty()) mods.add("64-bit Float Limiter")
        return mods.joinToString(" · ")
    }

    companion object {
        val DEFAULT_FREQUENCIES = floatArrayOf(
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )

        val DEFAULT_TEN_BANDS: List<EqBandSpec> = listOf(
            EqBandSpec(31f, 0f, 0.71f),
            EqBandSpec(62f, 0f, 1.41f),
            EqBandSpec(125f, 0f, 1.41f),
            EqBandSpec(250f, 0f, 1.41f),
            EqBandSpec(500f, 0f, 1.41f),
            EqBandSpec(1000f, 0f, 1.41f),
            EqBandSpec(2000f, 0f, 1.41f),
            EqBandSpec(4000f, 0f, 1.41f),
            EqBandSpec(8000f, 0f, 1.41f),
            EqBandSpec(16000f, 0f, 0.71f)
        )
    }
}

@Serializable
enum class NetworkServerType(val label: String) {
    SUBSONIC("Subsonic / OpenSubsonic"),
    WEBDAV("WebDAV / Direct HTTP")
}

@Serializable
data class NetworkServerConfig(
    val id: String,
    val name: String,
    val type: NetworkServerType = NetworkServerType.SUBSONIC,
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = true
)

data class AudioSignalPathState(
    val sourceCodec: String = "Ready",
    val sourceBitrateKbps: Int = 0,
    val sourceSampleRateHz: Int = 0,
    val sourceBitDepth: Int = 0,
    val sourceChannels: Int = 2,
    val isLossless: Boolean = false,
    val sourceOriginLabel: String = "Studio Stream",
    val dspEngineLabel: String = "64-bit Float Studio DSP",
    val dspActiveSummary: String = "Flat Reference",
    val bitPerfectBypass: Boolean = false,
    val outputRoute: AudioOutputRoute = AudioOutputRoute.PHONE_SPEAKER,
    val outputDeviceName: String = "Phone Speaker",
    val outputSampleRateHz: Int = 48000,
    val outputEncodingLabel: String = "32-bit Float PCM",
    val usbBitPerfectExclusive: Boolean = false
) {
    val compactBadge: String
        get() {
            val srKhz = if (sourceSampleRateHz >= 1000) {
                val khz = sourceSampleRateHz / 1000f
                if (khz % 1f == 0f) "${khz.toInt()}kHz" else String.format(java.util.Locale.US, "%.1fkHz", khz)
            } else ""

            val codec = sourceCodec.uppercase(java.util.Locale.US).ifBlank { "AUDIO" }
            if (codec == "READY") return "Studio Audio"

            return when {
                isLossless && sourceBitDepth >= 24 && srKhz.isNotBlank() ->
                    "Hi-Res $codec · ${sourceBitDepth}-bit/$srKhz"
                isLossless && srKhz.isNotBlank() ->
                    "Lossless $codec · $srKhz"
                usbBitPerfectExclusive && srKhz.isNotBlank() ->
                    "Bit-Perfect · $codec $srKhz"
                sourceBitrateKbps > 0 && srKhz.isNotBlank() ->
                    "$codec · ${sourceBitrateKbps}kbps · $srKhz"
                sourceBitrateKbps > 0 ->
                    "$codec · ${sourceBitrateKbps}kbps"
                srKhz.isNotBlank() ->
                    "$codec · $srKhz"
                else -> sourceOriginLabel
            }
        }
}
