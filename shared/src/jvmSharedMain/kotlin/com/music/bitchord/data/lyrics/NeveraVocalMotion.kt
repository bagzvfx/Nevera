package com.music.bitchord.data.lyrics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unified smart vocal-duration adaptive timing, automatic line-to-word karaoke
 * synthesis, and C²-smooth raised-cosine wave physics shared identically across
 * Playback ([PlayerLyrics]), Borderless Floating Overlay
 * ([FloatingLyricsOverlayService]), and Share Lyrics Video ([LyricsCardPainter]).
 */
object NeveraVocalMotion {

    /**
     * Ensures every synced [LyricLine] has silky word/syllable timings for
     * karaoke sweep, wave buoyancy, and vocal-hold glow.
     *
     * - If the provider already supplied word timings ([LyricLine.isWordSynced]),
     *   returns [line] untouched.
     * - If only line-level LRC timestamps exist, synthesizes natural vocal-weighted
     *   word (or CJK/Kana/Hangul character-group) timings spanning ~86% of the
     *   line's slot so singing finishes gracefully before the next line arrives,
     *   with extra sustain weight on vowels and the final note of the phrase.
     */
    fun ensureKaraokeTiming(line: LyricLine, nextLineTimeMs: Long?): LyricLine {
        if (line.isWordSynced || line.isGap || line.text.isBlank()) return line
        val start = line.timeMs.coerceAtLeast(0L)
        val rawSlot = when {
            line.sungUntilMs != null && line.sungUntilMs > start + 250L -> line.sungUntilMs - start
            nextLineTimeMs != null && nextLineTimeMs > start + 250L -> nextLineTimeMs - start
            else -> 3_400L
        }
        val slotMs = rawSlot.coerceIn(1_100L, 7_600L)
        val vocalSpanMs = (slotMs * 0.86f).toLong().coerceAtLeast(850L)

        val tokens = tokenizeForKaraoke(line.text)
        if (tokens.isEmpty()) return line

        val weights = FloatArray(tokens.size) { idx ->
            val tok = tokens[idx]
            val len = tok.length.coerceAtLeast(1).toFloat()
            val vowels = tok.count { it.lowercaseChar() in "aeiouyāīūēō" }.toFloat()
            val base = (len * 0.72f + vowels * 0.45f).coerceAtLeast(1.4f)
            if (idx == tokens.lastIndex) base * 1.52f else base
        }
        val totalWeight = weights.sum().coerceAtLeast(1f)

        var cursor = start.toFloat()
        val words = ArrayList<LyricWord>(tokens.size)
        for (i in tokens.indices) {
            val dur = (weights[i] / totalWeight) * vocalSpanMs
            val wStart = cursor.toLong()
            val wEnd = (cursor + dur * 0.96f).toLong().coerceAtLeast(wStart + 70L)
            words += LyricWord(text = tokens[i], startMs = wStart, endMs = wEnd)
            cursor += dur
        }
        return line.copy(
            words = words,
            sungUntilMs = words.lastOrNull()?.endMs ?: (start + vocalSpanMs),
        )
    }

    private fun tokenizeForKaraoke(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (' ' in trimmed) {
            return trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        // Space-less scripts (Japanese/Chinese/etc.): group into smooth 2-char rhythmic beats
        val out = ArrayList<String>()
        var i = 0
        while (i < trimmed.length) {
            val end = (i + 2).coerceAtMost(trimmed.length)
            out += trimmed.substring(i, end)
            i = end
        }
        return out
    }

    /**
     * Smooth fractional character sweep index with natural legato pacing across
     * held vowels and inter-word pauses.
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
                // Gentle organic ease-in-out proportional to vocal hold length
                val sustainBlend = ((heldMs - 340f) / 680f).coerceIn(0.08f, 0.34f)
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
     */
    fun sustainStrength(word: LyricWord): Float {
        val heldMs = (word.endMs - word.startMs).coerceAtLeast(1L).toFloat()
        val len = word.text.length.coerceAtLeast(1)
        val msPerChar = heldMs / len
        val byTotal = ((heldMs - 320f) / 900f).coerceIn(0f, 1f)
        val byPace = ((msPerChar - 80f) / 170f).coerceIn(0f, 1f)
        val combined = (byTotal * 0.65f + byPace * 0.35f).coerceIn(0f, 1f)
        return combined * combined * (3f - 2f * combined)
    }

    /**
     * Smooth live envelope [0f..1f] of a word's vocal sustain at [positionMs],
     * rising gracefully into held notes and settling cleanly over 520–820 ms.
     */
    fun vocalHoldEnvelope(word: LyricWord, positionMs: Long): Float {
        val strength = sustainStrength(word)
        if (strength <= 0.03f) return 0f
        val heldMs = (word.endMs - word.startMs).coerceAtLeast(1L).toFloat()
        val attackMs = (heldMs * 0.32f).coerceIn(130f, 340f)
        val releaseMs = 520f + strength * 320f
        val rise = ((positionMs - word.startMs) / attackMs).coerceIn(0f, 1f)
        val fall = (1f - (positionMs - word.endMs) / releaseMs).coerceIn(0f, 1f)
        val env = minOf(rise, fall)
        if (env <= 0.001f) return 0f
        val smoothEnv = env * env * (3f - 2f * env)
        val breath = if (heldMs >= 800f && positionMs in word.startMs..word.endMs) {
            val phase = ((positionMs - word.startMs) / 400.0) * PI
            1f + 0.065f * strength * sin(phase).toFloat()
        } else {
            1f
        }
        return (strength * smoothEnv * breath).coerceIn(0f, 1f)
    }

    /**
     * C²-smooth raised-cosine liquid wave buoyancy [0f..1.65f] for character at
     * [charIndex] inside [word], gliding effortlessly along [revealedChars].
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
        // Broad velvet wave radius (widens further when the singer holds a note!)
        val radius = 2.6f + sustain * 1.4f
        val norm = (dist / radius).coerceIn(-1f, 1f)
        val raisedCos = 0.5f * (1f + cos(norm * PI).toFloat())
        val crest = raisedCos * raisedCos // C²-smooth silk curve with zero hard edges

        val tail = (1f - (positionMs - word.endMs) / (420f + sustain * 320f)).coerceIn(0f, 1f)
        val lead = ((positionMs - word.startMs + 85f) / 160f).coerceIn(0f, 1f)
        val envelope = minOf(lead, tail).let { it * it * (3f - 2f * it) }

        val amplitude = 0.68f + sustain * 0.62f + if (isBestPart) 0.28f else 0f
        return (crest * amplitude * envelope).coerceIn(0f, 1.65f)
    }

    /** True for Arabic / joined cursive scripts where per-glyph slicing would break ligatures. */
    fun hasJoinedScript(text: String): Boolean = text.any { ch ->
        val c = ch.code
        c in 0x0600..0x06FF || c in 0x0750..0x077F || c in 0x08A0..0x08FF ||
            c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFF
    }
}
