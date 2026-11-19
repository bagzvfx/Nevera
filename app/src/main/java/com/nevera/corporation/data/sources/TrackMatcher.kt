package com.nevera.corporation.data.sources

import com.nevera.corporation.data.model.Song
import kotlin.math.abs

/**
 * Matches tracks across different catalogues (Spotify, YouTube Music, JioSaavn)
 * by normalizing noisy suffixes and scoring title, artist, and duration similarity.
 */
object TrackMatcher {

    private val noisePattern = Regex(
        """\s*[\(\[]\s*(official\s*(music\s*)?(video|audio|lyric\s*video|visualizer)|lyrics?|audio|hd|hq|4k|explicit|clean|remastered(\s*\d{4})?|feat\.?[^)\]]*|ft\.?[^)\]]*)\s*[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val featSuffixPattern = Regex("""\s+(feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val nonAlphaNum = Regex("""[^\p{L}\p{N}\s]""")
    private val multiSpace = Regex("""\s+""")

    private val degradedVideoKeywords = listOf(
        "official music video", "music video", "official video", "mv", "m/v",
        "live", "concert", "cover", "karaoke", "instrumental",
        "slowed", "reverb", "nightcore", "sped up", "8d", "bass boosted", "remix"
    )
    private val masterAudioKeywords = listOf("official audio", "topic", "remastered", "studio")

    fun normalizeTitle(raw: String): String {
        var s = raw.trim()
        s = noisePattern.replace(s, "")
        s = featSuffixPattern.replace(s, "")
        s = nonAlphaNum.replace(s, " ")
        s = multiSpace.replace(s, " ").trim().lowercase()
        return s
    }

    fun normalizeArtist(raw: String): String {
        val primary = raw.split(",", "&", " x ", " feat.", " ft.", ignoreCase = true).firstOrNull().orEmpty()
        return nonAlphaNum.replace(primary, " ")
            .let { multiSpace.replace(it, " ").trim().lowercase() }
    }

    fun tokenSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val tokensA = a.split(" ").filter { it.isNotBlank() }.toSet()
        val tokensB = b.split(" ").filter { it.isNotBlank() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val union = tokensA.union(tokensB).size.toDouble()
        return intersection / union
    }

    fun scoreCandidate(
        targetTitle: String,
        targetArtist: String,
        targetDurationSec: Long,
        candidate: Song
    ): Double {
        val tTitle = normalizeTitle(targetTitle)
        val cTitle = normalizeTitle(candidate.title)
        val titleScore = tokenSimilarity(tTitle, cTitle)
        if (titleScore < 0.4) return 0.0

        val tArtist = normalizeArtist(targetArtist)
        val cArtist = normalizeArtist(candidate.artist)
        val artistScore = when {
            tArtist.isBlank() || cArtist.isBlank() -> 0.5
            cArtist.contains(tArtist) || tArtist.contains(cArtist) -> 1.0
            else -> tokenSimilarity(tArtist, cArtist)
        }
        if (artistScore < 0.25) return 0.0

        val durationScore = if (targetDurationSec > 0 && candidate.durationSeconds > 0) {
            val diff = abs(targetDurationSec - candidate.durationSeconds)
            when {
                diff <= 3 -> 1.0
                diff <= 8 -> 0.8
                diff <= 15 -> 0.4
                else -> 0.0
            }
        } else 0.7

        // Master Studio Quality Bias: prefer clean studio/lossless masters over degraded MV/cover/reupload variants
        val targetLower = targetTitle.lowercase()
        val candLower = "${candidate.title} ${candidate.artist}".lowercase()
        var qualityModifier = 0.0
        if (candidate.isLosslessHint) {
            qualityModifier += 0.14
        }
        for (kw in degradedVideoKeywords) {
            val wordBound = Regex("""\b${Regex.escape(kw)}\b""")
            if (wordBound.containsMatchIn(candLower) && !wordBound.containsMatchIn(targetLower)) {
                qualityModifier -= if (kw in setOf("cover", "karaoke", "slowed", "nightcore", "8d", "remix", "live")) {
                    0.22
                } else {
                    0.09
                }
            }
        }
        for (kw in masterAudioKeywords) {
            if (candLower.contains(kw)) {
                qualityModifier += 0.04
            }
        }

        return ((titleScore * 0.50) + (artistScore * 0.35) + (durationScore * 0.15) + qualityModifier)
            .coerceIn(0.0, 1.15)
    }

    fun pickBestMatch(
        targetTitle: String,
        targetArtist: String,
        targetDurationSec: Long,
        candidates: List<Song>,
        minScore: Double = 0.58
    ): Song? {
        return candidates
            .map { it to scoreCandidate(targetTitle, targetArtist, targetDurationSec, it) }
            .filter { it.second >= minScore }
            .maxByOrNull { it.second }
            ?.first
    }
}
