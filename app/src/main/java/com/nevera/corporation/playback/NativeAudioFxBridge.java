package com.nevera.corporation.playback;

import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;

/**
 * Pure-Java hardware audio mastering bridge wrapping Android's DynamicsProcessing
 * (API 28+ 10-band EQ + post-limiter) with clean fallback to standard Equalizer & LoudnessEnhancer.
 */
public final class NativeAudioFxBridge {

    private DynamicsProcessing dynamicsProcessing;
    private Equalizer fallbackEq;
    private LoudnessEnhancer loudnessEnhancer;
    private int currentPreset = 0;
    private boolean loudnessEnabled = true;

    private static final float[][] PRESET_GAINS_DB = new float[][]{
            // 31Hz, 62Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz
            {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f},          // 0: Flat / Normal
            {-1f, 0f, 2f, 3f, 2f, 0f, 1.5f, 3f, 2.5f, 1f},     // 1: Pop
            {4f, 3f, 1.5f, 0f, -1f, 0.5f, 2f, 3.5f, 4f, 3f},   // 2: Rock
            {6f, 5.5f, 4f, 2f, 0f, 0f, 0f, 0.5f, 1f, 0.5f},    // 3: Bass Boost
            {2.5f, 1.5f, 0f, 1.5f, 3f, 2f, 1f, 2f, 2.5f, 1.5f},// 4: Jazz
            {3f, 2f, 1f, 0f, 0f, 0.5f, 2f, 3.5f, 4f, 4.5f},    // 5: Classic
            {-3f, -2f, -1f, 0.5f, 2f, 4f, 5f, 4.5f, 2f, 0.5f}  // 6: Vocal Boost (Karaoke)
    };

    private static final float[] BAND_FREQS = new float[]{
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    };

    public synchronized void attachSession(int audioSessionId) {
        release();
        int session = Math.max(0, audioSessionId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                DynamicsProcessing.Config.Builder cfgBuilder = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2,
                        true,
                        BAND_FREQS.length,
                        true,
                        4,
                        true,
                        BAND_FREQS.length,
                        true
                );
                DynamicsProcessing.Config cfg = cfgBuilder.build();
                DynamicsProcessing dp = new DynamicsProcessing(0, session, cfg);

                DynamicsProcessing.Limiter limiter = new DynamicsProcessing.Limiter(
                        true, true, 0, 2f, 40f, 4.0f, -0.5f, 0.5f
                );
                dp.setLimiterAllChannelsTo(limiter);
                dp.setEnabled(true);
                this.dynamicsProcessing = dp;
                applyEqualizerPreset(currentPreset);
                applyLoudness(loudnessEnabled);
                return;
            } catch (Throwable ignored) {
                this.dynamicsProcessing = null;
            }
        }

        try {
            Equalizer eq = new Equalizer(0, session);
            eq.setEnabled(true);
            this.fallbackEq = eq;
            applyEqualizerPreset(currentPreset);
        } catch (Throwable ignored) {
            this.fallbackEq = null;
        }

        try {
            LoudnessEnhancer le = new LoudnessEnhancer(session);
            this.loudnessEnhancer = le;
            applyLoudness(loudnessEnabled);
        } catch (Throwable ignored) {
            this.loudnessEnhancer = null;
        }
    }

    public synchronized void applyEqualizerPreset(int presetIndex) {
        this.currentPreset = Math.max(0, Math.min(presetIndex, PRESET_GAINS_DB.length - 1));
        float[] gains = PRESET_GAINS_DB[currentPreset];

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing != null) {
            try {
                DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, BAND_FREQS.length);
                for (int i = 0; i < BAND_FREQS.length; i++) {
                    DynamicsProcessing.EqBand band = new DynamicsProcessing.EqBand(true, BAND_FREQS[i], gains[i]);
                    eq.setBand(i, band);
                }
                dynamicsProcessing.setPostEqAllChannelsTo(eq);
                return;
            } catch (Throwable ignored) {
            }
        }

        if (fallbackEq != null) {
            try {
                short bands = fallbackEq.getNumberOfBands();
                short[] range = fallbackEq.getBandLevelRange();
                int minLv = range[0];
                int maxLv = range[1];
                int mid = (minLv + maxLv) / 2;
                int span = (maxLv - mid);
                for (short b = 0; b < bands; b++) {
                    int mappedIdx = Math.min(gains.length - 1, (b * gains.length) / Math.max(1, bands));
                    float normalized = Math.max(-1f, Math.min(1f, gains[mappedIdx] / 6.0f));
                    int target = mid + Math.round(normalized * span * 0.65f);
                    fallbackEq.setBandLevel(b, (short) Math.max(minLv, Math.min(maxLv, target)));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void applyLoudness(boolean enabled) {
        this.loudnessEnabled = enabled;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing != null) {
            try {
                dynamicsProcessing.setInputGainAllChannelsTo(enabled ? 1.5f : 0f);
            } catch (Throwable ignored) {
            }
        }
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.setTargetGain(enabled ? 280 : 0);
                loudnessEnhancer.setEnabled(enabled);
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing != null) {
            try {
                dynamicsProcessing.release();
            } catch (Throwable ignored) {
            }
            dynamicsProcessing = null;
        }
        if (fallbackEq != null) {
            try {
                fallbackEq.release();
            } catch (Throwable ignored) {
            }
            fallbackEq = null;
        }
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.release();
            } catch (Throwable ignored) {
            }
            loudnessEnhancer = null;
        }
    }
}
