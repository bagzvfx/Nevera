package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NeveraVocalMotionTest {

    @Test
    fun `synthesized word timings are ordered and never overlap`() {
        // A fast line: many short words in a tight slot is where the old
        // per-word build pushed ends past the next word's start.
        val line = LyricLine(
            timeMs = 10_000L,
            text = "a b c d e f g h i j k l m n o p q r s t",
        )
        val timed = NeveraVocalMotion.ensureKaraokeTiming(line, nextLineTimeMs = 11_200L)
        assertTrue(timed.isWordSynced)
        var previousEnd = Long.MIN_VALUE
        for (word in timed.words) {
            assertTrue(word.startMs >= previousEnd, "word '${word.text}' starts before the previous word ends")
            assertTrue(word.endMs > word.startMs, "word '${word.text}' has no duration")
            assertTrue(word.endMs <= 10_000L + 7_600L, "word '${word.text}' runs past the vocal span")
            previousEnd = word.endMs
        }
        assertEquals(timed.words.last().endMs, timed.sungUntilMs)
    }

    @Test
    fun `provider word timings are left untouched`() {
        val words = listOf(LyricWord(startMs = 0L, endMs = 500L, text = "hello"))
        val line = LyricLine(timeMs = 0L, text = "hello", words = words)
        assertEquals(line, NeveraVocalMotion.ensureKaraokeTiming(line, nextLineTimeMs = 2_000L))
    }

    @Test
    fun `sweep keeps moving through an inter-word pause`() {
        // Provider timings with a real pause between words: the old gap ease
        // was a full smoothstep, whose slope is zero at both ends, so the
        // sweep stalled on the last letter and lurched into the next word.
        val line = LyricLine(
            timeMs = 0L,
            text = "aa   bb",
            words = listOf(
                LyricWord(startMs = 0L, endMs = 500L, text = "aa"),
                LyricWord(startMs = 800L, endMs = 1_300L, text = "bb"),
            ),
        )
        val gapAdvanceStart = NeveraVocalMotion.smartRevealedChars(line, 530L) -
            NeveraVocalMotion.smartRevealedChars(line, 500L)
        val gapAdvanceEnd = NeveraVocalMotion.smartRevealedChars(line, 800L) -
            NeveraVocalMotion.smartRevealedChars(line, 770L)
        assertTrue(gapAdvanceStart >= 0.15f, "sweep stalls entering the pause: $gapAdvanceStart chars in 30 ms")
        assertTrue(gapAdvanceEnd >= 0.15f, "sweep stalls leaving the pause: $gapAdvanceEnd chars in 30 ms")
    }

    @Test
    fun `legato ease reaches its ends and never stalls at them`() {
        assertEquals(0f, NeveraVocalMotion.legatoEase(0f, 0.45f))
        assertEquals(1f, NeveraVocalMotion.legatoEase(1f, 0.45f))
        assertEquals(0f, NeveraVocalMotion.legatoEase(-1f, 0.45f))
        assertEquals(1f, NeveraVocalMotion.legatoEase(2f, 0.45f))
        // A full smoothstep covers only ~0.7% of the span in its first 5%;
        // the blend must keep the ends clearly moving.
        assertTrue(NeveraVocalMotion.legatoEase(0.05f, 0.45f) >= 0.02f)
        assertTrue(NeveraVocalMotion.legatoEase(0.95f, 0.45f) <= 0.98f)
        var previous = -1f
        var t = 0f
        while (t <= 1.001f) {
            val eased = NeveraVocalMotion.legatoEase(t, 0.45f)
            assertTrue(eased >= previous, "legato ease moved backwards at $t")
            previous = eased
            t += 0.05f
        }
    }

    @Test
    fun `glow window stops are sorted symmetric and clamped`() {
        for (fraction in listOf(0f, 0.1f, 0.25f, 0.5f, 0.9f, 2f)) {
            val stops = NeveraVocalMotion.glowWindowStops(fraction)
            assertEquals(0f, stops.first().first)
            assertEquals(1f, stops.last().first)
            assertEquals(1f, stops.maxOf { it.second })
            for (i in stops.indices) {
                assertTrue(stops[i].second in 0f..1f, "multiplier out of range at $fraction")
                if (i > 0) assertTrue(stops[i].first > stops[i - 1].first, "stops not sorted at $fraction")
                val mirror = stops[stops.lastIndex - i]
                assertEquals(stops[i].second, mirror.second, "window not symmetric at $fraction")
                assertEquals(stops[i].first, 1f - mirror.first, "window not symmetric at $fraction")
            }
            if (fraction > 0.001f) {
                assertEquals(0f, stops.first().second, "window must fade in from nothing")
                assertEquals(0f, stops.last().second, "window must fade out to nothing")
            }
        }
    }

    @Test
    fun `glow lead stops are lit from the start and fade only at the end`() {
        for (fraction in listOf(0.1f, 0.4f, 1f, 3f)) {
            val stops = NeveraVocalMotion.glowLeadStops(fraction)
            assertEquals(0f, stops.first().first)
            assertEquals(1f, stops.last().first)
            assertEquals(1f, stops.first().second, "lead glow starts lit")
            assertEquals(0f, stops.last().second, "lead glow ends faded")
            for (i in 1 until stops.size) {
                assertTrue(stops[i].first > stops[i - 1].first, "stops not sorted at $fraction")
                assertTrue(stops[i].second <= stops[i - 1].second, "lead glow brightens mid-span at $fraction")
            }
        }
        // No feather requested: fully lit across the whole span.
        assertTrue(NeveraVocalMotion.glowLeadStops(0f).all { it.second == 1f })
        assertTrue(NeveraVocalMotion.glowWindowStops(0f).all { it.second == 1f })
    }

    @Test
    fun `glyph glow is dark before the sweep arrives`() {
        // Centre of the 10th letter; the sweep is still letters away.
        assertEquals(0f, NeveraVocalMotion.glyphGlowBrightness(9.5f, 0f, 0f))
        assertEquals(0f, NeveraVocalMotion.glyphGlowBrightness(9.5f, 8.3f, 0f))
        // A held note must not light a letter the singer hasn't reached.
        assertEquals(0f, NeveraVocalMotion.glyphGlowBrightness(9.5f, 0f, 1f))
    }

    @Test
    fun `glyph glow peaks at the sweep crossing and decays behind it`() {
        val center = 9.5f
        val before = NeveraVocalMotion.glyphGlowBrightness(center, center - 3f, 0f)
        val atCrossing = NeveraVocalMotion.glyphGlowBrightness(center, center, 0f)
        val justPast = NeveraVocalMotion.glyphGlowBrightness(center, center + 0.4f, 0f)
        val wellPast = NeveraVocalMotion.glyphGlowBrightness(center, center + 8f, 0f)
        assertEquals(0f, before)
        assertTrue(atCrossing > 0.5f, "letter should be lit as the sweep crosses it: $atCrossing")
        assertTrue(justPast >= atCrossing, "peak lands on/just past the crossing: $atCrossing -> $justPast")
        assertTrue(wellPast < justPast * 0.4f, "light must decay behind the head: $justPast -> $wellPast")
        // Monotone decay once the attack has completed.
        var previous = Float.MAX_VALUE
        var revealed = center + 0.4f
        while (revealed <= center + 12f) {
            val brightness = NeveraVocalMotion.glyphGlowBrightness(center, revealed, 0f)
            assertTrue(brightness <= previous + 1e-4f, "glow brightened behind the head at $revealed")
            previous = brightness
            revealed += 0.5f
        }
    }

    @Test
    fun `glyph glow is continuous in the sweep position`() {
        // No steps: a hundredth of a letter of sweep movement may only move
        // the brightness by a small, bounded amount — for the plain sweep,
        // for a held letter, and on a best part with the bass admitted.
        for ((hold, best) in listOf(0f to false, 1f to false, 0.5f to true)) {
            var previous = NeveraVocalMotion.glyphGlowBrightness(20.5f, 10f, hold, isBestPart = best)
            var revealed = 10f
            while (revealed <= 40f) {
                val brightness = NeveraVocalMotion.glyphGlowBrightness(
                    20.5f, revealed, hold, beatBoost = 0.7f, isBestPart = best,
                )
                assertTrue(
                    kotlin.math.abs(brightness - previous) <= 0.03f,
                    "brightness stepped at $revealed (hold=$hold, best=$best): $previous -> $brightness",
                )
                previous = brightness
                revealed += 0.01f
            }
        }
    }

    @Test
    fun `glyph glow stays in bounds and a held letter burns brightest`() {
        var revealed = -5f
        while (revealed <= 60f) {
            for (hold in listOf(0f, 0.37f, 1f)) {
                val brightness = NeveraVocalMotion.glyphGlowBrightness(20.5f, revealed, hold)
                assertTrue(brightness in 0f..1f, "brightness out of bounds at $revealed/$hold: $brightness")
            }
            revealed += 0.25f
        }
        val center = 20.5f
        val plain = NeveraVocalMotion.glyphGlowBrightness(center, center + 0.4f, 0f)
        val held = NeveraVocalMotion.glyphGlowBrightness(center, center + 0.4f, 1f)
        assertTrue(held > plain, "a carried note must out-glow the plain sweep: $plain -> $held")
        assertTrue(held >= 0.99f, "a fully held letter at the crossing should be at full brightness: $held")
        // A growing word's own per-letter bloom lights its letters directly.
        assertTrue(NeveraVocalMotion.glyphGlowBrightness(center, 0f, 0f, letterBloom = 0.8f) >= 0.7f)
    }

    @Test
    fun `revealed chars move monotonically through a synthesized line`() {
        val line = NeveraVocalMotion.ensureKaraokeTiming(
            LyricLine(timeMs = 0L, text = "shine on through the night"),
            nextLineTimeMs = 4_000L,
        )
        var previous = -1f
        var at = 0L
        while (at <= 4_000L) {
            val revealed = NeveraVocalMotion.smartRevealedChars(line, at)
            assertTrue(revealed >= previous, "sweep moved backwards at ${at}ms: $previous -> $revealed")
            previous = revealed
            at += 37L
        }
        assertEquals(line.text.length.toFloat(), previous)
    }
}
