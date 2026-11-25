package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RhythmCompositeTest {

    @Test
    fun `silence stays silent`() {
        assertEquals(0f, RhythmComposite.of(bass = 0f, treble = 0f, sustain = 0f, beat = 0f))
    }

    @Test
    fun `weights sum to one so full scale in gives full scale out`() {
        assertEquals(
            1f,
            RhythmComposite.of(bass = 1f, treble = 1f, sustain = 1f, beat = 1f),
            0.0001f,
        )
    }

    @Test
    fun `a bass-light but busy passage still moves the waveform`() {
        // No bass at all, but a sustained busy texture with treble activity:
        // the old bass-only ribbon sat flat here.
        val rhythm = RhythmComposite.of(bass = 0f, treble = 0.8f, sustain = 0.9f, beat = 0.2f)
        assertTrue(rhythm > 0.4f, "expected composite > 0.4 without any bass, was $rhythm")
    }

    @Test
    fun `sustain is the body and outweighs treble shimmer`() {
        val sustainOnly = RhythmComposite.of(bass = 0f, treble = 0f, sustain = 1f, beat = 0f)
        val trebleOnly = RhythmComposite.of(bass = 0f, treble = 1f, sustain = 0f, beat = 0f)
        assertEquals(RhythmComposite.SUSTAIN_WEIGHT, sustainOnly, 0.0001f)
        assertEquals(RhythmComposite.TREBLE_WEIGHT, trebleOnly, 0.0001f)
        assertTrue(sustainOnly > trebleOnly)
    }

    @Test
    fun `a beat spike lifts the composite above its sustained floor`() {
        val floor = RhythmComposite.of(bass = 0.4f, treble = 0.2f, sustain = 0.5f, beat = 0f)
        val hit = RhythmComposite.of(bass = 0.4f, treble = 0.2f, sustain = 0.5f, beat = 1f)
        assertTrue(hit > floor)
        assertEquals(RhythmComposite.BEAT_WEIGHT, hit - floor, 0.0001f)
    }

    @Test
    fun `out of range inputs are clamped`() {
        assertEquals(1f, RhythmComposite.of(bass = 2f, treble = 2f, sustain = 2f, beat = 2f))
        assertEquals(0f, RhythmComposite.of(bass = -1f, treble = -1f, sustain = -1f, beat = -1f))
    }
}
