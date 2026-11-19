package com.nevera.corporation.data.audio

import com.nevera.corporation.data.model.AudiophileDspProfile
import com.nevera.corporation.data.model.CrossfeedMode
import com.nevera.corporation.data.model.EqBandSpec
import kotlin.math.abs
import kotlin.math.ln

data class AutoEqPreset(
    val name: String,
    val category: String,
    val bands: List<EqBandSpec>,
    val preampDb: Float = 0f,
    val suggestedCrossfeed: CrossfeedMode = CrossfeedMode.OFF
)

/**
 * Curated Studio Reference & AutoEQ IEM / Headphone Target Library + Standard AutoEQ .txt parser.
 */
object AutoEqCatalog {

    private fun tenBands(gains: FloatArray, qs: FloatArray? = null): List<EqBandSpec> {
        val freqs = AudiophileDspProfile.DEFAULT_FREQUENCIES
        return List(freqs.size) { i ->
            val defaultQ = if (i == 0 || i == freqs.lastIndex) 0.71f else 1.41f
            EqBandSpec(
                frequencyHz = freqs[i],
                gainDb = gains.getOrElse(i) { 0f }.coerceIn(-12f, 12f),
                qFactor = (qs?.getOrElse(i) { defaultQ } ?: defaultQ).coerceIn(0.4f, 6.0f)
            )
        }
    }

    val presets: List<AutoEqPreset> = listOf(
        AutoEqPreset(
            name = "Flat Reference",
            category = "Studio",
            bands = tenBands(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        ),
        AutoEqPreset(
            name = "Harman 2019 IE",
            category = "Target",
            bands = tenBands(floatArrayOf(3.2f, 2.6f, 0.8f, -1.0f, -0.4f, 0.2f, 1.4f, 2.2f, 1.0f, 0.6f)),
            preampDb = -3.4f,
            suggestedCrossfeed = CrossfeedMode.STUDIO_DEFAULT
        ),
        AutoEqPreset(
            name = "Warm Audiophile",
            category = "Studio",
            bands = tenBands(floatArrayOf(2.2f, 2.0f, 1.5f, 0.8f, 0.2f, -0.2f, -0.4f, -0.8f, -0.5f, 0.4f)),
            preampDb = -2.4f,
            suggestedCrossfeed = CrossfeedMode.STUDIO_DEFAULT
        ),
        AutoEqPreset(
            name = "Vocal Clarity",
            category = "Studio",
            bands = tenBands(floatArrayOf(-1.8f, -1.2f, -0.6f, 0.4f, 1.8f, 3.2f, 3.8f, 2.6f, 1.0f, 0.4f)),
            preampDb = -3.8f
        ),
        AutoEqPreset(
            name = "Sub-Bass Studio",
            category = "Studio",
            bands = tenBands(floatArrayOf(5.2f, 4.6f, 2.5f, 0.5f, -0.5f, 0f, 0.4f, 0.8f, 1.0f, 0.6f)),
            preampDb = -5.4f
        ),
        AutoEqPreset(
            name = "Acoustic Jazz",
            category = "Studio",
            bands = tenBands(floatArrayOf(1.6f, 1.2f, 0.4f, 1.0f, 2.0f, 1.4f, 0.8f, 1.8f, 2.2f, 1.4f)),
            preampDb = -2.4f,
            suggestedCrossfeed = CrossfeedMode.STUDIO_DEFAULT
        ),
        AutoEqPreset(
            name = "Rock Punch",
            category = "Studio",
            bands = tenBands(floatArrayOf(3.4f, 2.8f, 1.2f, -0.4f, -1.2f, 0.4f, 1.8f, 3.0f, 3.2f, 2.2f)),
            preampDb = -3.6f
        ),
        AutoEqPreset(
            name = "Classical Air",
            category = "Studio",
            bands = tenBands(floatArrayOf(1.8f, 1.2f, 0.4f, 0f, 0f, 0.4f, 1.4f, 2.4f, 3.2f, 3.8f)),
            preampDb = -4.0f,
            suggestedCrossfeed = CrossfeedMode.JAN_MEIER
        ),
        AutoEqPreset(
            name = "Moondrop Chu II",
            category = "AutoEQ IEM",
            bands = tenBands(floatArrayOf(1.4f, 0.8f, -0.6f, -1.4f, -0.4f, 0.5f, 1.6f, -1.2f, -1.8f, 1.2f)),
            preampDb = -2.0f,
            suggestedCrossfeed = CrossfeedMode.STUDIO_DEFAULT
        ),
        AutoEqPreset(
            name = "Truthear Hexa",
            category = "AutoEQ IEM",
            bands = tenBands(floatArrayOf(2.8f, 2.0f, 0.6f, -0.4f, 0f, 0.2f, 0.8f, 0.4f, -0.8f, 1.0f)),
            preampDb = -3.0f,
            suggestedCrossfeed = CrossfeedMode.STUDIO_DEFAULT
        ),
        AutoEqPreset(
            name = "KZ ZSN Pro",
            category = "AutoEQ IEM",
            bands = tenBands(floatArrayOf(-1.2f, -2.4f, -2.0f, -0.8f, 1.2f, 1.8f, -2.6f, -4.2f, -2.8f, 0.8f)),
            preampDb = -2.0f,
            suggestedCrossfeed = CrossfeedMode.STUDIO_DEFAULT
        ),
        AutoEqPreset(
            name = "Sony WH-1000XM5",
            category = "AutoEQ TWS/BT",
            bands = tenBands(floatArrayOf(-2.2f, -3.4f, -3.8f, -2.4f, 0.6f, 2.0f, 3.2f, 2.4f, 1.6f, 1.8f)),
            preampDb = -3.4f,
            suggestedCrossfeed = CrossfeedMode.CHU_MOY
        ),
        AutoEqPreset(
            name = "AirPods Pro 2",
            category = "AutoEQ TWS/BT",
            bands = tenBands(floatArrayOf(1.2f, 0.6f, -0.8f, -1.2f, -0.2f, 0.8f, 1.6f, 1.4f, 0.6f, 1.8f)),
            preampDb = -2.0f,
            suggestedCrossfeed = CrossfeedMode.STUDIO_DEFAULT
        ),
        AutoEqPreset(
            name = "Sennheiser HD 600",
            category = "AutoEQ Open",
            bands = tenBands(floatArrayOf(4.6f, 3.2f, 0.8f, -1.0f, -0.4f, 0f, -0.6f, -1.2f, 0.6f, 1.4f)),
            preampDb = -4.8f,
            suggestedCrossfeed = CrossfeedMode.JAN_MEIER
        )
    )

    fun applyPreset(current: AudiophileDspProfile, preset: AutoEqPreset): AudiophileDspProfile {
        return current.copy(
            enabled = true,
            bitPerfectBypass = false,
            presetName = preset.name,
            bands = preset.bands,
            preampDb = preset.preampDb,
            crossfeedMode = if (current.crossfeedMode == CrossfeedMode.OFF) preset.suggestedCrossfeed else current.crossfeedMode
        )
    }

    data class ParsedAutoEqResult(
        val name: String,
        val preampDb: Float,
        val bands: List<EqBandSpec>,
        val parsedFilterCount: Int
    )

    private val preampRegex = Regex("""(?i)preamp\s*:\s*([+-]?\d+(?:\.\d+)?)\s*db""")
    private val filterRegex = Regex(
        """(?i)filter\s*\d*\s*:\s*on\s+(?:pk|peaking|ls|lsc|hs|hsc)\s+fc\s+(\d+(?:\.\d+)?)\s*hz\s+gain\s+([+-]?\d+(?:\.\d+)?)\s*db(?:\s+q\s+(\d+(?:\.\d+)?))?"""
    )

    /**
     * Parses standard AutoEQ / EqualizerAPO / Squig.link ParametricEQ text files into Nevera's 10-band engine.
     */
    fun parseAutoEqText(raw: String, customName: String = "Custom AutoEQ"): ParsedAutoEqResult? {
        if (raw.isBlank()) return null

        val preamp = preampRegex.find(raw)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.coerceIn(-12f, 6f) ?: 0f
        val parsed = mutableListOf<EqBandSpec>()

        for (match in filterRegex.findAll(raw)) {
            val fc = match.groupValues[1].toFloatOrNull() ?: continue
            val gain = match.groupValues[2].toFloatOrNull() ?: continue
            val q = match.groupValues.getOrNull(3)?.toFloatOrNull() ?: 1.41f
            parsed.add(
                EqBandSpec(
                    frequencyHz = fc.coerceIn(20f, 20000f),
                    gainDb = gain.coerceIn(-12f, 12f),
                    qFactor = q.coerceIn(0.4f, 6.0f)
                )
            )
        }

        if (parsed.isEmpty()) return null

        val sorted = parsed.sortedBy { it.frequencyHz }
        val tenBands = when {
            sorted.size == 10 -> sorted
            sorted.size < 10 -> {
                // Place parsed bands onto nearest logarithmic slots so standard 10-band UI stays intact
                val defaults = AudiophileDspProfile.DEFAULT_TEN_BANDS.toMutableList()
                val usedSlots = mutableSetOf<Int>()
                for (band in sorted) {
                    var bestSlot = 0
                    var bestDist = Double.MAX_VALUE
                    for (i in defaults.indices) {
                        if (i in usedSlots) continue
                        val d = abs(ln(band.frequencyHz.toDouble()) - ln(defaults[i].frequencyHz.toDouble()))
                        if (d < bestDist) {
                            bestDist = d
                            bestSlot = i
                        }
                    }
                    defaults[bestSlot] = band
                    usedSlots.add(bestSlot)
                }
                defaults.sortedBy { it.frequencyHz }
            }
            else -> {
                // Keep 10 strongest-impact filters sorted by frequency
                sorted.sortedByDescending { abs(it.gainDb) }
                    .take(10)
                    .sortedBy { it.frequencyHz }
            }
        }

        return ParsedAutoEqResult(
            name = customName.ifBlank { "Custom AutoEQ" },
            preampDb = preamp,
            bands = tenBands,
            parsedFilterCount = parsed.size
        )
    }
}
