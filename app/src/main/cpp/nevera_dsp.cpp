#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr double kPiD = 3.14159265358979323846;
constexpr int kFftSize = 512;
constexpr int kNumBands = 32;
constexpr int kDspBands = 10;

struct Biquad64 {
    double b0 = 1.0, b1 = 0.0, b2 = 0.0;
    double a1 = 0.0, a2 = 0.0;
    double z1 = 0.0, z2 = 0.0;
    bool active = false;

    void reset() {
        z1 = 0.0;
        z2 = 0.0;
    }

    void setIdentity() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0;
        a1 = 0.0; a2 = 0.0;
        active = false;
    }

    // kind: 0 = LowShelf, 1 = Peaking, 2 = HighShelf
    void configure(int kind, double sampleRate, double freqHz, double gainDb, double q) {
        if (sampleRate <= 1000.0 || std::abs(gainDb) < 0.02) {
            setIdentity();
            return;
        }
        const double safeF = std::clamp(freqHz, 18.0, sampleRate * 0.45);
        const double safeQ = std::clamp(q, 0.35, 8.0);
        const double safeG = std::clamp(gainDb, -15.0, 15.0);

        const double A = std::pow(10.0, safeG / 40.0);
        const double w0 = 2.0 * kPiD * safeF / sampleRate;
        const double cosW = std::cos(w0);
        const double sinW = std::sin(w0);
        const double alpha = sinW / (2.0 * safeQ);

        double nb0 = 1.0, nb1 = 0.0, nb2 = 0.0;
        double na0 = 1.0, na1 = 0.0, na2 = 0.0;

        if (kind == 0) {
            // Low Shelf
            const double twoSqrtAAlpha = 2.0 * std::sqrt(A) * (sinW / 2.0) * std::sqrt((A + 1.0 / A) * (1.0 / std::clamp(safeQ, 0.4, 2.0) - 1.0) + 2.0);
            const double shelfAlpha = std::isfinite(twoSqrtAAlpha) ? twoSqrtAAlpha : (2.0 * std::sqrt(A) * alpha);
            nb0 = A * ((A + 1.0) - (A - 1.0) * cosW + shelfAlpha);
            nb1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW);
            nb2 = A * ((A + 1.0) - (A - 1.0) * cosW - shelfAlpha);
            na0 = (A + 1.0) + (A - 1.0) * cosW + shelfAlpha;
            na1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW);
            na2 = (A + 1.0) + (A - 1.0) * cosW - shelfAlpha;
        } else if (kind == 2) {
            // High Shelf
            const double twoSqrtAAlpha = 2.0 * std::sqrt(A) * (sinW / 2.0) * std::sqrt((A + 1.0 / A) * (1.0 / std::clamp(safeQ, 0.4, 2.0) - 1.0) + 2.0);
            const double shelfAlpha = std::isfinite(twoSqrtAAlpha) ? twoSqrtAAlpha : (2.0 * std::sqrt(A) * alpha);
            nb0 = A * ((A + 1.0) + (A - 1.0) * cosW + shelfAlpha);
            nb1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW);
            nb2 = A * ((A + 1.0) + (A - 1.0) * cosW - shelfAlpha);
            na0 = (A + 1.0) - (A - 1.0) * cosW + shelfAlpha;
            na1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW);
            na2 = (A + 1.0) - (A - 1.0) * cosW - shelfAlpha;
        } else {
            // Peaking EQ
            nb0 = 1.0 + alpha * A;
            nb1 = -2.0 * cosW;
            nb2 = 1.0 - alpha * A;
            na0 = 1.0 + alpha / A;
            na1 = -2.0 * cosW;
            na2 = 1.0 - alpha / A;
        }

        if (std::abs(na0) < 1e-12) {
            setIdentity();
            return;
        }
        b0 = nb0 / na0;
        b1 = nb1 / na0;
        b2 = nb2 / na0;
        a1 = na1 / na0;
        a2 = na2 / na0;
        active = true;
    }

    inline double process(double x) {
        if (!active) return x;
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }

    double responseDb(double freqHz, double sampleRate) const {
        if (!active || sampleRate <= 1000.0) return 0.0;
        const double w = 2.0 * kPiD * freqHz / sampleRate;
        const double cw = std::cos(w);
        const double sw = std::sin(w);
        const double c2w = std::cos(2.0 * w);
        const double s2w = std::sin(2.0 * w);

        const double numR = b0 + b1 * cw + b2 * c2w;
        const double numI = -b1 * sw - b2 * s2w;
        const double denR = 1.0 + a1 * cw + a2 * c2w;
        const double denI = -a1 * sw - a2 * s2w;

        const double numSq = numR * numR + numI * numI;
        const double denSq = std::max(1e-18, denR * denR + denI * denI);
        return 10.0 * std::log10(std::max(1e-18, numSq / denSq));
    }
};

struct NeveraStudioDsp {
    double sampleRate = 48000.0;
    int channels = 2;

    bool enabled = true;
    bool bitPerfectBypass = false;
    bool autoPreamp = true;
    bool lufsNorm = true;

    double preampLinear = 1.0;
    double stereoWidth = 1.0;
    double balL = 1.0;
    double balR = 1.0;
    double tubeWarmth = 0.0;
    double targetLufsLinear = 0.20; // ~ -14 LUFS RMS proxy

    // 10-band stereo biquads
    Biquad64 eqL[kDspBands];
    Biquad64 eqR[kDspBands];

    // BS2B crossfeed state
    bool crossfeedActive = false;
    double cfLpA0 = 0.0, cfLpB1 = 0.0;
    double cfCrossGain = 0.0;
    double cfDirectGain = 1.0;
    double cfStateL = 0.0, cfStateR = 0.0;

    // EBU R128 loudness integrator & DC blocker
    double lufsEnergy = 0.04;
    double lufsGain = 1.0;
    double dcPrevInL = 0.0, dcPrevOutL = 0.0;
    double dcPrevInR = 0.0, dcPrevOutR = 0.0;

    // True-peak soft-knee limiter state
    double limEnv = 0.0;

    void init(int sr, int ch) {
        sampleRate = static_cast<double>(std::max(8000, sr));
        channels = std::clamp(ch, 1, 8);
        reset();
    }

    void reset() {
        for (int i = 0; i < kDspBands; ++i) {
            eqL[i].reset();
            eqR[i].reset();
        }
        cfStateL = 0.0;
        cfStateR = 0.0;
        lufsEnergy = 0.04;
        lufsGain = 1.0;
        dcPrevInL = dcPrevOutL = 0.0;
        dcPrevInR = dcPrevOutR = 0.0;
        limEnv = 0.0;
    }

    void updateConfig(
            const float* params,
            int paramCount,
            const float* freqs,
            const float* gains,
            const float* qs,
            int bandCount) {
        if (paramCount < 11) return;
        enabled = params[0] > 0.5f;
        bitPerfectBypass = params[1] > 0.5f;
        const double manualPreampDb = std::clamp(static_cast<double>(params[2]), -12.0, 6.0);
        autoPreamp = params[3] > 0.5f;
        const double cfFcut = static_cast<double>(params[4]);
        const double cfFeedDb = static_cast<double>(params[5]);
        stereoWidth = std::clamp(static_cast<double>(params[6]), 0.5, 1.6);
        const double balance = std::clamp(static_cast<double>(params[7]), -1.0, 1.0);
        tubeWarmth = std::clamp(static_cast<double>(params[8]), 0.0, 1.0);
        lufsNorm = params[9] > 0.5f;
        const double targetLufsDb = std::clamp(static_cast<double>(params[10]), -23.0, -9.0);
        targetLufsLinear = std::pow(10.0, targetLufsDb / 20.0);

        balL = (balance > 0.0) ? (1.0 - balance) : 1.0;
        balR = (balance < 0.0) ? (1.0 + balance) : 1.0;

        double maxPositiveGainDb = 0.0;
        const int n = std::min(kDspBands, bandCount);
        for (int i = 0; i < kDspBands; ++i) {
            if (i < n) {
                const int kind = (i == 0) ? 0 : ((i == kDspBands - 1) ? 2 : 1);
                const double g = static_cast<double>(gains[i]);
                if (g > maxPositiveGainDb) maxPositiveGainDb = g;
                eqL[i].configure(kind, sampleRate, static_cast<double>(freqs[i]), g, static_cast<double>(qs[i]));
                eqR[i].configure(kind, sampleRate, static_cast<double>(freqs[i]), g, static_cast<double>(qs[i]));
            } else {
                eqL[i].setIdentity();
                eqR[i].setIdentity();
            }
        }

        double effectivePreampDb = manualPreampDb;
        if (autoPreamp && maxPositiveGainDb > 0.1) {
            const double autoHeadroom = -(maxPositiveGainDb + 0.4);
            effectivePreampDb = std::min(manualPreampDb, autoHeadroom);
        }
        preampLinear = std::pow(10.0, effectivePreampDb / 20.0);

        // BS2B crossfeed
        if (cfFcut > 50.0 && cfFeedDb > 0.1) {
            crossfeedActive = true;
            const double x = std::exp(-2.0 * kPiD * cfFcut / sampleRate);
            cfLpA0 = 1.0 - x;
            cfLpB1 = x;
            cfCrossGain = std::pow(10.0, -cfFeedDb / 20.0);
            cfDirectGain = 1.0 / (1.0 + cfCrossGain * 0.65);
        } else {
            crossfeedActive = false;
            cfCrossGain = 0.0;
            cfDirectGain = 1.0;
        }
    }

    void processStereoInterleaved(float* buf, int frameCount) {
        if (!enabled || bitPerfectBypass || frameCount <= 0) return;

        const double limAttack = std::exp(-1.0 / (0.0005 * sampleRate));
        const double limRelease = std::exp(-1.0 / (0.065 * sampleRate));
        const double lufsAlpha = 1.0 - std::exp(-1.0 / (1.2 * sampleRate));
        const double lufsGainSlew = 1.0 - std::exp(-1.0 / (0.35 * sampleRate));
        const double dcCoeff = 1.0 - (2.0 * kPiD * 8.0 / sampleRate);
        constexpr double kCeiling = 0.966; // -0.3 dBFS

        if (channels == 1) {
            for (int f = 0; f < frameCount; ++f) {
                double x = static_cast<double>(buf[f]) * preampLinear;
                for (int b = 0; b < kDspBands; ++b) x = eqL[b].process(x);
                if (tubeWarmth > 0.01) {
                    const double exc = x + tubeWarmth * 0.14 * (x * x - 0.5 * x * x * x);
                    const double dcOut = exc - dcPrevInL + dcCoeff * dcPrevOutL;
                    dcPrevInL = exc;
                    dcPrevOutL = dcOut;
                    x = dcOut;
                }
                const double absX = std::abs(x);
                limEnv = (absX > limEnv)
                        ? (limAttack * limEnv + (1.0 - limAttack) * absX)
                        : (limRelease * limEnv + (1.0 - limRelease) * absX);
                if (limEnv > kCeiling) {
                    x *= (kCeiling / limEnv);
                }
                if (std::abs(x) > 0.985) {
                    x = std::tanh(x) * 0.985;
                }
                buf[f] = std::isfinite(x) ? static_cast<float>(std::clamp(x, -0.99, 0.99)) : 0.0f;
            }
            return;
        }

        const int stride = channels;
        for (int f = 0; f < frameCount; ++f) {
            const int base = f * stride;
            double l = static_cast<double>(buf[base]) * preampLinear;
            double r = static_cast<double>(buf[base + 1]) * preampLinear;

            // 1. 10-band 64-bit Parametric EQ cascade
            for (int b = 0; b < kDspBands; ++b) {
                l = eqL[b].process(l);
                r = eqR[b].process(r);
            }

            // 2. BS2B Headphone Crossfeed
            if (crossfeedActive) {
                cfStateL = cfLpA0 * l + cfLpB1 * cfStateL;
                cfStateR = cfLpA0 * r + cfLpB1 * cfStateR;
                const double outL = l * cfDirectGain + cfStateR * cfCrossGain * cfDirectGain;
                const double outR = r * cfDirectGain + cfStateL * cfCrossGain * cfDirectGain;
                l = outL;
                r = outR;
            }

            // 3. Mid-Side Stereo Width + L/R Channel Balance
            if (std::abs(stereoWidth - 1.0) > 0.005 || balL < 0.999 || balR < 0.999) {
                const double mid = 0.5 * (l + r);
                const double side = 0.5 * (l - r) * stereoWidth;
                l = (mid + side) * balL;
                r = (mid - side) * balR;
            }

            // 4. EBU R128 Smooth Loudness Normalizer (with -55 dBFS silence gate)
            if (lufsNorm) {
                const double instPower = 0.5 * (l * l + r * r);
                if (instPower > 3.16e-6) {
                    lufsEnergy += lufsAlpha * (instPower - lufsEnergy);
                    const double currentRms = std::sqrt(std::max(1e-8, lufsEnergy));
                    const double desired = std::clamp(targetLufsLinear / currentRms, 0.40, 2.0);
                    lufsGain += lufsGainSlew * (desired - lufsGain);
                }
                l *= lufsGain;
                r *= lufsGain;
            }

            // 5. Subtle Analog Tube Warmth (even-harmonic + DC block)
            if (tubeWarmth > 0.01) {
                const double excL = l + tubeWarmth * 0.14 * (l * l - 0.5 * l * l * l);
                const double excR = r + tubeWarmth * 0.14 * (r * r - 0.5 * r * r * r);
                const double dcL = excL - dcPrevInL + dcCoeff * dcPrevOutL;
                const double dcR = excR - dcPrevInR + dcCoeff * dcPrevOutR;
                dcPrevInL = excL; dcPrevOutL = dcL;
                dcPrevInR = excR; dcPrevOutR = dcR;
                l = dcL;
                r = dcR;
            }

            // 6. True-Peak Soft-Knee Limiter (-0.3 dBFS ceiling)
            const double peak = std::max(std::abs(l), std::abs(r));
            limEnv = (peak > limEnv)
                    ? (limAttack * limEnv + (1.0 - limAttack) * peak)
                    : (limRelease * limEnv + (1.0 - limRelease) * peak);
            if (limEnv > kCeiling) {
                const double gr = kCeiling / limEnv;
                l *= gr;
                r *= gr;
            }
            if (std::abs(l) > 0.985) l = std::tanh(l) * 0.985;
            if (std::abs(r) > 0.985) r = std::tanh(r) * 0.985;

            buf[base] = std::isfinite(l) ? static_cast<float>(std::clamp(l, -0.99, 0.99)) : 0.0f;
            buf[base + 1] = std::isfinite(r) ? static_cast<float>(std::clamp(r, -0.99, 0.99)) : 0.0f;
        }
    }
};

inline bool isJapaneseKana(uint16_t ch) {
    return (ch >= 0x3040 && ch <= 0x309F) || (ch >= 0x30A0 && ch <= 0x30FF);
}

inline bool isCjkIdeograph(uint16_t ch) {
    return (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF);
}

inline uint16_t katakanaToHiragana(uint16_t ch) {
    if (ch >= 0x30A1 && ch <= 0x30F6) {
        return static_cast<uint16_t>(ch - 0x60);
    }
    return ch;
}

const char* basicKanaLookup(uint16_t hira) {
    switch (hira) {
        case 0x3041: case 0x3042: return "a";
        case 0x3043: case 0x3044: return "i";
        case 0x3045: case 0x3046: return "u";
        case 0x3047: case 0x3048: return "e";
        case 0x3049: case 0x304A: return "o";
        case 0x304B: return "ka"; case 0x304C: return "ga";
        case 0x304D: return "ki"; case 0x304E: return "gi";
        case 0x304F: return "ku"; case 0x3050: return "gu";
        case 0x3051: return "ke"; case 0x3052: return "ge";
        case 0x3053: return "ko"; case 0x3054: return "go";
        case 0x3055: return "sa"; case 0x3056: return "za";
        case 0x3057: return "shi"; case 0x3058: return "ji";
        case 0x3059: return "su"; case 0x305A: return "zu";
        case 0x305B: return "se"; case 0x305C: return "ze";
        case 0x305D: return "so"; case 0x305E: return "zo";
        case 0x305F: return "ta"; case 0x3060: return "da";
        case 0x3061: return "chi"; case 0x3062: return "ji";
        case 0x3064: return "tsu"; case 0x3065: return "zu";
        case 0x3066: return "te"; case 0x3067: return "de";
        case 0x3068: return "to"; case 0x3069: return "do";
        case 0x306A: return "na"; case 0x306B: return "ni";
        case 0x306C: return "nu"; case 0x306D: return "ne";
        case 0x306E: return "no";
        case 0x306F: return "ha"; case 0x3070: return "ba"; case 0x3071: return "pa";
        case 0x3072: return "hi"; case 0x3073: return "bi"; case 0x3074: return "pi";
        case 0x3075: return "fu"; case 0x3076: return "bu"; case 0x3077: return "pu";
        case 0x3078: return "he"; case 0x3079: return "be"; case 0x307A: return "pe";
        case 0x307B: return "ho"; case 0x307C: return "bo"; case 0x307D: return "po";
        case 0x307E: return "ma"; case 0x307F: return "mi";
        case 0x3080: return "mu"; case 0x3081: return "me";
        case 0x3082: return "mo";
        case 0x3083: case 0x3084: return "ya";
        case 0x3085: case 0x3086: return "yu";
        case 0x3087: case 0x3088: return "yo";
        case 0x3089: return "ra"; case 0x308A: return "ri";
        case 0x308B: return "ru"; case 0x308C: return "re";
        case 0x308D: return "ro";
        case 0x308E: case 0x308F: return "wa";
        case 0x3090: return "wi"; case 0x3091: return "we";
        case 0x3092: return "wo"; case 0x3093: return "n";
        case 0x3094: return "vu";
        default: return nullptr;
    }
}

const char* yoonVowelSuffix(uint16_t smallKana) {
    switch (smallKana) {
        case 0x3083: return "a";
        case 0x3085: return "u";
        case 0x3087: return "o";
        default: return nullptr;
    }
}

std::string buildYoon(uint16_t baseHira, uint16_t smallHira) {
    const char* s = yoonVowelSuffix(smallHira);
    if (!s) return "";
    switch (baseHira) {
        case 0x304D: return std::string("ky") + s;
        case 0x304E: return std::string("gy") + s;
        case 0x3057: return std::string("sh") + s;
        case 0x3058: return std::string("j") + s;
        case 0x3061: return std::string("ch") + s;
        case 0x306B: return std::string("ny") + s;
        case 0x3072: return std::string("hy") + s;
        case 0x3073: return std::string("by") + s;
        case 0x3074: return std::string("py") + s;
        case 0x307F: return std::string("my") + s;
        case 0x308A: return std::string("ry") + s;
        default: return "";
    }
}

inline void fftInPlace512(float* real, float* imag) {
    int j = 0;
    for (int i = 0; i < kFftSize; ++i) {
        if (i < j) {
            std::swap(real[i], real[j]);
            std::swap(imag[i], imag[j]);
        }
        int m = kFftSize >> 1;
        while (m >= 1 && j >= m) {
            j -= m;
            m >>= 1;
        }
        j += m;
    }

    for (int len = 2; len <= kFftSize; len <<= 1) {
        float ang = -2.0f * kPi / static_cast<float>(len);
        float wlenR = std::cos(ang);
        float wlenI = std::sin(ang);
        int half = len >> 1;
        for (int i = 0; i < kFftSize; i += len) {
            float wR = 1.0f;
            float wI = 0.0f;
            for (int k = 0; k < half; ++k) {
                int evenIdx = i + k;
                int oddIdx = evenIdx + half;
                float tR = real[oddIdx] * wR - imag[oddIdx] * wI;
                float tI = real[oddIdx] * wI + imag[oddIdx] * wR;
                real[oddIdx] = real[evenIdx] - tR;
                imag[oddIdx] = imag[evenIdx] - tI;
                real[evenIdx] += tR;
                imag[evenIdx] += tI;
                float nextR = wR * wlenR - wI * wlenI;
                wI = wR * wlenI + wI * wlenR;
                wR = nextR;
            }
        }
    }
}

} // namespace

extern "C" {

/**
 * Computes weighted start/end millisecond timestamps for each word/syllable unit in a lyric line.
 */
JNIEXPORT jlongArray JNICALL
Java_com_nevera_corporation_data_native_NeveraNativeEngine_nativeAllocateWordTimings(
        JNIEnv* env,
        jobject /* thiz */,
        jdoubleArray weights,
        jlong startMs,
        jlong endMs) {
    jsize n = env->GetArrayLength(weights);
    if (n <= 0 || endMs <= startMs) {
        return env->NewLongArray(0);
    }

    jdouble* wPtr = env->GetDoubleArrayElements(weights, nullptr);
    double totalWeight = 0.0;
    for (jsize i = 0; i < n; ++i) {
        totalWeight += std::max(0.25, wPtr[i]);
    }
    if (totalWeight <= 0.0) totalWeight = static_cast<double>(n);

    const double span = static_cast<double>(endMs - startMs);
    const double activeSpan = span * 0.96;

    std::vector<jlong> out(static_cast<size_t>(n) * 2);
    double acc = 0.0;
    for (jsize i = 0; i < n; ++i) {
        const double w = std::max(0.25, wPtr[i]);
        const jlong wStart = startMs + static_cast<jlong>((acc / totalWeight) * activeSpan);
        acc += w;
        jlong wEnd = startMs + static_cast<jlong>((acc / totalWeight) * activeSpan);
        if (wEnd <= wStart) wEnd = wStart + 60;
        if (wEnd > endMs) wEnd = endMs;
        out[static_cast<size_t>(i) * 2] = wStart;
        out[static_cast<size_t>(i) * 2 + 1] = wEnd;
    }

    env->ReleaseDoubleArrayElements(weights, wPtr, JNI_ABORT);

    jlongArray res = env->NewLongArray(n * 2);
    env->SetLongArrayRegion(res, 0, n * 2, out.data());
    return res;
}

/**
 * Computes 32 smooth logarithmic frequency bands + real-time Spectral Flux BPM estimation.
 * stateBuf size must be >= 40:
 *   [0..31]  = smoothed bands from previous frame
 *   [32]     = prevBassMidEnergy
 *   [33]     = emaFlux
 *   [34]     = lastOnsetMs
 *   [35]     = clockMs accumulated from buffer hop
 *   [36]     = smoothedBpm (default 0 or 65..185)
 *   [37]     = beatPulse (0..1 decaying envelope on beat hit)
 */
JNIEXPORT void JNICALL
Java_com_nevera_corporation_data_native_NeveraNativeEngine_nativeAnalyzeSpectrumAndBpm(
        JNIEnv* env,
        jobject /* thiz */,
        jfloatArray monoPcm,
        jint sampleRate,
        jfloatArray outBands,
        jfloatArray stateBuf) {
    jsize pcmLen = env->GetArrayLength(monoPcm);
    jsize bandCount = env->GetArrayLength(outBands);
    jsize stateLen = env->GetArrayLength(stateBuf);
    if (pcmLen <= 0 || bandCount < kNumBands || stateLen < 38 || sampleRate <= 0) {
        return;
    }

    jfloat* pcm = env->GetFloatArrayElements(monoPcm, nullptr);
    jfloat* out = env->GetFloatArrayElements(outBands, nullptr);
    jfloat* st = env->GetFloatArrayElements(stateBuf, nullptr);

    float real[kFftSize];
    float imag[kFftSize];

    const int copyLen = std::min(static_cast<int>(pcmLen), kFftSize);
    for (int i = 0; i < kFftSize; ++i) {
        float s = (i < copyLen) ? pcm[i] : 0.0f;
        float hann = 0.5f * (1.0f - std::cos(2.0f * kPi * static_cast<float>(i) / static_cast<float>(kFftSize - 1)));
        real[i] = s * hann;
        imag[i] = 0.0f;
    }

    fftInPlace512(real, imag);

    const float sr = static_cast<float>(sampleRate);
    const float nyquist = sr * 0.5f;
    const float fMin = 34.0f;
    const float fBassTop = 220.0f;
    const float fVocalTop = 3400.0f;
    const float fMax = std::min(16000.0f, nyquist * 0.93f);
    const int halfBins = kFftSize / 2;

    float rawBands[kNumBands];
    float bassMidEnergy = 0.0f;

    // Zone bounds inside 32 bands: Bass [0..9], Vocal [10..21], Treble [22..31]
    for (int b = 0; b < kNumBands; ++b) {
        float f0 = fMin;
        float f1 = fBassTop;
        float gainScale = 1.0f;
        if (b < 10) {
            float t0 = static_cast<float>(b) / 10.0f;
            float t1 = static_cast<float>(b + 1) / 10.0f;
            f0 = fMin * std::pow(fBassTop / fMin, t0);
            f1 = fMin * std::pow(fBassTop / fMin, t1);
            gainScale = 0.95f + 0.12f * t0;
        } else if (b < 22) {
            float t0 = static_cast<float>(b - 10) / 12.0f;
            float t1 = static_cast<float>(b - 9) / 12.0f;
            f0 = fBassTop * std::pow(fVocalTop / fBassTop, t0);
            f1 = fBassTop * std::pow(fVocalTop / fBassTop, t1);
            gainScale = 1.28f + 0.25f * std::sin(t0 * kPi);
        } else {
            float t0 = static_cast<float>(b - 22) / 10.0f;
            float t1 = static_cast<float>(b - 21) / 10.0f;
            float safeTop = std::max(fVocalTop + 600.0f, fMax);
            f0 = fVocalTop * std::pow(safeTop / fVocalTop, t0);
            f1 = fVocalTop * std::pow(safeTop / fVocalTop, t1);
            gainScale = 1.55f + 0.45f * t0;
        }

        int binStart = std::max(1, static_cast<int>(std::floor(f0 * kFftSize / sr)));
        int binEnd = std::min(halfBins - 1, std::max(binStart, static_cast<int>(std::ceil(f1 * kFftSize / sr))));

        float sumSq = 0.0f;
        int cnt = 0;
        for (int k = binStart; k <= binEnd; ++k) {
            float mag = std::sqrt(real[k] * real[k] + imag[k] * imag[k]) / (kFftSize * 0.5f);
            sumSq += mag;
            cnt++;
        }
        float avgMag = (cnt > 0) ? (sumSq / static_cast<float>(cnt)) : 0.0f;

        float compressed = std::log1p(avgMag * 54.0f * gainScale) / std::log1p(54.0f * 0.55f);
        if (!std::isfinite(compressed)) compressed = 0.0f;
        rawBands[b] = std::clamp(compressed, 0.0f, 1.0f);

        if (b < 10) {
            bassMidEnergy += rawBands[b];
        }
    }

    // Spatial smoothing strictly within each frequency zone so Bass, Vocal, and Treble stay distinct
    float blended[kNumBands];
    for (int b = 0; b < kNumBands; ++b) {
        int zStart = (b < 10) ? 0 : (b < 22 ? 10 : 22);
        int zEnd = (b < 10) ? 9 : (b < 22 ? 21 : 31);
        float left = (b > zStart) ? rawBands[b - 1] : rawBands[b];
        float cur = rawBands[b];
        float right = (b < zEnd) ? rawBands[b + 1] : rawBands[b];
        blended[b] = 0.14f * left + 0.72f * cur + 0.14f * right;

        float prev = st[b];
        float attack = (b < 10) ? 0.62f : 0.58f;
        float decay = (b < 10) ? 0.21f : 0.25f;
        float coeff = (blended[b] > prev) ? attack : decay;
        float smoothed = prev + (blended[b] - prev) * coeff;
        st[b] = smoothed;
        out[b] = smoothed;
    }

    // Spectral flux beat onset & BPM tracking
    float hopMs = (static_cast<float>(copyLen) / sr) * 1000.0f;
    st[35] += hopMs; // accumulated clock in ms

    float avgBass = bassMidEnergy / 10.0f;
    float flux = std::max(0.0f, avgBass - st[32]);
    st[32] = avgBass;
    st[33] = st[33] * 0.90f + flux * 0.10f; // adaptive threshold baseline

    float elapsedSinceOnset = st[35] - st[34];
    st[37] = std::max(0.0f, st[37] * 0.86f); // beat pulse decay

    if (flux > std::max(0.045f, st[33] * 2.15f) && elapsedSinceOnset > 290.0f) {
        if (elapsedSinceOnset < 1050.0f) {
            float instBpm = 60000.0f / elapsedSinceOnset;
            while (instBpm < 70.0f) instBpm *= 2.0f;
            while (instBpm > 176.0f) instBpm *= 0.5f;
            if (st[36] < 40.0f) {
                st[36] = instBpm;
            } else {
                st[36] = st[36] * 0.82f + instBpm * 0.18f;
            }
        }
        st[34] = st[35];
        st[37] = 1.0f;
    }

    env->ReleaseFloatArrayElements(monoPcm, pcm, JNI_ABORT);
    env->ReleaseFloatArrayElements(outBands, out, 0);
    env->ReleaseFloatArrayElements(stateBuf, st, 0);
}

JNIEXPORT jfloat JNICALL
Java_com_nevera_corporation_data_native_NeveraNativeEngine_nativeStepSpring(
        JNIEnv* env,
        jobject /* thiz */,
        jfloat current,
        jfloat target,
        jfloat velocity,
        jfloat stiffness,
        jfloat damping,
        jfloat dt,
        jfloatArray outVel) {
    float x = current;
    float v = velocity;
    if (dt > 0.0f) {
        int steps = std::max(1, static_cast<int>(std::ceil(dt / 0.006f)));
        float h = dt / static_cast<float>(steps);
        for (int i = 0; i < steps; ++i) {
            float accel = -stiffness * (x - target) - damping * v;
            v += accel * h;
            x += v * h;
        }
    }
    if (outVel != nullptr && env->GetArrayLength(outVel) > 0) {
        env->SetFloatArrayRegion(outVel, 0, 1, &v);
    }
    return x;
}

JNIEXPORT jstring JNICALL
Java_com_nevera_corporation_data_native_NeveraNativeEngine_nativeTransliterateKana(
        JNIEnv* env,
        jobject /* thiz */,
        jstring input) {
    if (input == nullptr) return env->NewStringUTF("");
    jsize len = env->GetStringLength(input);
    const jchar* chars = env->GetStringChars(input, nullptr);

    std::string result;
    result.reserve(static_cast<size_t>(len) * 2);

    bool geminateNext = false;
    for (jsize i = 0; i < len; ++i) {
        uint16_t raw = static_cast<uint16_t>(chars[i]);
        uint16_t hira = katakanaToHiragana(raw);

        if (raw == 0x30FC) {
            if (!result.empty()) {
                char last = result.back();
                if (last == 'a' || last == 'i' || last == 'u' || last == 'e' || last == 'o') {
                    result.push_back(last);
                }
            }
            continue;
        }

        if (hira == 0x3063) {
            geminateNext = true;
            continue;
        }

        if (i + 1 < len) {
            uint16_t nextHira = katakanaToHiragana(static_cast<uint16_t>(chars[i + 1]));
            std::string yoon = buildYoon(hira, nextHira);
            if (!yoon.empty()) {
                if (geminateNext) {
                    result.push_back(yoon[0] == 'c' ? 't' : yoon[0]);
                    geminateNext = false;
                }
                result += yoon;
                ++i;
                continue;
            }
        }

        const char* mapped = basicKanaLookup(hira);
        if (mapped != nullptr) {
            if (geminateNext) {
                char c0 = mapped[0];
                if (c0 != 'a' && c0 != 'i' && c0 != 'u' && c0 != 'e' && c0 != 'o') {
                    result.push_back(c0 == 'c' ? 't' : c0);
                }
                geminateNext = false;
            }
            if (hira == 0x3093 && i + 1 < len) {
                uint16_t nextH = katakanaToHiragana(static_cast<uint16_t>(chars[i + 1]));
                const char* nextRomaji = basicKanaLookup(nextH);
                if (nextRomaji && (nextRomaji[0] == 'a' || nextRomaji[0] == 'i' ||
                    nextRomaji[0] == 'u' || nextRomaji[0] == 'e' || nextRomaji[0] == 'o' ||
                    nextRomaji[0] == 'y')) {
                    result += "n'";
                    continue;
                }
            }
            result += mapped;
        } else {
            geminateNext = false;
            if (raw < 0x80) {
                result.push_back(static_cast<char>(raw));
            } else if (raw < 0x800) {
                result.push_back(static_cast<char>(0xC0 | (raw >> 6)));
                result.push_back(static_cast<char>(0x80 | (raw & 0x3F)));
            } else {
                result.push_back(static_cast<char>(0xE0 | (raw >> 12)));
                result.push_back(static_cast<char>(0x80 | ((raw >> 6) & 0x3F)));
                result.push_back(static_cast<char>(0x80 | (raw & 0x3F)));
            }
        }
    }

    env->ReleaseStringChars(input, chars);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jint JNICALL
Java_com_nevera_corporation_data_native_NeveraNativeEngine_nativeDetectJapaneseScript(
        JNIEnv* env,
        jobject /* thiz */,
        jstring input) {
    if (input == nullptr) return 0;
    jsize len = env->GetStringLength(input);
    const jchar* chars = env->GetStringChars(input, nullptr);
    int mask = 0;
    for (jsize i = 0; i < len; ++i) {
        uint16_t ch = static_cast<uint16_t>(chars[i]);
        if (isJapaneseKana(ch)) mask |= 1;
        if (isCjkIdeograph(ch)) mask |= 2;
        if (mask == 3) break;
    }
    env->ReleaseStringChars(input, chars);
    return mask;
}

JNIEXPORT jlong JNICALL
Java_com_nevera_corporation_data_native_NeveraNativeEngine_nativeDspCreate(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint sampleRate,
        jint channels) {
    auto* dsp = new NeveraStudioDsp();
    dsp->init(static_cast<int>(sampleRate), static_cast<int>(channels));
    return reinterpret_cast<jlong>(dsp);
}

JNIEXPORT void JNICALL
Java_com_nevera_corporation_data_native_NeveraNativeEngine_nativeDspDestroy(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    if (handle == 0) return;
    auto* dsp = reinterpret_cast<NeveraStudioDsp*>(handle);
    delete dsp;
}

JNIEXPORT void JNICALL
Java_com_nevera_corporation_data_native_NeveraNativeEngine_nativeDspReset(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    if (handle == 0) return;
    auto* dsp = reinterpret_cast<NeveraStudioDsp*>(handle);
    dsp->reset();
}

JNIEXPORT void JNICALL
Java_com_nevera_corporation_data_native_NeveraNativeEngine_nativeDspUpdateConfig(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jfloatArray params,
        jfloatArray freqs,
        jfloatArray gains,
        jfloatArray qs) {
    if (handle == 0 || params == nullptr || freqs == nullptr || gains == nullptr || qs == nullptr) return;
    auto* dsp = reinterpret_cast<NeveraStudioDsp*>(handle);
    jsize pLen = env->GetArrayLength(params);
    jsize bLen = std::min(env->GetArrayLength(freqs), std::min(env->GetArrayLength(gains), env->GetArrayLength(qs)));
    jfloat* pPtr = env->GetFloatArrayElements(params, nullptr);
    jfloat* fPtr = env->GetFloatArrayElements(freqs, nullptr);
    jfloat* gPtr = env->GetFloatArrayElements(gains, nullptr);
    jfloat* qPtr = env->GetFloatArrayElements(qs, nullptr);
    dsp->updateConfig(pPtr, static_cast<int>(pLen), fPtr, gPtr, qPtr, static_cast<int>(bLen));
    env->ReleaseFloatArrayElements(params, pPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(freqs, fPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(gains, gPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(qs, qPtr, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_nevera_corporation_data_native_NeveraNativeEngine_nativeDspProcessFloatInterleaved(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jfloatArray interleaved,
        jint frameCount) {
    if (handle == 0 || interleaved == nullptr || frameCount <= 0) return;
    auto* dsp = reinterpret_cast<NeveraStudioDsp*>(handle);
    jfloat* ptr = env->GetFloatArrayElements(interleaved, nullptr);
    const jsize arrLen = env->GetArrayLength(interleaved);
    const int maxFrames = static_cast<int>(arrLen) / std::max(1, dsp->channels);
    const int safeFrames = std::min(static_cast<int>(frameCount), maxFrames);
    dsp->processStereoInterleaved(ptr, safeFrames);
    env->ReleaseFloatArrayElements(interleaved, ptr, 0);
}

JNIEXPORT void JNICALL
Java_com_nevera_corporation_data_native_NeveraNativeEngine_nativeDspComputeCurveDb(
        JNIEnv* env,
        jobject /* thiz */,
        jfloatArray freqs,
        jfloatArray gains,
        jfloatArray qs,
        jint sampleRate,
        jfloatArray evalFreqs,
        jfloatArray outDb) {
    if (freqs == nullptr || gains == nullptr || qs == nullptr || evalFreqs == nullptr || outDb == nullptr) return;
    const jsize bLen = std::min(kDspBands, static_cast<int>(std::min(
            env->GetArrayLength(freqs), std::min(env->GetArrayLength(gains), env->GetArrayLength(qs)))));
    const jsize evalLen = std::min(env->GetArrayLength(evalFreqs), env->GetArrayLength(outDb));
    if (evalLen <= 0) return;

    jfloat* fPtr = env->GetFloatArrayElements(freqs, nullptr);
    jfloat* gPtr = env->GetFloatArrayElements(gains, nullptr);
    jfloat* qPtr = env->GetFloatArrayElements(qs, nullptr);
    jfloat* ePtr = env->GetFloatArrayElements(evalFreqs, nullptr);
    jfloat* oPtr = env->GetFloatArrayElements(outDb, nullptr);

    const double sr = static_cast<double>(std::max(8000, static_cast<int>(sampleRate)));
    Biquad64 bands[kDspBands];
    for (int i = 0; i < bLen; ++i) {
        const int kind = (i == 0) ? 0 : ((i == kDspBands - 1) ? 2 : 1);
        bands[i].configure(kind, sr, static_cast<double>(fPtr[i]), static_cast<double>(gPtr[i]), static_cast<double>(qPtr[i]));
    }
    for (jsize k = 0; k < evalLen; ++k) {
        double sumDb = 0.0;
        const double ef = std::clamp(static_cast<double>(ePtr[k]), 18.0, sr * 0.45);
        for (int i = 0; i < bLen; ++i) {
            sumDb += bands[i].responseDb(ef, sr);
        }
        oPtr[k] = std::isfinite(sumDb) ? static_cast<float>(std::clamp(sumDb, -18.0, 18.0)) : 0.0f;
    }

    env->ReleaseFloatArrayElements(freqs, fPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(gains, gPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(qs, qPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(evalFreqs, ePtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outDb, oPtr, 0);
}

} // extern "C"
