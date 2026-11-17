#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr int kFftSize = 512;
constexpr int kNumBands = 32;

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

} // extern "C"
