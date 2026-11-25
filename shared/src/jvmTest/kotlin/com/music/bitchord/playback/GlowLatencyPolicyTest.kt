package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlowLatencyPolicyTest {

    @Test
    fun `measured sink lead wins over track buffer and legacy table`() {
        assertEquals(
            480L,
            GlowLatencyPolicy.resolveCompensationMs(
                measuredLeadMs = 460L,
                trackBufferMs = 250L,
                routeTailMs = 20L,
                legacyTotalMs = 65L,
            ),
        )
    }

    @Test
    fun `track buffer is the stand-in when no live lead is published`() {
        assertEquals(
            270L,
            GlowLatencyPolicy.resolveCompensationMs(
                measuredLeadMs = null,
                trackBufferMs = 250L,
                routeTailMs = 20L,
                legacyTotalMs = 65L,
            ),
        )
    }

    @Test
    fun `legacy route total is the last resort`() {
        assertEquals(
            210L,
            GlowLatencyPolicy.resolveCompensationMs(
                measuredLeadMs = null,
                trackBufferMs = null,
                routeTailMs = 140L,
                legacyTotalMs = 210L,
            ),
        )
    }

    @Test
    fun `multi-second float-route leads survive but stay clamped`() {
        assertEquals(
            GlowLatencyPolicy.MAX_COMPENSATION_MS,
            GlowLatencyPolicy.resolveCompensationMs(
                measuredLeadMs = 9_500L,
                trackBufferMs = null,
                routeTailMs = 20L,
                legacyTotalMs = 65L,
            ),
        )
    }

    @Test
    fun `negative inputs never produce a negative compensation`() {
        assertEquals(
            0L,
            GlowLatencyPolicy.resolveCompensationMs(
                measuredLeadMs = -50L,
                trackBufferMs = null,
                routeTailMs = -10L,
                legacyTotalMs = 65L,
            ),
        )
    }

    @Test
    fun `track buffer duration converts bytes to milliseconds`() {
        // 48 kHz float stereo: 4096 frames of buffer = ~85 ms.
        assertEquals(
            85L,
            GlowLatencyPolicy.trackBufferDurationMs(
                bufferSizeBytes = 4096 * 4 * 2,
                sampleRateHz = 48_000,
                bytesPerSample = 4,
                channelCount = 2,
            ),
        )
        // Media3's float-route AudioTrack can hold ~4 s: 48 kHz 16-bit stereo.
        assertEquals(
            4_000L,
            GlowLatencyPolicy.trackBufferDurationMs(
                bufferSizeBytes = 192_000 * 4,
                sampleRateHz = 48_000,
                bytesPerSample = 2,
                channelCount = 2,
            ),
        )
    }

    @Test
    fun `track buffer duration rejects unusable inputs`() {
        assertNull(GlowLatencyPolicy.trackBufferDurationMs(0, 48_000, 2, 2))
        assertNull(GlowLatencyPolicy.trackBufferDurationMs(4096, 0, 2, 2))
        assertNull(GlowLatencyPolicy.trackBufferDurationMs(4096, 48_000, 0, 2))
        assertNull(GlowLatencyPolicy.trackBufferDurationMs(4096, 48_000, 2, 0))
    }

    @Test
    fun `bytes per sample matches Android AudioFormat encodings`() {
        // android.media.AudioFormat constants.
        assertEquals(2, GlowLatencyPolicy.bytesPerSampleForEncoding(2)) // PCM_16BIT
        assertEquals(1, GlowLatencyPolicy.bytesPerSampleForEncoding(3)) // PCM_8BIT
        assertEquals(4, GlowLatencyPolicy.bytesPerSampleForEncoding(4)) // PCM_FLOAT
        assertEquals(3, GlowLatencyPolicy.bytesPerSampleForEncoding(21)) // PCM_24BIT_PACKED
        assertEquals(4, GlowLatencyPolicy.bytesPerSampleForEncoding(22)) // PCM_32BIT
    }
}
