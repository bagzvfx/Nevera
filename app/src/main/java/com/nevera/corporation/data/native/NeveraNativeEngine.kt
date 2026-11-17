package com.nevera.corporation.data.native

import com.nevera.corporation.data.model.LyricWord
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
}
