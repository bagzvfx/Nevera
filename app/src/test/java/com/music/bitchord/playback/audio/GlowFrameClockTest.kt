package com.music.bitchord.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glow's media→wall scheduling: analyzed frames must be published when
 * their media position is *heard*, spaced as the music is spaced, no matter
 * how bursty the DSP block delivery is.
 */
class GlowFrameClockTest {

    private val ms = 1_000_000L

    @Test
    fun `first block is heard one compensation after it arrives`() {
        val clock = GlowFrameClock()
        val ready = clock.readyTimeNs(
            blockStartMediaNs = 0L,
            blockEndMediaNs = 80 * ms,
            arrivalWallNs = 1_000 * ms,
            compensationNs = 200 * ms,
        )
        // Middle of the block: arrival + compensation + 40ms.
        assertEquals(1_240 * ms, ready)
    }

    @Test
    fun `a burst keeps media spacing instead of collapsing onto one instant`() {
        val clock = GlowFrameClock()
        val arrival = 1_000 * ms
        val comp = 200 * ms
        // Three 80ms blocks delivered in the same wall-clock instant.
        val r0 = clock.readyTimeNs(0L, 80 * ms, arrival, comp)
        val r1 = clock.readyTimeNs(80 * ms, 160 * ms, arrival, comp)
        val r2 = clock.readyTimeNs(160 * ms, 240 * ms, arrival, comp)
        assertEquals(80 * ms, r1 - r0)
        assertEquals(80 * ms, r2 - r1)
        assertTrue(r2 > arrival + comp)
    }

    @Test
    fun `steady real-time delivery stays on the anchor`() {
        val clock = GlowFrameClock()
        val comp = 150 * ms
        var arrival = 0L
        var media = 0L
        var previousReady = Long.MIN_VALUE
        repeat(20) {
            val ready = clock.readyTimeNs(media, media + 40 * ms, arrival, comp)
            assertEquals(arrival + comp + 20 * ms, ready)
            assertTrue(ready > previousReady)
            previousReady = ready
            arrival += 40 * ms
            media += 40 * ms
        }
    }

    @Test
    fun `seek backwards re-anchors instead of projecting into the past`() {
        val clock = GlowFrameClock()
        val comp = 100 * ms
        clock.readyTimeNs(0L, 40 * ms, 0L, comp)
        clock.readyTimeNs(40 * ms, 80 * ms, 40 * ms, comp)
        // Seek back to the start, delivered much later in wall time.
        val ready = clock.readyTimeNs(0L, 40 * ms, 5_000 * ms, comp)
        assertEquals(5_000 * ms + comp + 20 * ms, ready)
    }

    @Test
    fun `starved delivery re-anchors rather than dumping stale frames`() {
        val clock = GlowFrameClock()
        val comp = 100 * ms
        clock.readyTimeNs(0L, 40 * ms, 0L, comp)
        // Next block arrives 2s late (delivery starved / playback stalled):
        // its projection on the old anchor is far in the past, so the clock
        // must re-anchor to arrival + compensation.
        val ready = clock.readyTimeNs(40 * ms, 80 * ms, 2_000 * ms, comp)
        assertEquals(2_000 * ms + comp + 20 * ms, ready)
    }

    @Test
    fun `collapsed compensation re-anchors instead of holding frames for seconds`() {
        val clock = GlowFrameClock()
        // Float route: 4s of sink lead.
        clock.readyTimeNs(0L, 40 * ms, 0L, 4_000 * ms)
        clock.readyTimeNs(40 * ms, 80 * ms, 40 * ms, 4_000 * ms)
        // Route rebuilt with a shallow buffer: compensation is now 50ms.
        // The old anchor would publish this block ~4s late; re-anchor.
        val ready = clock.readyTimeNs(80 * ms, 120 * ms, 80 * ms, 50 * ms)
        assertEquals(80 * ms + 50 * ms + 20 * ms, ready)
    }

    @Test
    fun `anchor changes are reported exactly once so stale frames can be dropped`() {
        val clock = GlowFrameClock()
        clock.readyTimeNs(0L, 40 * ms, 0L, 100 * ms)
        assertTrue(clock.consumeAnchorChange())
        // Steady-state blocks do not re-anchor.
        clock.readyTimeNs(40 * ms, 80 * ms, 40 * ms, 100 * ms)
        assertTrue(!clock.consumeAnchorChange())
        // A seek re-anchors and is reported again.
        clock.readyTimeNs(0L, 40 * ms, 5_000 * ms, 100 * ms)
        assertTrue(clock.consumeAnchorChange())
    }

    @Test
    fun `fraction zero stamps the block start and fraction one the block end`() {
        val comp = 200 * ms
        val atStart = GlowFrameClock().readyTimeNsAtFraction(0L, 80 * ms, 0f, 1_000 * ms, comp)
        assertEquals(1_000 * ms + comp, atStart)
        val atEnd = GlowFrameClock().readyTimeNsAtFraction(0L, 80 * ms, 1f, 1_000 * ms, comp)
        assertEquals(1_000 * ms + comp + 80 * ms, atEnd)
        // Fraction 0.5 is exactly the legacy middle stamp.
        val atMid = GlowFrameClock().readyTimeNsAtFraction(0L, 80 * ms, 0.5f, 1_000 * ms, comp)
        assertEquals(1_000 * ms + comp + 40 * ms, atMid)
    }

    @Test
    fun `peak offset moves an onset stamp off the block middle to the transient`() {
        val clock = GlowFrameClock()
        val comp = 200 * ms
        // Kick lands in the first ~10ms sub-window of an 85ms block: the
        // middle stamp would fire ~37ms after the transient is heard.
        // 0.06 × 85ms = 5.1ms; 0.94 × 85ms = 79.9ms (ns-exact expectations).
        val ready = clock.readyTimeNsAtFraction(0L, 85 * ms, 0.06f, 1_000 * ms, comp)
        assertEquals(1_000 * ms + comp + 5_100_000L, ready)
        // A late transient stamps late by the same logic.
        val clock2 = GlowFrameClock()
        val readyLate = clock2.readyTimeNsAtFraction(0L, 85 * ms, 0.94f, 1_000 * ms, comp)
        assertEquals(1_000 * ms + comp + 79_899_999L, readyLate)
    }

    @Test
    fun `peak stamped burst keeps media spacing between onsets`() {
        val clock = GlowFrameClock()
        val arrival = 1_000 * ms
        val comp = 200 * ms
        val r0 = clock.readyTimeNsAtFraction(0L, 80 * ms, 0.25f, arrival, comp)
        val r1 = clock.readyTimeNsAtFraction(80 * ms, 160 * ms, 0.25f, arrival, comp)
        assertEquals(80 * ms, r1 - r0)
    }

    @Test
    fun `out of range fractions are clamped to the block span`() {
        val comp = 100 * ms
        val under = GlowFrameClock().readyTimeNsAtFraction(0L, 80 * ms, -0.5f, 0L, comp)
        assertEquals(comp, under)
        val over = GlowFrameClock().readyTimeNsAtFraction(0L, 80 * ms, 1.5f, 0L, comp)
        assertEquals(comp + 80 * ms, over)
    }

    @Test
    fun `reset makes the next block a fresh anchor`() {
        val clock = GlowFrameClock()
        clock.readyTimeNs(0L, 40 * ms, 0L, 100 * ms)
        clock.reset()
        val ready = clock.readyTimeNs(500 * ms, 540 * ms, 900 * ms, 100 * ms)
        assertEquals(900 * ms + 100 * ms + 20 * ms, ready)
    }
}
