package com.music.bitchord.data.lyrics

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Unified smart vocal-duration adaptive timing and glitch-free karaoke wave
 * physics shared identically across Playback ([PlayerLyrics]), Borderless
 * Floating Overlay ([FloatingLyricsOverlayService]), and Share Lyrics Video
 * ([LyricsCardPainter]).
 *
 * Adapts sweep smoothness, vertical buoyancy, wave width, and vocal bloom to
 * how long the singer actually sustains each note/word:
 * - Rapid syllables (<320 ms): crisp, effortless flow without twitching.
 * - Melodic held notes (450..900 ms): velvety buoyant lift + warm subtle glow.
 * - Long belted/sustained notes (>=900 ms): broad breathing wave + sustained
 *   luminous halo that lingers and settles smoothly after the note releases.
 */
object NeveraVocalMotion {

    /**
     * Smooth fractional character sweep index with natural vocal-hold easing.
     * Leaves [LyricLine.revealedChars] untouched for deterministic unit tests.
     */
    fun smartRevealedChars(line: LyricLine, positionMs: Long): Float {
        line.timingSource?.let { source ->
            if (source.text.isEmpty()) return 0f
            return (smartRevealedChars(source, positionMs) / source.text.length)
                .coerceIn(0f, 1f) * line.text.length
        }
        if (line.words.isEmpty()) {
            return if (positionMs >= line.timeMs) line.text.length.toFloat() else 0f
        }
        var offset = 0
        line.words.forEachIndexed { index, word ->
            val start = line.text.indexOf(word.text, offset).takeIf { it >= 0 } ?: offset
            val end = start + word.text.length
            if (positionMs < word.startMs) return start.toFloat()
            if (positionMs < word.endMs) {
                if (word.syllables.isNotEmpty()) {
                    return start + word.charsSungAt(positionMs)
                }
                val heldMs = (word.endMs - word.startMs).coerceAtLeast(1L).toFloat()
                val rawT = ((positionMs - word.startMs) / heldMs).coerceIn(0f, 1f)
                // For sustained single-span vocal holds (>=480 ms), blend subtle S-curve
                // breathing so the sweep glides organically across held vowels.
                val sustainBlend = ((heldMs - 420f) / 700f).coerceIn(0f, 0.28f)
                val smoothT = rawT * rawT * (3f - 2f * rawT)
                val easedT = rawT * (1f - sustainBlend) + smoothT * sustainBlend
                return start + easedT * word.text.length
            }
            val next = line.words.getOrNull(index + 1)
            if (next != null && positionMs < next.startMs) {
                val gapStart = line.text.indexOf(next.text, end).takeIf { it >= 0 } ?: end
                val pause = (next.startMs - word.endMs).coerceAtLeast(1L)
                val through = ((positionMs - word.endMs).toFloat() / pause).coerceIn(0f, 1f)
                val easedGap = through * through * (3f - 2f * through)
                return end + easedGap * (gapStart - end)
            }
            offset = end
        }
        return line.text.length.toFloat()
    }

    /**
     * How strongly [word] is being vocally sustained, in [0f..1f].
     * Combines total note duration and time-per-character so both long single-vowel
     * holds ("ohhh") and stretched melodic words ("for-e-ver") register naturally.
     */
    fun sustainStrength(word: LyricWord): Float {
        val heldMs = (word.endMs - word.startMs).coerceAtLeast(1L).toFloat()
        val len = word.text.length.coerceAtLeast(1)
        val msPerChar = heldMs / len
        val byTotal = ((heldMs - 340f) / 960f).coerceIn(0f, 1f)
        val byPace = ((msPerChar - 85f) / 180f).coerceIn(0f, 1f)
        val combined = (byTotal * 0.65f + byPace * 0.35f).coerceIn(0f, 1f)
        return combined * combined * (3f - 2f * combined)
    }

    /**
     * Smooth live envelope [0f..1f] of a word's vocal sustain at [positionMs],
     * rising gracefully into the held note and settling cleanly over 550–780 ms.
     */
    fun vocalHoldEnvelope(word: LyricWord, positionMs: Long): Float {
        val strength = sustainStrength(word)
        if (strength <= 0.04f) return 0f
        val heldMs = (word.endMs - word.startMs).coerceAtLeast(1L).toFloat()
        val attackMs = (heldMs * 0.35f).coerceIn(140f, 360f)
        val releaseMs = 480f + strength * 320f
        val rise = ((positionMs - word.startMs) / attackMs).coerceIn(0f, 1f)
        val fall = (1f - (positionMs - word.endMs) / releaseMs).coerceIn(0f, 1f)
        val env = minOf(rise, fall)
        if (env <= 0.001f) return 0f
        val smoothEnv = env * env * (3f - 2f * env)
        // Subtle breathing micro-vibration on very long vocal holds (>=900ms)
        val breath = if (heldMs >= 850f && positionMs in word.startMs..word.endMs) {
            val phase = ((positionMs - word.startMs) / 420.0) * PI
            1f + 0.06f * strength * sin(phase).toFloat()
        } else {
            1f
        }
        return (strength * smoothEnv * breath).coerceIn(0f, 1f)
    }

    /**
     * Normalized vertical wave buoyancy [0f..1.6f] for character at [charIndex]
     * inside [word], riding cleanly along [revealedChars].
     *
     * Widens and deepens organically when the vocalist holds a note longer,
     * and stays gentle and crisp on fast patter so text never jitters.
     */
    fun charWaveBuoyancy(
        charIndex: Int,
        revealedChars: Float,
        word: LyricWord,
        positionMs: Long,
        isBestPart: Boolean = false,
    ): Float {
        val sustain = sustainStrength(word)
        val dist = (charIndex + 0.5f) - revealedChars
        // Held vocal notes spread their wave across more neighbouring letters softly
        val widthFactor = 0.42f - sustain * 0.16f // wider Gaussian for held notes
        val crest = if (dist in -4.2f..2.6f) {
            exp((-dist * dist * widthFactor).toDouble()).toFloat()
        } else {
            0f
        }
        // Smooth fade-out after word ends so wave never snaps off abruptly
        val tail = (1f - (positionMs - word.endMs) / (360f + sustain * 300f)).coerceIn(0f, 1f)
        val lead = ((positionMs - word.startMs + 60f) / 140f).coerceIn(0f, 1f)
        val envelope = minOf(lead, tail).let { it * it * (3f - 2f * it) }

        val amplitude = 0.58f + sustain * 0.62f + if (isBestPart) 0.28f else 0f
        return (crest * amplitude * envelope).coerceIn(0f, 1.55f)
    }

    /** True for Arabic / joined cursive scripts where per-glyph slicing would break ligatures. */
    fun hasJoinedScript(text: String): Boolean = text.any { ch ->
        val c = ch.code
        c in 0x0600..0x06FF || c in 0x0750..0x077F || c in 0x08A0..0x08FF ||
            c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFF
    }
}
