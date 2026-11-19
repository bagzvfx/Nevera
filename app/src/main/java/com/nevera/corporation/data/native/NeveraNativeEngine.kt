package com.nevera.corporation.data.native

import com.nevera.corporation.data.model.AudiophileDspProfile
import com.nevera.corporation.data.model.LyricWord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * JNI bridge to C++17 native engine (libnevera_native.so),
 * with pure-Kotlin fallback for JVM unit tests where Android .so libs aren't loaded.
 */
object NeveraNativeEngine {

    val isNativeLoaded: Boolean = try {
        System.loadLibrary("nevera_native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: Throwable) {
        false
    }

    private external fun nativeAllocateWordTimings(
        weights: DoubleArray,
        startMs: Long,
        endMs: Long
    ): LongArray

    private external fun nativeAnalyzeSpectrumAndBpm(
        monoPcm: FloatArray,
        sampleRate: Int,
        outBands: FloatArray,
        stateBuf: FloatArray
    )

    private external fun nativeTransliterateKana(input: String): String

    private external fun nativeDetectJapaneseScript(input: String): Int

    private external fun nativeDspCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeDspDestroy(handle: Long)
    private external fun nativeDspReset(handle: Long)
    private external fun nativeDspUpdateConfig(
        handle: Long,
        params: FloatArray,
        freqs: FloatArray,
        gains: FloatArray,
        qs: FloatArray
    )
    private external fun nativeDspProcessFloatInterleaved(
        handle: Long,
        interleaved: FloatArray,
        frameCount: Int
    )
    private external fun nativeDspComputeCurveDb(
        freqs: FloatArray,
        gains: FloatArray,
        qs: FloatArray,
        sampleRate: Int,
        evalFreqs: FloatArray,
        outDb: FloatArray
    )

    private val fallbackHandleSeq = AtomicLong(1L)
    private val fallbackDsps = ConcurrentHashMap<Long, FallbackStudioDsp>()

    fun dspCreate(sampleRate: Int, channels: Int): Long {
        return if (isNativeLoaded) {
            nativeDspCreate(sampleRate, channels)
        } else {
            val id = -fallbackHandleSeq.getAndIncrement()
            fallbackDsps[id] = FallbackStudioDsp(sampleRate, channels)
            id
        }
    }

    fun dspDestroy(handle: Long) {
        if (handle == 0L) return
        if (isNativeLoaded && handle > 0L) {
            nativeDspDestroy(handle)
        } else {
            fallbackDsps.remove(handle)
        }
    }

    fun dspReset(handle: Long) {
        if (handle == 0L) return
        if (isNativeLoaded && handle > 0L) {
            nativeDspReset(handle)
        } else {
            fallbackDsps[handle]?.reset()
        }
    }

    fun dspUpdateProfile(handle: Long, profile: AudiophileDspProfile) {
        if (handle == 0L) return
        val bands = profile.normalizedBands()
        val params = floatArrayOf(
            if (profile.enabled) 1f else 0f,
            if (profile.bitPerfectBypass) 1f else 0f,
            profile.preampDb,
            if (profile.autoPreamp) 1f else 0f,
            profile.crossfeedMode.fcutHz,
            profile.crossfeedMode.feedDb,
            profile.stereoWidth,
            profile.channelBalance,
            profile.tubeWarmth,
            if (profile.lufsNormalization) 1f else 0f,
            profile.targetLufs
        )
        val freqs = FloatArray(bands.size) { bands[it].frequencyHz }
        val gains = FloatArray(bands.size) { bands[it].gainDb }
        val qs = FloatArray(bands.size) { bands[it].qFactor }

        if (isNativeLoaded && handle > 0L) {
            nativeDspUpdateConfig(handle, params, freqs, gains, qs)
        } else {
            fallbackDsps[handle]?.updateConfig(params, freqs, gains, qs)
        }
    }

    fun dspProcessFloatInterleaved(handle: Long, interleaved: FloatArray, frameCount: Int) {
        if (handle == 0L || frameCount <= 0) return
        if (isNativeLoaded && handle > 0L) {
            nativeDspProcessFloatInterleaved(handle, interleaved, frameCount)
        } else {
            fallbackDsps[handle]?.processInterleaved(interleaved, frameCount)
        }
    }

    fun computeEqCurveDb(
        profile: AudiophileDspProfile,
        evalFreqs: FloatArray,
        sampleRate: Int = 48000
    ): FloatArray {
        val out = FloatArray(evalFreqs.size)
        if (evalFreqs.isEmpty()) return out
        if (profile.bitPerfectBypass || !profile.enabled) return out
        val bands = profile.normalizedBands()
        val freqs = FloatArray(bands.size) { bands[it].frequencyHz }
        val gains = FloatArray(bands.size) { bands[it].gainDb }
        val qs = FloatArray(bands.size) { bands[it].qFactor }
        if (isNativeLoaded) {
            nativeDspComputeCurveDb(freqs, gains, qs, sampleRate, evalFreqs, out)
        } else {
            val sr = max(8000, sampleRate).toDouble()
            val biquads = Array(bands.size) { idx ->
                FallbackBiquad64().apply {
                    val kind = when (idx) {
                        0 -> 0
                        bands.lastIndex -> 2
                        else -> 1
                    }
                    configure(kind, sr, freqs[idx].toDouble(), gains[idx].toDouble(), qs[idx].toDouble())
                }
            }
            for (k in evalFreqs.indices) {
                var sum = 0.0
                val ef = evalFreqs[k].toDouble().coerceIn(18.0, sr * 0.45)
                for (bq in biquads) sum += bq.responseDb(ef, sr)
                out[k] = if (sum.isFinite()) sum.coerceIn(-18.0, 18.0).toFloat() else 0f
            }
        }
        return out
    }

    fun allocateWordTimings(text: String, startMs: Long, endMs: Long): List<LyricWord> {
        val tokens = tokenizeWords(text)
        if (tokens.isEmpty()) return emptyList()
        val safeEnd = if (endMs > startMs + 250L) endMs else startMs + (tokens.size * 340L).coerceAtLeast(1600L)

        val weights = DoubleArray(tokens.size) { idx ->
            estimateTokenWeight(tokens[idx])
        }

        val pairs = if (isNativeLoaded) {
            nativeAllocateWordTimings(weights, startMs, safeEnd)
        } else {
            allocateFallback(weights, startMs, safeEnd)
        }

        val out = ArrayList<LyricWord>(tokens.size)
        var scanCursor = 0
        for (i in tokens.indices) {
            val tokenText = tokens[i]
            val s = pairs.getOrElse(i * 2) { startMs }
            val e = pairs.getOrElse(i * 2 + 1) { safeEnd }
            val foundIdx = text.indexOf(tokenText, startIndex = scanCursor)
            val cStart = if (foundIdx >= 0) foundIdx else scanCursor.coerceAtMost(text.length)
            val cEnd = (cStart + tokenText.length).coerceAtMost(text.length)
            scanCursor = cEnd
            out.add(LyricWord(startMs = s, endMs = e, text = tokenText, charStart = cStart, charEnd = cEnd))
        }
        return out
    }

    fun analyzeSpectrumAndBpm(
        monoPcm: FloatArray,
        sampleRate: Int,
        outBands: FloatArray,
        stateBuf: FloatArray
    ) {
        if (monoPcm.isEmpty() || outBands.size < 32 || stateBuf.size < 38 || sampleRate <= 0) return
        if (isNativeLoaded) {
            nativeAnalyzeSpectrumAndBpm(monoPcm, sampleRate, outBands, stateBuf)
        } else {
            analyzeSpectrumFallback(monoPcm, sampleRate, outBands, stateBuf)
        }
    }

    fun hasJapaneseKana(text: String): Boolean {
        return if (isNativeLoaded) {
            (nativeDetectJapaneseScript(text) and 1) != 0
        } else {
            text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
        }
    }

    fun hasCjkOrKanji(text: String): Boolean {
        return if (isNativeLoaded) {
            (nativeDetectJapaneseScript(text) and 2) != 0
        } else {
            text.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }
        }
    }

    fun isLikelyJapanese(text: String): Boolean = hasJapaneseKana(text)

    fun transliterateKanaToRomaji(text: String): String {
        if (text.isBlank()) return ""
        return if (isNativeLoaded) {
            nativeTransliterateKana(text).trim()
        } else {
            fallbackKanaToRomaji(text).trim()
        }
    }

    internal fun tokenizeWords(text: String): List<String> {
        val raw = text.trim()
        if (raw.isBlank()) return emptyList()
        val spaced = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (spaced.size >= 2 || raw.none { it in '\u3040'..'\u9FFF' }) {
            return spaced
        }
        val chunks = mutableListOf<String>()
        var buf = StringBuilder()
        for (ch in raw) {
            buf.append(ch)
            val isPunct = ch in "、。！？,.!?"
            if (buf.length >= 2 || isPunct) {
                chunks.add(buf.toString())
                buf = StringBuilder()
            }
        }
        if (buf.isNotEmpty()) chunks.add(buf.toString())
        return chunks
    }

    /**
     * AI Phonetic Syllable & Mora Weight Estimator:
     * Gives longer duration proportional to counted English/Indonesian vowel nuclei + CJK morae
     * and punctuation breath pauses so per-letter wipe glides organically even on line-LRC tracks.
     */
    internal fun estimateTokenWeight(token: String): Double {
        var w = 0.0
        var prevVowel = false
        for (ch in token) {
            val lower = ch.lowercaseChar()
            val isVowel = lower in "aeiouy"
            w += when {
                ch in '\u3040'..'\u30FF' -> 1.15
                ch in '\u4E00'..'\u9FFF' -> 1.75
                isVowel && !prevVowel -> 1.35 // Vowel nucleus = sung open syllable
                isVowel -> 0.55 // Sustained vowel diphthong
                ch in ",.;:!?、" -> 1.55 // Phrasing pause
                ch.isLetterOrDigit() -> 0.38
                else -> 0.25
            }
            prevVowel = isVowel
        }
        return max(0.5, w)
    }

    private fun analyzeSpectrumFallback(
        pcm: FloatArray,
        sampleRate: Int,
        out: FloatArray,
        st: FloatArray
    ) {
        val n = 512
        val bands = 32
        val real = FloatArray(n)
        val imag = FloatArray(n)
        val copyLen = min(pcm.size, n)
        for (i in 0 until n) {
            val s = if (i < copyLen) pcm[i] else 0f
            val hann = 0.5f * (1f - cos(2f * PI.toFloat() * i / (n - 1)))
            real[i] = s * hann
        }

        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenR = cos(ang).toFloat()
            val wlenI = sin(ang).toFloat()
            val half = len shr 1
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until half) {
                    val even = i + k
                    val odd = even + half
                    val tR = real[odd] * wR - imag[odd] * wI
                    val tI = real[odd] * wI + imag[odd] * wR
                    real[odd] = real[even] - tR
                    imag[odd] = imag[even] - tI
                    real[even] += tR
                    imag[even] += tI
                    val nextR = wR * wlenR - wI * wlenI
                    wI = wR * wlenI + wI * wlenR
                    wR = nextR
                }
                i += len
            }
            len = len shl 1
        }

        val sr = sampleRate.toFloat()
        val fMin = 34.0
        val fBassTop = 220.0
        val fVocalTop = 3400.0
        val fMax = min(16000.0, sr * 0.465)
        val halfBins = n / 2
        val raw = FloatArray(bands)
        var bassMid = 0f

        for (b in 0 until bands) {
            val f0: Double
            val f1: Double
            val gainScale: Double
            when {
                b < 10 -> {
                    val t0 = b.toDouble() / 10.0
                    val t1 = (b + 1).toDouble() / 10.0
                    f0 = fMin * (fBassTop / fMin).pow(t0)
                    f1 = fMin * (fBassTop / fMin).pow(t1)
                    gainScale = 0.95 + 0.12 * t0
                }
                b < 22 -> {
                    val t0 = (b - 10).toDouble() / 12.0
                    val t1 = (b - 9).toDouble() / 12.0
                    f0 = fBassTop * (fVocalTop / fBassTop).pow(t0)
                    f1 = fBassTop * (fVocalTop / fBassTop).pow(t1)
                    gainScale = 1.28 + 0.25 * sin(t0 * PI)
                }
                else -> {
                    val t0 = (b - 22).toDouble() / 10.0
                    val t1 = (b - 21).toDouble() / 10.0
                    val safeTop = max(fVocalTop + 600.0, fMax)
                    f0 = fVocalTop * (safeTop / fVocalTop).pow(t0)
                    f1 = fVocalTop * (safeTop / fVocalTop).pow(t1)
                    gainScale = 1.55 + 0.45 * t0
                }
            }
            val binStart = max(1, floor(f0 * n / sr).toInt())
            val binEnd = min(halfBins - 1, max(binStart, kotlin.math.ceil(f1 * n / sr).toInt()))
            var sumSq = 0f
            var cnt = 0
            for (k in binStart..binEnd) {
                val mag = sqrt(real[k] * real[k] + imag[k] * imag[k]) / (n * 0.5f)
                sumSq += mag
                cnt++
            }
            val avgMag = if (cnt > 0) sumSq / cnt else 0f
            val comp = (ln(1.0 + avgMag * 54.0 * gainScale) / ln(1.0 + 54.0 * 0.55)).toFloat()
            raw[b] = if (comp.isFinite()) comp.coerceIn(0f, 1f) else 0f
            if (b < 10) bassMid += raw[b]
        }

        for (b in 0 until bands) {
            val zStart = if (b < 10) 0 else if (b < 22) 10 else 22
            val zEnd = if (b < 10) 9 else if (b < 22) 21 else 31
            val left = if (b > zStart) raw[b - 1] else raw[b]
            val cur = raw[b]
            val right = if (b < zEnd) raw[b + 1] else raw[b]
            val bl = 0.14f * left + 0.72f * cur + 0.14f * right
            val prev = st[b]
            val attack = if (b < 10) 0.62f else 0.58f
            val decay = if (b < 10) 0.21f else 0.25f
            val coeff = if (bl > prev) attack else decay
            val sm = prev + (bl - prev) * coeff
            st[b] = sm
            out[b] = sm
        }

        val hopMs = (copyLen.toFloat() / sr) * 1000f
        st[35] += hopMs
        val avgBass = bassMid / 10f
        val flux = max(0f, avgBass - st[32])
        st[32] = avgBass
        st[33] = st[33] * 0.90f + flux * 0.10f
        st[37] = max(0f, st[37] * 0.86f)
        val elapsed = st[35] - st[34]
        if (flux > max(0.045f, st[33] * 2.15f) && elapsed > 290f) {
            if (elapsed < 1050f) {
                var instBpm = 60000f / elapsed
                while (instBpm < 70f) instBpm *= 2f
                while (instBpm > 176f) instBpm *= 0.5f
                st[36] = if (st[36] < 40f) instBpm else st[36] * 0.82f + instBpm * 0.18f
            }
            st[34] = st[35]
            st[37] = 1f
        }
    }

    private fun allocateFallback(weights: DoubleArray, startMs: Long, endMs: Long): LongArray {
        val n = weights.size
        val total = weights.sum().coerceAtLeast(1.0)
        val activeSpan = (endMs - startMs) * 0.96
        val res = LongArray(n * 2)
        var acc = 0.0
        for (i in 0 until n) {
            val w = weights[i].coerceAtLeast(0.25)
            val ws = startMs + ((acc / total) * activeSpan).toLong()
            acc += w
            var we = startMs + ((acc / total) * activeSpan).toLong()
            if (we <= ws) we = ws + 60L
            if (we > endMs) we = endMs
            res[i * 2] = ws
            res[i * 2 + 1] = we
        }
        return res
    }

    private fun fallbackKanaToRomaji(input: String): String {
        val sb = StringBuilder(input.length * 2)
        var geminate = false
        var i = 0
        while (i < input.length) {
            val raw = input[i]
            val hira = if (raw in '\u30A1'..'\u30F6') (raw.code - 0x60).toChar() else raw
            if (raw == 'ー') {
                if (sb.isNotEmpty() && sb.last() in "aiueo") sb.append(sb.last())
                i++; continue
            }
            if (hira == 'っ') {
                geminate = true
                i++; continue
            }
            val mapped = KANA_MAP[hira]
            if (mapped != null) {
                if (geminate && mapped.isNotEmpty() && mapped[0] !in "aiueo") {
                    sb.append(if (mapped[0] == 'c') 't' else mapped[0])
                }
                geminate = false
                sb.append(mapped)
            } else {
                geminate = false
                sb.append(raw)
            }
            i++
        }
        return sb.toString()
    }

    private val KANA_MAP = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'が' to "ga", 'き' to "ki", 'ぎ' to "gi", 'く' to "ku", 'ぐ' to "gu",
        'け' to "ke", 'げ' to "ge", 'こ' to "ko", 'ご' to "go",
        'さ' to "sa", 'ざ' to "za", 'し' to "shi", 'じ' to "ji", 'す' to "su", 'ず' to "zu",
        'せ' to "se", 'ぜ' to "ze", 'そ' to "so", 'ぞ' to "zo",
        'た' to "ta", 'だ' to "da", 'ち' to "chi", 'ぢ' to "ji", 'つ' to "tsu", 'づ' to "zu",
        'て' to "te", 'で' to "de", 'と' to "to", 'ど' to "do",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ば' to "ba", 'ぱ' to "pa", 'ひ' to "hi", 'び' to "bi", 'ぴ' to "pi",
        'ふ' to "fu", 'ぶ' to "bu", 'ぷ' to "pu", 'へ' to "he", 'べ' to "be", 'ぺ' to "pe",
        'ほ' to "ho", 'ぼ' to "bo", 'ぽ' to "po",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'ゃ' to "ya", 'や' to "ya", 'ゅ' to "yu", 'ゆ' to "yu", 'ょ' to "yo", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'を' to "wo", 'ん' to "n", 'ゔ' to "vu"
    )

    private class FallbackBiquad64 {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0
        var a1 = 0.0; var a2 = 0.0
        var z1 = 0.0; var z2 = 0.0
        var active = false

        fun reset() { z1 = 0.0; z2 = 0.0 }

        fun setIdentity() {
            b0 = 1.0; b1 = 0.0; b2 = 0.0
            a1 = 0.0; a2 = 0.0
            active = false
        }

        fun configure(kind: Int, sampleRate: Double, freqHz: Double, gainDb: Double, q: Double) {
            if (sampleRate <= 1000.0 || abs(gainDb) < 0.02) {
                setIdentity()
                return
            }
            val safeF = freqHz.coerceIn(18.0, sampleRate * 0.45)
            val safeQ = q.coerceIn(0.35, 8.0)
            val safeG = gainDb.coerceIn(-15.0, 15.0)
            val a = 10.0.pow(safeG / 40.0)
            val w0 = 2.0 * PI * safeF / sampleRate
            val cosW = cos(w0)
            val sinW = sin(w0)
            val alpha = sinW / (2.0 * safeQ)

            val nb0: Double; val nb1: Double; val nb2: Double
            val na0: Double; val na1: Double; val na2: Double
            when (kind) {
                0 -> {
                    val shelfRad = (a + 1.0 / a) * (1.0 / safeQ.coerceIn(0.4, 2.0) - 1.0) + 2.0
                    val sAlpha = if (shelfRad > 0.0) 2.0 * sqrt(a) * (sinW / 2.0) * sqrt(shelfRad) else 2.0 * sqrt(a) * alpha
                    nb0 = a * ((a + 1.0) - (a - 1.0) * cosW + sAlpha)
                    nb1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)
                    nb2 = a * ((a + 1.0) - (a - 1.0) * cosW - sAlpha)
                    na0 = (a + 1.0) + (a - 1.0) * cosW + sAlpha
                    na1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW)
                    na2 = (a + 1.0) + (a - 1.0) * cosW - sAlpha
                }
                2 -> {
                    val shelfRad = (a + 1.0 / a) * (1.0 / safeQ.coerceIn(0.4, 2.0) - 1.0) + 2.0
                    val sAlpha = if (shelfRad > 0.0) 2.0 * sqrt(a) * (sinW / 2.0) * sqrt(shelfRad) else 2.0 * sqrt(a) * alpha
                    nb0 = a * ((a + 1.0) + (a - 1.0) * cosW + sAlpha)
                    nb1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)
                    nb2 = a * ((a + 1.0) + (a - 1.0) * cosW - sAlpha)
                    na0 = (a + 1.0) - (a - 1.0) * cosW + sAlpha
                    na1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW)
                    na2 = (a + 1.0) - (a - 1.0) * cosW - sAlpha
                }
                else -> {
                    nb0 = 1.0 + alpha * a
                    nb1 = -2.0 * cosW
                    nb2 = 1.0 - alpha * a
                    na0 = 1.0 + alpha / a
                    na1 = -2.0 * cosW
                    na2 = 1.0 - alpha / a
                }
            }
            if (abs(na0) < 1e-12) { setIdentity(); return }
            b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0
            a1 = na1 / na0; a2 = na2 / na0
            active = true
        }

        fun process(x: Double): Double {
            if (!active) return x
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun responseDb(freqHz: Double, sampleRate: Double): Double {
            if (!active || sampleRate <= 1000.0) return 0.0
            val w = 2.0 * PI * freqHz / sampleRate
            val cw = cos(w); val sw = sin(w)
            val c2w = cos(2.0 * w); val s2w = sin(2.0 * w)
            val numR = b0 + b1 * cw + b2 * c2w
            val numI = -b1 * sw - b2 * s2w
            val denR = 1.0 + a1 * cw + a2 * c2w
            val denI = -a1 * sw - a2 * s2w
            val numSq = numR * numR + numI * numI
            val denSq = max(1e-18, denR * denR + denI * denI)
            return 10.0 * log10(max(1e-18, numSq / denSq))
        }
    }

    private class FallbackStudioDsp(sr: Int, ch: Int) {
        private val sampleRate = max(8000, sr).toDouble()
        private val channels = ch.coerceIn(1, 8)
        private var enabled = true
        private var bitPerfectBypass = false
        private var autoPreamp = true
        private var lufsNorm = true
        private var preampLinear = 1.0
        private var stereoWidth = 1.0
        private var balL = 1.0
        private var balR = 1.0
        private var tubeWarmth = 0.0
        private var targetLufsLinear = 0.20
        private val eqL = Array(10) { FallbackBiquad64() }
        private val eqR = Array(10) { FallbackBiquad64() }
        private var crossfeedActive = false
        private var cfLpA0 = 0.0
        private var cfLpB1 = 0.0
        private var cfCrossGain = 0.0
        private var cfDirectGain = 1.0
        private var cfStateL = 0.0
        private var cfStateR = 0.0
        private var lufsEnergy = 0.04
        private var lufsGain = 1.0
        private var dcPrevInL = 0.0; private var dcPrevOutL = 0.0
        private var dcPrevInR = 0.0; private var dcPrevOutR = 0.0
        private var limEnv = 0.0

        fun reset() {
            eqL.forEach { it.reset() }
            eqR.forEach { it.reset() }
            cfStateL = 0.0; cfStateR = 0.0
            lufsEnergy = 0.04; lufsGain = 1.0
            dcPrevInL = 0.0; dcPrevOutL = 0.0
            dcPrevInR = 0.0; dcPrevOutR = 0.0
            limEnv = 0.0
        }

        fun updateConfig(params: FloatArray, freqs: FloatArray, gains: FloatArray, qs: FloatArray) {
            if (params.size < 11) return
            enabled = params[0] > 0.5f
            bitPerfectBypass = params[1] > 0.5f
            val manualPreamp = params[2].toDouble().coerceIn(-12.0, 6.0)
            autoPreamp = params[3] > 0.5f
            val cfFcut = params[4].toDouble()
            val cfFeed = params[5].toDouble()
            stereoWidth = params[6].toDouble().coerceIn(0.5, 1.6)
            val balance = params[7].toDouble().coerceIn(-1.0, 1.0)
            tubeWarmth = params[8].toDouble().coerceIn(0.0, 1.0)
            lufsNorm = params[9] > 0.5f
            targetLufsLinear = 10.0.pow(params[10].toDouble().coerceIn(-23.0, -9.0) / 20.0)
            balL = if (balance > 0.0) 1.0 - balance else 1.0
            balR = if (balance < 0.0) 1.0 + balance else 1.0

            var maxPosGain = 0.0
            val n = min(10, min(freqs.size, min(gains.size, qs.size)))
            for (i in 0 until 10) {
                if (i < n) {
                    val kind = if (i == 0) 0 else if (i == 9) 2 else 1
                    val g = gains[i].toDouble()
                    if (g > maxPosGain) maxPosGain = g
                    eqL[i].configure(kind, sampleRate, freqs[i].toDouble(), g, qs[i].toDouble())
                    eqR[i].configure(kind, sampleRate, freqs[i].toDouble(), g, qs[i].toDouble())
                } else {
                    eqL[i].setIdentity(); eqR[i].setIdentity()
                }
            }
            var effPreamp = manualPreamp
            if (autoPreamp && maxPosGain > 0.1) effPreamp = min(manualPreamp, -(maxPosGain + 0.4))
            preampLinear = 10.0.pow(effPreamp / 20.0)

            if (cfFcut > 50.0 && cfFeed > 0.1) {
                crossfeedActive = true
                val x = exp(-2.0 * PI * cfFcut / sampleRate)
                cfLpA0 = 1.0 - x
                cfLpB1 = x
                cfCrossGain = 10.0.pow(-cfFeed / 20.0)
                cfDirectGain = 1.0 / (1.0 + cfCrossGain * 0.65)
            } else {
                crossfeedActive = false
                cfCrossGain = 0.0
                cfDirectGain = 1.0
            }
        }

        fun processInterleaved(buf: FloatArray, frameCount: Int) {
            if (!enabled || bitPerfectBypass || frameCount <= 0) return
            val safeFrames = min(frameCount, buf.size / channels)
            val limAttack = exp(-1.0 / (0.0005 * sampleRate))
            val limRelease = exp(-1.0 / (0.065 * sampleRate))
            val lufsAlpha = 1.0 - exp(-1.0 / (1.2 * sampleRate))
            val lufsGainSlew = 1.0 - exp(-1.0 / (0.35 * sampleRate))
            val dcCoeff = 1.0 - (2.0 * PI * 8.0 / sampleRate)
            val ceiling = 0.966

            if (channels == 1) {
                for (f in 0 until safeFrames) {
                    var x = buf[f].toDouble() * preampLinear
                    for (b in 0 until 10) x = eqL[b].process(x)
                    val absX = abs(x)
                    limEnv = if (absX > limEnv) limAttack * limEnv + (1.0 - limAttack) * absX else limRelease * limEnv + (1.0 - limRelease) * absX
                    if (limEnv > ceiling) x *= (ceiling / limEnv)
                    if (abs(x) > 0.985) x = tanh(x) * 0.985
                    buf[f] = if (x.isFinite()) x.coerceIn(-0.99, 0.99).toFloat() else 0f
                }
                return
            }

            for (f in 0 until safeFrames) {
                val base = f * channels
                var l = buf[base].toDouble() * preampLinear
                var r = buf[base + 1].toDouble() * preampLinear
                for (b in 0 until 10) {
                    l = eqL[b].process(l)
                    r = eqR[b].process(r)
                }
                if (crossfeedActive) {
                    cfStateL = cfLpA0 * l + cfLpB1 * cfStateL
                    cfStateR = cfLpA0 * r + cfLpB1 * cfStateR
                    val outL = l * cfDirectGain + cfStateR * cfCrossGain * cfDirectGain
                    val outR = r * cfDirectGain + cfStateL * cfCrossGain * cfDirectGain
                    l = outL; r = outR
                }
                if (abs(stereoWidth - 1.0) > 0.005 || balL < 0.999 || balR < 0.999) {
                    val mid = 0.5 * (l + r)
                    val side = 0.5 * (l - r) * stereoWidth
                    l = (mid + side) * balL
                    r = (mid - side) * balR
                }
                if (lufsNorm) {
                    val instPower = 0.5 * (l * l + r * r)
                    if (instPower > 3.16e-6) {
                        lufsEnergy += lufsAlpha * (instPower - lufsEnergy)
                        val currentRms = sqrt(max(1e-8, lufsEnergy))
                        val desired = (targetLufsLinear / currentRms).coerceIn(0.40, 2.0)
                        lufsGain += lufsGainSlew * (desired - lufsGain)
                    }
                    l *= lufsGain; r *= lufsGain
                }
                if (tubeWarmth > 0.01) {
                    val excL = l + tubeWarmth * 0.14 * (l * l - 0.5 * l * l * l)
                    val excR = r + tubeWarmth * 0.14 * (r * r - 0.5 * r * r * r)
                    val dcL = excL - dcPrevInL + dcCoeff * dcPrevOutL
                    val dcR = excR - dcPrevInR + dcCoeff * dcPrevOutR
                    dcPrevInL = excL; dcPrevOutL = dcL
                    dcPrevInR = excR; dcPrevOutR = dcR
                    l = dcL; r = dcR
                }
                val peak = max(abs(l), abs(r))
                limEnv = if (peak > limEnv) limAttack * limEnv + (1.0 - limAttack) * peak else limRelease * limEnv + (1.0 - limRelease) * peak
                if (limEnv > ceiling) {
                    val gr = ceiling / limEnv
                    l *= gr; r *= gr
                }
                if (abs(l) > 0.985) l = tanh(l) * 0.985
                if (abs(r) > 0.985) r = tanh(r) * 0.985
                buf[base] = if (l.isFinite()) l.coerceIn(-0.99, 0.99).toFloat() else 0f
                buf[base + 1] = if (r.isFinite()) r.coerceIn(-0.99, 0.99).toFloat() else 0f
            }
        }
    }
}
