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
