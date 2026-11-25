package com.music.bitchord.data.lyrics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
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

        // Word boundaries first, from the cumulative weights, so every word
        // owns exactly one slice of the vocal span and the slices tile it in
        // order. The previous per-word build let a short word's end be pushed
        // at least 70 ms out even when its own slice was shorter — past the
        // next word's start — so on fast lines neighbouring words overlapped
        // and the sweep stuttered back and forth between them (0.1-beta).
        val boundaries = LongArray(tokens.size + 1)
        boundaries[0] = start
        var cursor = start.toFloat()
        for (i in tokens.indices) {
            cursor += (weights[i] / totalWeight) * vocalSpanMs
            boundaries[i + 1] = if (i == tokens.lastIndex) start + vocalSpanMs else cursor.toLong()
        }
        val words = ArrayList<LyricWord>(tokens.size)
        for (i in tokens.indices) {
            val wStart = boundaries[i]
            val sliceEnd = boundaries[i + 1].coerceAtLeast(wStart + 1L)
            // Sung for 96% of its slice, resting on the boundary for the rest —
            // but never past its own slice, however short the slice is.
            val wEnd = (wStart + (sliceEnd - wStart) * 0.96f).toLong().coerceIn(wStart + 1L, sliceEnd)
            words += LyricWord(text = tokens[i], startMs = wStart, endMs = wEnd)
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
                return start + legatoEase(rawT, sustainBlend) * word.text.length
            }
            val next = line.words.getOrNull(index + 1)
            if (next != null && positionMs < next.startMs) {
                val gapStart = line.text.indexOf(next.text, end).takeIf { it >= 0 } ?: end
                val pause = (next.startMs - word.endMs).coerceAtLeast(1L)
                val through = ((positionMs - word.endMs).toFloat() / pause).coerceIn(0f, 1f)
                return end + legatoEase(through, GAP_EASE_BLEND) * (gapStart - end)
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

    /**
     * Linear motion softened by a smoothstep: [blend] of the ease, the rest
     * straight through.
     *
     * A full smoothstep has zero slope at both ends. Within a word that is a
     * gentle settle, but across an inter-word pause it made the sweep stall
     * on the last letter, creep across the gap, then lurch into the next
     * word — a surge at every word boundary instead of one glide. Blending
     * keeps the ends moving at (1 − blend) of full pace, so the leading edge
     * never actually stops between words.
     */
    fun legatoEase(t: Float, blend: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val b = blend.coerceIn(0f, 1f)
        val smooth = x * x * (3f - 2f * x)
        return x * (1f - b) + smooth * b
    }

    /** How much of the inter-word gap's ease is smoothstep; see [legatoEase]. */
    private const val GAP_EASE_BLEND = 0.45f

    /**
     * Alpha multipliers for a glow mask window across a span, as (offset,
     * multiplier) gradient stops: 0 at both ends, 1 across the middle,
     * ramping over [featherFraction] of the span at each end.
     *
     * A glow masked by a plain rectangle keeps the rectangle's hard edges —
     * the blur laid over it can only soften them by its own radius, and the
     * halo reads as blocks of light behind the letters (the "pixelated"
     * glow). Every glow mask in the player is feathered with this window
     * before it is blurred, so the mask itself is already a gradient and no
     * rectangular step survives for the blur to show.
     */
    fun glowWindowStops(featherFraction: Float): List<Pair<Float, Float>> {
        val f = featherFraction.coerceIn(0f, 0.5f)
        return when {
            f <= 0.001f -> listOf(0f to 1f, 1f to 1f)
            f >= 0.499f -> listOf(0f to 0f, 0.5f to 1f, 1f to 0f)
            else -> listOf(0f to 0f, f to 1f, 1f - f to 1f, 1f to 0f)
        }
    }

    /**
     * Alpha multipliers for a glow that is lit from its start and fades out
     * over the last [featherFraction] of its span — the leading edge of the
     * best-part aura, which follows the sweep and must not end on a cut.
     */
    fun glowLeadStops(featherFraction: Float): List<Pair<Float, Float>> {
        val f = featherFraction.coerceIn(0f, 1f)
        return when {
            f <= 0.001f -> listOf(0f to 1f, 1f to 1f)
            f >= 0.999f -> listOf(0f to 1f, 1f to 0f)
            else -> listOf(0f to 1f, 1f - f to 1f, 1f to 0f)
        }
    }

    /**
     * How brightly one letter radiates at the sweep's current position — the
     * per-glyph glow model. Every letter of the line being sung carries its
     * own halo, so the light must be a property of the letter, not of a mask
     * laid over the line: the earlier word/row window masks, however
     * feathered, still moved as blocks of light behind the words.
     *
     * A continuous function of [revealedChars] (the fractional sweep head,
     * from [smartRevealedChars]) and of the letter's own state, so the
     * radiance travels letter by letter with no steps:
     *
     * - **Attack**: the letter lights as the sweep crosses it — a smoothstep
     *   beginning [GLYPH_GLOW_ATTACK_LEAD] letters ahead of its centre and
     *   completing just past it, so the peak lands on the crossing.
     * - **Trail**: once crossed, the sweep's contribution decays
     *   exponentially with the letters left behind it, down to a low ember
     *   ([GLYPH_GLOW_TRAIL_FLOOR]) that lasts while the line is active.
     * - **Hold**: a letter of a word being carried ([holdEnvelope], from
     *   [vocalHoldEnvelope]) burns at up to full brightness for as long as
     *   the note is held — but only once the sweep has reached it; a held
     *   note never lights a letter the singer hasn't got to.
     * - **Letter bloom**: a growing word's per-letter swell ([letterBloom],
     *   from [GrowingWord.sampleInto]) can light its letters directly.
     *
     * [glyphCenter] is the letter's fractional index (its index + 0.5).
     * [beatBoost] is the live bass energy, admitted only on a best part,
     * where the whole line already pulses with the music.
     */
    fun glyphGlowBrightness(
        glyphCenter: Float,
        revealedChars: Float,
        holdEnvelope: Float,
        letterBloom: Float = 0f,
        beatBoost: Float = 0f,
        isBestPart: Boolean = false,
    ): Float {
        val attackT = ((revealedChars - (glyphCenter - GLYPH_GLOW_ATTACK_LEAD)) /
            GLYPH_GLOW_ATTACK_SPAN).coerceIn(0f, 1f)
        if (attackT <= 0f && letterBloom <= 0f) return 0f
        val attack = attackT * attackT * (3f - 2f * attackT)
        val behind = (revealedChars - glyphCenter).coerceAtLeast(0f)
        val trail = exp(-behind * GLYPH_GLOW_TRAIL_DECAY)
        val sweep = attack * (GLYPH_GLOW_TRAIL_FLOOR + (1f - GLYPH_GLOW_TRAIL_FLOOR) * trail)
        val sweepWeight = if (isBestPart) GLYPH_GLOW_SWEEP_WEIGHT_BEST else GLYPH_GLOW_SWEEP_WEIGHT
        var brightness = maxOf(
            sweep * sweepWeight,
            attack * holdEnvelope.coerceIn(0f, 1f),
        )
        if (isBestPart) brightness += attack * beatBoost.coerceIn(0f, 1f) * 0.18f
        brightness = maxOf(brightness, letterBloom.coerceIn(0f, 1f) * 0.95f)
        return brightness.coerceIn(0f, 1f)
    }

    /** How far ahead of a letter's centre its glow attack begins, in letters. */
    private const val GLYPH_GLOW_ATTACK_LEAD = 1.1f

    /** How many letters the glow attack spans, from first light to full. */
    private const val GLYPH_GLOW_ATTACK_SPAN = 1.5f

    /** Exponential fall of the sweep's light per letter left behind the head. */
    private const val GLYPH_GLOW_TRAIL_DECAY = 0.42f

    /** The ember a sung letter keeps while its line is still the active one. */
    private const val GLYPH_GLOW_TRAIL_FLOOR = 0.16f

    private const val GLYPH_GLOW_SWEEP_WEIGHT = 0.78f
    private const val GLYPH_GLOW_SWEEP_WEIGHT_BEST = 0.95f

    /** True for Arabic / joined cursive scripts where per-glyph slicing would break ligatures. */
    fun hasJoinedScript(text: String): Boolean = text.any { ch ->
        val c = ch.code
        c in 0x0600..0x06FF || c in 0x0750..0x077F || c in 0x08A0..0x08FF ||
            c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFF
    }
}
