#include <jni.h>
#include <cmath>
#include <algorithm>

namespace {

// Nevera v4.1.0 Studio Mastering Loudness Maximizer + Adaptive Beat-Onset
// Bass/Treble Rhythm Analyzer.
//
// Mastering Stage (only active when boostDb > 0.05f; 100% bit-exact bypass otherwise):
//  - Smooth RMS upward compression / body loudness maximizer so even already-hot
//    streaming tracks (-1 dBFS peak) gain genuine perceptual loudness & fullness.
//  - Subtle musical mastering contour (warm sub-bass punch ~90Hz + vocal air ~3.8kHz).
//  - Dual-stage fast-attack/smooth-release true-peak limiter + analog soft-knee saturator.
//
// Rhythm Analysis Stage (outputs [bassPulse, treblePulse, sustainEnergy,
// beatHit, peakOffsetFraction]):
//  - 2-pole cascaded Low-Pass (<150 Hz) for punchy kick/bass isolation.
//  - 2-pole cascaded High-Pass (>3.2 kHz) for hi-hat/snare/vocal shimmer isolation.
//  - Adaptive spectral-flux onset detector (instant RMS vs slow rolling average)
//    so the UI glow hits tightly on actual musical beats rather than drifting flat.
//  - peakOffsetFraction [0..1] is where inside this block the loudest ~10 ms
//    sub-window sits, so the Kotlin frame clock can stamp an onset frame at
//    the transient itself instead of the block middle (which could be tens
//    of ms away from the kick). Only written when the caller's array holds
//    5+ floats; older 4-float callers are unaffected.
struct NeveraMasteringState {
    int sampleRate = 0;

    // Mastering DSP state
    float currentDrive = 1.0f;
    float rmsEnv = 0.0f;
    float limiterEnv = 1.0f;
    float shelfLowState[2] = {0.0f, 0.0f};
    float shelfHighPrevIn[2] = {0.0f, 0.0f};
    float shelfHighPrevOut[2] = {0.0f, 0.0f};
    float shelfLowAlpha = 0.015f;
    float shelfHighAlpha = 0.65f;

    // 2-pole Bass (<150 Hz) & Treble (>3.2 kHz) analyzer state
    float lp1 = 0.0f;
    float lp2 = 0.0f;
    float hp1PrevIn = 0.0f;
    float hp1PrevOut = 0.0f;
    float hp2PrevIn = 0.0f;
    float hp2PrevOut = 0.0f;
    float lpAlpha = 0.02f;
    float hpAlpha = 0.68f;

    // Adaptive beat-onset & sustain envelopes
    float slowBass = 0.05f;
    float slowTreble = 0.03f;
    float smoothBass = 0.0f;
    float smoothTreble = 0.0f;
    float sustainEnergy = 0.0f;
    float smoothBeat = 0.0f;

    void configure(int sr) {
        if (sr <= 0 || sr == sampleRate) return;
        sampleRate = sr;
        constexpr float kTwoPi = 6.28318530718f;
        const float dt = 1.0f / static_cast<float>(sr);

        // Mastering contour filters (90 Hz low warmth, 3.8 kHz presence)
        const float rcShelfLow = 1.0f / (kTwoPi * 90.0f);
        shelfLowAlpha = dt / (rcShelfLow + dt);
        const float rcShelfHigh = 1.0f / (kTwoPi * 3800.0f);
        shelfHighAlpha = rcShelfHigh / (rcShelfHigh + dt);

        // Analyzer filters (150 Hz 2-pole LP, 3.2 kHz 2-pole HP)
        const float rcLow = 1.0f / (kTwoPi * 150.0f);
        lpAlpha = dt / (rcLow + dt);
        const float rcHigh = 1.0f / (kTwoPi * 3200.0f);
        hpAlpha = rcHigh / (rcHigh + dt);

        shelfLowState[0] = shelfLowState[1] = 0.0f;
        shelfHighPrevIn[0] = shelfHighPrevIn[1] = 0.0f;
        shelfHighPrevOut[0] = shelfHighPrevOut[1] = 0.0f;
        lp1 = lp2 = 0.0f;
        hp1PrevIn = hp1PrevOut = 0.0f;
        hp2PrevIn = hp2PrevOut = 0.0f;
        rmsEnv = 0.0f;
        limiterEnv = 1.0f;
    }
};

thread_local NeveraMasteringState gState;

inline float analogSoftCeiling(float x) {
    // Transparent linear region up to 0.80f, musical analog soft-knee saturation
    // converging smoothly toward 0.985f (-0.13 dBFS) without harsh digital clipping.
    const float ax = std::fabs(x);
    if (ax <= 0.80f) return x;
    const float excess = ax - 0.80f;
    const float shaped = 0.80f + 0.185f * std::tanh(excess * 5.4f);
    return (x >= 0.0f) ? shaped : -shaped;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NeveraMasteringProcessor_nativeProcess(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray jSamples,
    jint frameCount,
    jint channelCount,
    jint sampleRate,
    jfloat boostDb,
    jboolean analyzeBands,
    jfloatArray jOutBands
) {
    if (!jSamples || frameCount <= 0 || channelCount <= 0 || sampleRate <= 0) return;
    gState.configure(sampleRate);

    jfloat* samples = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(jSamples, nullptr));
    if (!samples) return;

    const bool applyBoost = boostDb > 0.05f;
    const float clampedDb = std::clamp(boostDb, 0.0f, 15.0f);
    const float boostNorm = clampedDb / 15.0f; // [0..1]
    const float targetDrive = applyBoost ? std::pow(10.0f, (clampedDb * 0.85f) / 20.0f) : 1.0f;

    const float sr = static_cast<float>(sampleRate);
    const float driveGlide = 1.0f - std::exp(-1.0f / (0.022f * sr));
    const float rmsAttack = 1.0f - std::exp(-1.0f / (0.018f * sr));
    const float rmsRelease = 1.0f - std::exp(-1.0f / (0.135f * sr));
    const float limRelease = 1.0f - std::exp(-1.0f / (0.085f * sr));

    // Subtle mastering contour weights (keeps boosted sound warm & crisp, not harsh)
    const float lowContour = applyBoost ? (0.14f * boostNorm) : 0.0f;
    const float highContour = applyBoost ? (0.11f * boostNorm) : 0.0f;
    // Upward loudness body maximizer (lifts quiet/mid passages to sound dramatically louder)
    const float maxUpward = 1.0f + boostNorm * 1.35f;

    float bassAcc = 0.0f;
    float trebleAcc = 0.0f;
    float fullAcc = 0.0f;
    // Sub-window (~10 ms) peak tracking for onset detection: a kick that lands
    // inside a long block must not be averaged away by the block RMS. The
    // loudest sub-window's center frame is also remembered so the caller can
    // locate the transient inside the block (peakOffsetFraction output).
    float bassSubAcc = 0.0f;
    float trebleSubAcc = 0.0f;
    float bassPeakRms = 0.0f;
    float treblePeakRms = 0.0f;
    float peakSubPower = -1.0f;
    int peakSubCenterFrame = -1;
    int subCount = 0;
    const int subWindow = (sampleRate / 100 > 0) ? (sampleRate / 100) : 480;
    // Closes the current sub-window: folds it into the peak RMS figures and,
    // if it is the loudest so far, records where it sits in the block.
    auto closeSubWindow = [&](int centerFrame) {
        if (subCount <= 0) return;
        const float invSub = 1.0f / static_cast<float>(subCount);
        bassPeakRms = std::max(bassPeakRms, std::sqrt(bassSubAcc * invSub));
        treblePeakRms = std::max(treblePeakRms, std::sqrt(trebleSubAcc * invSub));
        const float power = (bassSubAcc + trebleSubAcc) * invSub;
        if (power > peakSubPower) {
            peakSubPower = power;
            peakSubCenterFrame = centerFrame;
        }
        bassSubAcc = 0.0f; trebleSubAcc = 0.0f; subCount = 0;
    };
    const float invCh = 1.0f / static_cast<float>(channelCount);

    for (int f = 0; f < frameCount; ++f) {
        const int base = f * channelCount;
        gState.currentDrive += (targetDrive - gState.currentDrive) * driveGlide;

        float mono = 0.0f;

        if (applyBoost || std::fabs(gState.currentDrive - 1.0f) > 1e-4f) {
            // 1. Measure input frame power for smooth upward loudness maximizer
            float frameSq = 0.0f;
            for (int c = 0; c < channelCount; ++c) {
                const float x = samples[base + c];
                frameSq += x * x;
            }
            const float frameRms = std::sqrt(frameSq * invCh);
            const float rmsCoeff = (frameRms > gState.rmsEnv) ? rmsAttack : rmsRelease;
            gState.rmsEnv += (frameRms - gState.rmsEnv) * rmsCoeff;

            // Lift body energy smoothly (only active above noise floor 0.008f so silence stays quiet)
            float upwardGain = 1.0f;
            if (applyBoost && gState.rmsEnv > 0.008f) {
                const float desiredRms = 0.34f + 0.14f * boostNorm;
                const float ratio = desiredRms / std::max(gState.rmsEnv, 0.045f);
                const float rawUp = std::clamp(std::pow(ratio, 0.55f), 1.0f, maxUpward);
                // Smooth fade in from noise floor 0.008..0.025
                const float gate = std::clamp((gState.rmsEnv - 0.008f) / 0.017f, 0.0f, 1.0f);
                upwardGain = 1.0f + (rawUp - 1.0f) * gate;
            }

            const float totalGain = gState.currentDrive * upwardGain;
            float framePeak = 0.0f;

            // 2. Apply mastering drive + gentle bass/presence contour
            for (int c = 0; c < channelCount; ++c) {
                float s = samples[base + c];
                if (c < 2 && applyBoost) {
                    // Warm low-end body + crisp vocal presence
                    gState.shelfLowState[c] += gState.shelfLowAlpha * (s - gState.shelfLowState[c]);
                    const float hp = gState.shelfHighAlpha *
                        (gState.shelfHighPrevOut[c] + s - gState.shelfHighPrevIn[c]);
                    gState.shelfHighPrevIn[c] = s;
                    gState.shelfHighPrevOut[c] = hp;
                    s = s + gState.shelfLowState[c] * lowContour + hp * highContour;
                }
                s *= totalGain;
                const float a = std::fabs(s);
                if (a > framePeak) framePeak = a;
                samples[base + c] = s;
            }

            // 3. Fast-attack, smooth-release true-peak limiter + analog soft ceiling
            const float ceiling = 0.94f;
            const float needed = (framePeak > ceiling) ? (ceiling / framePeak) : 1.0f;
            if (needed < gState.limiterEnv) {
                gState.limiterEnv = needed;
            } else {
                gState.limiterEnv += (needed - gState.limiterEnv) * limRelease;
            }

            for (int c = 0; c < channelCount; ++c) {
                const float out = analogSoftCeiling(samples[base + c] * gState.limiterEnv);
                samples[base + c] = out;
                mono += out;
            }
        } else if (analyzeBands) {
            for (int c = 0; c < channelCount; ++c) {
                mono += samples[base + c];
            }
        }

        if (analyzeBands) {
            mono *= invCh;
            fullAcc += mono * mono;

            // 2-pole cascaded LP (<150 Hz) isolates kick drum & deep bass tightly
            gState.lp1 += gState.lpAlpha * (mono - gState.lp1);
            gState.lp2 += gState.lpAlpha * (gState.lp1 - gState.lp2);
            bassAcc += gState.lp2 * gState.lp2;
            bassSubAcc += gState.lp2 * gState.lp2;

            // 2-pole cascaded HP (>3.2 kHz) isolates hi-hats, snare snap & vocal air
            const float hp1 = gState.hpAlpha * (gState.hp1PrevOut + mono - gState.hp1PrevIn);
            gState.hp1PrevIn = mono;
            gState.hp1PrevOut = hp1;
            const float hp2 = gState.hpAlpha * (gState.hp2PrevOut + hp1 - gState.hp2PrevIn);
            gState.hp2PrevIn = hp1;
            gState.hp2PrevOut = hp2;
            trebleAcc += hp2 * hp2;
            trebleSubAcc += hp2 * hp2;
            if (++subCount >= subWindow) {
                // This window spans frames [f - subCount + 1, f].
                closeSubWindow(f - subCount / 2);
            }
        }
    }

    env->ReleasePrimitiveArrayCritical(jSamples, samples, 0);

    if (analyzeBands && jOutBands) {
        const float invFrames = 1.0f / static_cast<float>(frameCount);
        const float bassRms = std::sqrt(bassAcc * invFrames);
        const float trebleRms = std::sqrt(trebleAcc * invFrames);
        const float fullRms = std::sqrt(fullAcc * invFrames);

        // Adaptive moving baselines (~450ms window depending on block size)
        const float blockSec = static_cast<float>(frameCount) / sr;
        const float slowAlpha = std::clamp(blockSec / 0.42f, 0.02f, 0.35f);
        const float sustainAlpha = std::clamp(blockSec / 1.10f, 0.01f, 0.25f);

        gState.slowBass += (bassRms - gState.slowBass) * slowAlpha;
        gState.slowTreble += (trebleRms - gState.slowTreble) * slowAlpha;

        // Spectral-flux transient onset: spikes sharply when kick/hi-hat hits above local baseline!
        if (subCount > 0) {
            // Final partial window spans [frameCount - subCount, frameCount - 1].
            closeSubWindow((frameCount - subCount + frameCount - 1) / 2);
        }
        const float peakOffsetFraction = (peakSubCenterFrame >= 0)
            ? std::clamp(static_cast<float>(peakSubCenterFrame) / static_cast<float>(frameCount), 0.0f, 1.0f)
            : 0.5f;
        const float bassOnsetRms = std::max(bassRms, bassPeakRms);
        const float trebleOnsetRms = std::max(trebleRms, treblePeakRms);
        const float bassFlux = std::max(0.0f, bassOnsetRms - gState.slowBass * 0.92f);
        const float trebleFlux = std::max(0.0f, trebleOnsetRms - gState.slowTreble * 0.90f);

        const float bassOnset = std::clamp(bassFlux * 9.5f, 0.0f, 1.0f);
        const float trebleOnset = std::clamp(trebleFlux * 16.0f, 0.0f, 1.0f);

        const float bassLevel = std::clamp(bassRms * 3.4f, 0.0f, 1.0f);
        const float trebleLevel = std::clamp(trebleRms * 6.2f, 0.0f, 1.0f);

        // Blend transient beat onset (65%) + musical energy floor (35%) so glow
        // breathes continuously AND snaps unmistakably with every beat!
        const float targetBass = std::clamp(bassLevel * 0.35f + bassOnset * 0.85f, 0.0f, 1.0f);
        const float targetTreble = std::clamp(trebleLevel * 0.30f + trebleOnset * 0.88f, 0.0f, 1.0f);

        // Time-based smoothing: coefficients derive from the actual block
        // duration, so attack/release time constants stay constant in seconds
        // no matter what block size the pipeline hands us (the old fixed
        // per-block coefficients made the release ~340 ms at 85 ms blocks and
        // smeared every beat). Bass release is 120 ms; attack is ~8 ms.
        const float keepAttack = std::exp(-blockSec / 0.008f);
        const float keepBassRelease = std::exp(-blockSec / 0.120f);
        const float keepTrebleRelease = std::exp(-blockSec / 0.100f);
        gState.smoothBass = (targetBass > gState.smoothBass)
            ? targetBass + (gState.smoothBass - targetBass) * keepAttack
            : targetBass + (gState.smoothBass - targetBass) * keepBassRelease;
        gState.smoothTreble = (targetTreble > gState.smoothTreble)
            ? targetTreble + (gState.smoothTreble - targetTreble) * keepAttack
            : targetTreble + (gState.smoothTreble - targetTreble) * keepTrebleRelease;

        const float targetSustain = std::clamp(fullRms * 3.2f, 0.0f, 1.0f);
        gState.sustainEnergy += (targetSustain - gState.sustainEnergy) * sustainAlpha;

        const float rawBeat = std::clamp(bassOnset * 0.75f + trebleOnset * 0.35f, 0.0f, 1.0f);
        gState.smoothBeat = (rawBeat > gState.smoothBeat)
            ? rawBeat
            : rawBeat + (gState.smoothBeat - rawBeat) * std::exp(-blockSec / 0.160f);

        const jsize outLen = env->GetArrayLength(jOutBands);
        if (outLen >= 5) {
            jfloat out[5] = {
                gState.smoothBass,
                gState.smoothTreble,
                gState.sustainEnergy,
                gState.smoothBeat,
                peakOffsetFraction,
            };
            env->SetFloatArrayRegion(jOutBands, 0, 5, out);
        } else if (outLen >= 4) {
            jfloat out[4] = {
                gState.smoothBass,
                gState.smoothTreble,
                gState.sustainEnergy,
                gState.smoothBeat,
            };
            env->SetFloatArrayRegion(jOutBands, 0, 4, out);
        } else if (outLen >= 2) {
            jfloat out[2] = { gState.smoothBass, gState.smoothTreble };
            env->SetFloatArrayRegion(jOutBands, 0, 2, out);
        }
    }
}
