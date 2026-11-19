package com.nevera.corporation.playback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.nevera.corporation.data.store.LibraryStore
import java.util.concurrent.atomic.AtomicLong

/**
 * Beat-reactive Music Haptics triggered by SpectrumAudioBus bass onset impulses.
 * Respects user settings toggle and enforces a smooth cooldown so haptics feel musical, never buzzy.
 */
object MusicHapticsEngine {

    private const val MIN_PULSE_GAP_MS = 145L

    private var vibrator: Vibrator? = null
    private val lastPulseAtMs = AtomicLong(0L)

    @Volatile
    private var isPlayingNow = false

    fun init(context: Context) {
        val appCtx = context.applicationContext
        vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appCtx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appCtx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun setPlaying(playing: Boolean) {
        isPlayingNow = playing
    }

    fun onBeatDetected(bassEnergy: Float) {
        if (!isPlayingNow) return
        if (!LibraryStore.settings.value.musicHapticsEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        val now = System.currentTimeMillis()
        val prev = lastPulseAtMs.get()
        if (now - prev < MIN_PULSE_GAP_MS) return
        if (!lastPulseAtMs.compareAndSet(prev, now)) return

        val intensity = bassEnergy.coerceIn(0.25f, 1.0f)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
            ) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, (0.35f + intensity * 0.65f).coerceIn(0.2f, 1f))
                    .compose()
                vib.vibrate(effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = (36 + (intensity * 110f)).toInt().coerceIn(24, 180)
                vib.vibrate(VibrationEffect.createOneShot(18L, amp))
            }
        } catch (_: Throwable) {
        }
    }
}
