package com.nevera.corporation.data.lyrics

import com.nevera.corporation.data.model.LyricLine
import com.nevera.corporation.data.model.LyricWord
import com.nevera.corporation.data.native.NeveraNativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Nevera Lyric AI Engine v2:
 * - Parses TTML, Enhanced LRC, LyricsPlus JSON, and standard LRC.
 * - Filters credit/composer header lines accurately.
 * - Uses C++17 Phonetic Syllable & Mora Weight Engine to synthesize word/syllable timings on LRC-only tracks.
 * - Inserts rhythmic instrumental interlude markers on >3.8s vocal breaks.
 * - Automatically generates accurate Hepburn Romaji for Japanese Kanji/Kana + on-demand translation.
 */
object NeveraLyricAiEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    var cacheDir: File? = null

    fun initCache(dir: File?) {
        if (dir == null) return
        val sub = File(dir, "nevera_romaji_v2")
        if (!sub.exists()) sub.mkdirs()
        cacheDir = sub
    }

    private val lrcTimestampRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val enhancedWordRegex = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>([^<\r\n]*)""")
    private val yrcLineHeaderRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val yrcWordRegex = Regex("""\((\d+),(\d+),\d+\)((?:(?!\(\d+,\d+,\d+\)).)*)""")
    private val metadataTagRegex = Regex("""^\[[a-zA-Z]+:.*]$""")
    private val creditLineRegex = Regex("""^(作词|作曲|编曲|混音|母带|制作人|Lyricist|Composer|Arranged by|Written by|Produced by)\s*[:：]""", RegexOption.IGNORE_CASE)

    private data class RawTimedSegment(
        val startMs: Long,
        val endMs: Long,
        val rawText: String,
        val hasSpaceBefore: Boolean = false
    )

    private fun buildLineAndWordSpans(segments: List<RawTimedSegment>): Pair<String, List<LyricWord>> {
        if (segments.isEmpty()) return "" to emptyList()
        val sb = StringBuilder()
        val words = mutableListOf<LyricWord>()
        var pendingSpace = false

        for (seg in segments) {
            val raw = seg.rawText
            val trimmed = raw.trim()
            val leadingWs = raw.firstOrNull()?.isWhitespace() == true
            val trailingWs = raw.lastOrNull()?.isWhitespace() == true

            if (trimmed.isEmpty()) {
                if (raw.isNotEmpty() || seg.hasSpaceBefore) pendingSpace = true
                continue
            }

            if (sb.isNotEmpty() && (pendingSpace || seg.hasSpaceBefore || leadingWs)) {
                if (!sb.last().isWhitespace()) sb.append(' ')
            }

            val cStart = sb.length
            sb.append(trimmed)
            val cEnd = sb.length
            words.add(
                LyricWord(
                    startMs = seg.startMs,
                    endMs = maxOf(seg.startMs + 50L, seg.endMs),
                    text = trimmed,
                    charStart = cStart,
                    charEnd = cEnd
                )
            )
            pendingSpace = trailingWs
        }
        return sb.toString() to words
    }

    internal fun parseFlexiblePayload(raw: String): List<LyricLine>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val unwrapped = runCatching {
            val el = json.parseToJsonElement(trimmed)
            if (el is JsonObject) {
                if (el["isError"]?.jsonPrimitive?.contentOrNull == "true") return null
                listOf("ttml", "ttmlContent", "lyrics", "lrc", "syncedLyrics", "yrc", "content", "data")
                    .firstNotNullOfOrNull { k ->
                        (el[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    } ?: trimmed
            } else trimmed
        }.getOrDefault(trimmed)

        return when {
            unwrapped.contains("<tt", ignoreCase = true) || unwrapped.contains("<p ", ignoreCase = true) ->
                parseTtml(unwrapped).takeIf { it.isNotEmpty() }
            yrcLineHeaderRegex.containsMatchIn(unwrapped.lineSequence().firstOrNull { it.trim().startsWith("[") }.orEmpty()) ->
                parseNetEaseYrc(unwrapped).takeIf { it.isNotEmpty() }
            else -> parseLrcOrEnhanced(unwrapped).takeIf { it.isNotEmpty() }
        }
    }

    internal fun parseNetEaseYrc(raw: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("{")) continue
            val header = yrcLineHeaderRegex.matchEntire(trimmed) ?: continue
            val lineStart = header.groupValues[1].toLongOrNull() ?: continue
            val lineDur = header.groupValues[2].toLongOrNull() ?: 0L
            val body = header.groupValues[3]
            val matches = yrcWordRegex.findAll(body).toList()
            if (matches.isEmpty()) continue

            val segments = matches.mapNotNull { m ->
                val ws = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val wd = (m.groupValues[2].toLongOrNull() ?: 160L).coerceAtLeast(50L)
                val tok = m.groupValues[3]
                RawTimedSegment(startMs = ws, endMs = ws + wd, rawText = tok)
            }
            val (lineText, words) = buildLineAndWordSpans(segments)
            if (lineText.isNotBlank() && !creditLineRegex.containsMatchIn(lineText)) {
                out.add(
                    LyricLine(
                        timeMs = lineStart,
                        text = lineText,
                        endTimeMs = if (lineDur > 0L) lineStart + lineDur else 0L,
                        words = words
                    )
                )
            }
        }
        return out.sortedBy { it.timeMs }
    }

    internal fun parseTtml(xml: String): List<LyricLine> {
        val pRegex = Regex("""<p\b([^>]*)>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val spanRegex = Regex("""<span\b([^>]*)>(.*?)</span>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val beginAttr = Regex("""\bbegin\s*=\s*"([^"]+)"""")
        val endAttr = Regex("""\bend\s*=\s*"([^"]+)"""")

        val out = mutableListOf<LyricLine>()
        for (pMatch in pRegex.findAll(xml)) {
            val attrs = pMatch.groupValues[1]
            val inner = pMatch.groupValues[2]
            val pBegin = beginAttr.find(attrs)?.groupValues?.get(1)?.let(::parseClockMs) ?: continue
            val pEnd = endAttr.find(attrs)?.groupValues?.get(1)?.let(::parseClockMs) ?: 0L

            val spans = spanRegex.findAll(inner).toList()
            val words: List<LyricWord>
            val lineText: String

            if (spans.isNotEmpty()) {
                val segments = mutableListOf<RawTimedSegment>()
                var gapCursor = 0
                var pendingGapSpace = false
                for (sp in spans) {
                    val gapRaw = decodeXmlEntities(
                        inner.substring(gapCursor, sp.range.first).replace(Regex("<[^>]+>"), "")
                    )
                    gapCursor = sp.range.last + 1
                    val gapHasSpace = pendingGapSpace || gapRaw.any { it.isWhitespace() }
                    pendingGapSpace = false

                    val sAttrs = sp.groupValues[1]
                    if (sAttrs.contains("x-bg", ignoreCase = true)) {
                        if (gapHasSpace) pendingGapSpace = true
                        continue
                    }

                    val wRaw = decodeXmlEntities(sp.groupValues[2].replace(Regex("<[^>]+>"), ""))
                    val wStart = beginAttr.find(sAttrs)?.groupValues?.get(1)?.let(::parseClockMs) ?: pBegin
                    val wEnd = endAttr.find(sAttrs)?.groupValues?.get(1)?.let(::parseClockMs) ?: (wStart + 250L)
                    segments.add(
                        RawTimedSegment(
                            startMs = wStart,
                            endMs = maxOf(wStart + 50L, wEnd),
                            rawText = wRaw,
                            hasSpaceBefore = gapHasSpace
                        )
                    )
                }
                val assembled = buildLineAndWordSpans(segments)
                lineText = assembled.first
                words = assembled.second
            } else {
                words = emptyList()
                lineText = decodeXmlEntities(inner.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
            }

            if (lineText.isNotBlank() && !creditLineRegex.containsMatchIn(lineText)) {
                out.add(LyricLine(timeMs = pBegin, text = lineText, endTimeMs = pEnd, words = words))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    internal fun parseLyricsPlusJson(raw: String): List<LyricLine>? {
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val arr = root["lyrics"]?.jsonArray ?: return null
            val out = mutableListOf<LyricLine>()
            for (el in arr) {
                val obj = el.jsonObject
                val time = obj["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: continue
                val dur = obj["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val syllabus = obj["syllabus"] as? JsonArray
                var words = emptyList<LyricWord>()
                var text = ""

                if (syllabus != null && syllabus.isNotEmpty()) {
                    val segments = mutableListOf<RawTimedSegment>()
                    for (sylEl in syllabus) {
                        val sObj = sylEl.jsonObject
                        val sText = sObj["text"]?.jsonPrimitive?.contentOrNull ?: continue
                        val sTime = sObj["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: time
                        val sDur = sObj["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 180L
                        segments.add(
                            RawTimedSegment(
                                startMs = sTime,
                                endMs = maxOf(sTime + 50L, sTime + sDur),
                                rawText = sText
                            )
                        )
                    }
                    val assembled = buildLineAndWordSpans(segments)
                    text = assembled.first
                    words = assembled.second
                }
                val explicitText = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isBlank()) {
                    text = explicitText
                } else if (explicitText.isNotBlank() && !explicitText.contains(' ') && text.contains(' ') && explicitText.replace(" ", "") == text.replace(" ", "")) {
                    // Prefer original canonical text when syllabus tokens accidentally split unspaced text
                    text = explicitText
                }
                if (text.isNotBlank() && !creditLineRegex.containsMatchIn(text)) {
                    out.add(
                        LyricLine(
                            timeMs = time,
                            text = text,
                            endTimeMs = if (dur > 0) time + dur else 0L,
                            words = words
                        )
                    )
                }
            }
            out.sortedBy { it.timeMs }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseLrcOrEnhanced(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || metadataTagRegex.matches(trimmed)) continue
            val tsMatches = lrcTimestampRegex.findAll(trimmed).toList()
            if (tsMatches.isEmpty()) continue

            val contentStart = tsMatches.last().range.last + 1
            val payload = trimmed.substring(contentStart)
            if (payload.isBlank() || creditLineRegex.containsMatchIn(payload.trim())) continue

            val inlineWords = enhancedWordRegex.findAll(payload).toList()
            val cleanText: String
            val parsedWords: List<LyricWord>
            if (inlineWords.isNotEmpty()) {
                val segments = mutableListOf<RawTimedSegment>()
                val prefix = payload.substring(0, inlineWords.first().range.first)
                if (prefix.isNotBlank()) {
                    val firstWordTime = parseFractionTimestampMs(
                        inlineWords.first().groupValues[1],
                        inlineWords.first().groupValues[2],
                        inlineWords.first().groupValues[3]
                    )
                    val baseTime = parseFractionTimestampMs(
                        tsMatches.last().groupValues[1],
                        tsMatches.last().groupValues[2],
                        tsMatches.last().groupValues[3]
                    )
                    segments.add(RawTimedSegment(baseTime, maxOf(baseTime + 60L, firstWordTime), prefix))
                }
                for (i in inlineWords.indices) {
                    val m = inlineWords[i]
                    val ws = parseFractionTimestampMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    val wRaw = m.groupValues[4]
                    val nextStart = inlineWords.getOrNull(i + 1)?.let {
                        parseFractionTimestampMs(it.groupValues[1], it.groupValues[2], it.groupValues[3])
                    } ?: (ws + 420L)
                    segments.add(RawTimedSegment(ws, maxOf(ws + 60L, nextStart), wRaw))
                }
                val assembled = buildLineAndWordSpans(segments)
                cleanText = assembled.first
                parsedWords = assembled.second
            } else {
                cleanText = payload.trim()
                parsedWords = emptyList()
            }

            if (cleanText.isNotBlank()) {
                for (ts in tsMatches) {
                    val startMs = parseFractionTimestampMs(ts.groupValues[1], ts.groupValues[2], ts.groupValues[3])
                    result.add(LyricLine(timeMs = startMs, text = cleanText, words = parsedWords))
                }
            }
        }
        return result.sortedBy { it.timeMs }
    }

    /**
     * AI Post-Processing Pipeline:
     * 1. Resolves natural line end-times and inserts breathing instrumental markers on long gaps (>3.8s).
     * 2. Runs C++ Phonetic Syllable Aligner whenever native word timestamps are absent.
     * 3. Automatically attaches Hepburn Romaji if Japanese script appears anywhere in the song.
     */
    suspend fun enrichLines(rawLines: List<LyricLine>, isSynced: Boolean): List<LyricLine> {
        if (rawLines.isEmpty()) return emptyList()

        val timed = rawLines.mapIndexed { idx, line ->
            val nextTime = rawLines.getOrNull(idx + 1)?.timeMs ?: (line.timeMs + 4200L)
            val maxNaturalWindow = if (idx < rawLines.lastIndex) {
                minOf(nextTime, line.timeMs + 8500L)
            } else {
                line.timeMs + 4500L
            }
            val resolvedEnd = if (line.endTimeMs > line.timeMs) {
                line.endTimeMs
            } else {
                maxNaturalWindow.coerceAtLeast(line.timeMs + 1200L)
            }
            val resolvedWords = if (isSynced && line.words.isEmpty()) {
                NeveraNativeEngine.allocateWordTimings(line.text, line.timeMs, resolvedEnd)
            } else {
                line.words
            }
            line.copy(endTimeMs = resolvedEnd, words = resolvedWords)
        }

        // Insert subtle instrumental markers on genuine vocal pauses (>3.8s gap)
        val withBreaks = mutableListOf<LyricLine>()
        if (isSynced && timed.first().timeMs > 4200L) {
            withBreaks.add(
                LyricLine(
                    timeMs = 0L,
                    endTimeMs = timed.first().timeMs,
                    text = "• • •",
                    isInstrumental = true
                )
            )
        }
        for (i in timed.indices) {
            val cur = timed[i]
            withBreaks.add(cur)
            val next = timed.getOrNull(i + 1)
            if (isSynced && next != null && (next.timeMs - cur.endTimeMs) > 3800L) {
                withBreaks.add(
                    LyricLine(
                        timeMs = cur.endTimeMs + 300L,
                        endTimeMs = next.timeMs,
                        text = "• • •",
                        isInstrumental = true
                    )
                )
            }
        }

        val hasJapanese = withBreaks.any { !it.isInstrumental && NeveraNativeEngine.hasJapaneseKana(it.text) }
        if (!hasJapanese) return withBreaks

        val sungIndices = withBreaks.indices.filter { !withBreaks[it].isInstrumental }
        val romajiTexts = batchTransliterate(sungIndices.map { withBreaks[it].text }, targetLang = "en", mode = "rm")
        val out = withBreaks.toMutableList()
        sungIndices.forEachIndexed { pos, listIdx ->
            val rm = romajiTexts.getOrNull(pos).orEmpty()
            val orig = out[listIdx].text
            if (rm.isNotBlank() && !rm.equals(orig, ignoreCase = true)) {
                out[listIdx] = out[listIdx].copy(romajiText = rm)
            }
        }
        return out
    }

    /** On-demand AI Translation preserving exact karaoke timestamps and word sweep data. */
    suspend fun translateLyrics(
        lines: List<LyricLine>,
        targetLangTag: String
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext lines
        val sungIndices = lines.indices.filter { !lines[it].isInstrumental && lines[it].text.isNotBlank() }
        val translatedTexts = batchTransliterate(
            sungIndices.map { lines[it].text },
            targetLang = targetLangTag,
            mode = "t"
        )
        val out = lines.toMutableList()
        sungIndices.forEachIndexed { pos, idx ->
            val tr = translatedTexts.getOrNull(pos).orEmpty()
            if (tr.isNotBlank()) {
                out[idx] = out[idx].copy(translatedText = tr)
            }
        }
        out
    }

    @Serializable
    private data class CachedBatch(val lines: List<String>)

    private suspend fun batchTransliterate(
        lines: List<String>,
        targetLang: String,
        mode: String
    ): List<String> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        val key = sha256("${mode}_${targetLang}_" + lines.joinToString("\n"))
        readDiskCache(key)?.let { cached ->
            if (cached.size == lines.size) return@withContext cached
        }

        val results = ArrayList<String>(lines.size)
        var ok = true
        for (chunk in lines.chunked(18)) {
            val joined = chunk.joinToString("\n")
            val remote = fetchTranslateEndpoint(joined, sl = if (mode == "rm") "ja" else "auto", tl = targetLang, dt = mode)
            if (remote != null) {
                val split = remote.split("\n")
                if (split.size == chunk.size) {
                    results.addAll(split.map { it.trim() })
                } else {
                    for (single in chunk) {
                        val singleRes = fetchTranslateEndpoint(single, sl = if (mode == "rm") "ja" else "auto", tl = targetLang, dt = mode)
                            ?: if (mode == "rm") NeveraNativeEngine.transliterateKanaToRomaji(single) else single
                        results.add(singleRes.trim())
                    }
                }
            } else {
                ok = false
                break
            }
        }

        val finalOut = if (ok && results.size == lines.size) {
            results
        } else if (mode == "rm") {
            lines.map { l -> if (NeveraNativeEngine.hasJapaneseKana(l)) NeveraNativeEngine.transliterateKanaToRomaji(l) else "" }
        } else {
            lines
        }

        writeDiskCache(key, finalOut)
        finalOut
    }

    private fun fetchTranslateEndpoint(text: String, sl: String, tl: String, dt: String): String? {
        return try {
            val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", sl)
                .addQueryParameter("tl", tl)
                .addQueryParameter("dt", dt)
                .addQueryParameter("q", text)
                .build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (dt == "rm") parseGoogleRomajiResponse(body) else parseGoogleTranslationResponse(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseGoogleRomajiResponse(rawJson: String): String? {
        return try {
            val root = json.parseToJsonElement(rawJson).jsonArray
            val sentences = root.getOrNull(0)?.jsonArray ?: return null
            val sb = StringBuilder()
            for (seg in sentences) {
                val arr = seg as? JsonArray ?: continue
                val rm = arr.getOrNull(3)?.jsonPrimitive?.contentOrNull
                if (!rm.isNullOrBlank()) {
                    if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")
                    sb.append(rm)
                }
            }
            sb.toString().trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseGoogleTranslationResponse(rawJson: String): String? {
        return try {
            val root = json.parseToJsonElement(rawJson).jsonArray
            val sentences = root.getOrNull(0)?.jsonArray ?: return null
            val sb = StringBuilder()
            for (seg in sentences) {
                val arr = seg as? JsonArray ?: continue
                val tr = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull
                if (!tr.isNullOrBlank()) sb.append(tr)
            }
            sb.toString().trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun readDiskCache(key: String): List<String>? {
        val dir = cacheDir ?: return null
        val file = File(dir, "$key.gz")
        if (!file.exists()) return null
        return try {
            GZIPInputStream(FileInputStream(file)).bufferedReader().use { r ->
                json.decodeFromString<CachedBatch>(r.readText()).lines
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDiskCache(key: String, lines: List<String>) {
        val dir = cacheDir ?: return
        try {
            val file = File(dir, "$key.gz")
            GZIPOutputStream(FileOutputStream(file)).bufferedWriter().use { w ->
                w.write(json.encodeToString(CachedBatch(lines)))
            }
        } catch (_: Exception) {
        }
    }

    private fun parseClockMs(raw: String): Long? {
        val s = raw.trim().removeSuffix("s")
        if (!s.contains(":")) {
            val sec = s.toDoubleOrNull() ?: return null
            return (sec * 1000.0).toLong()
        }
        val parts = s.split(":")
        return when (parts.size) {
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val sec = parts[1].toDoubleOrNull() ?: return null
                ((m * 60.0 + sec) * 1000.0).toLong()
            }
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val sec = parts[2].toDoubleOrNull() ?: return null
                (((h * 3600.0) + (m * 60.0) + sec) * 1000.0).toLong()
            }
            else -> null
        }
    }

    private fun parseFractionTimestampMs(minsStr: String, secsStr: String, fracStr: String): Long {
        val mins = minsStr.toLongOrNull() ?: 0L
        val secs = secsStr.toLongOrNull() ?: 0L
        val millis = when (fracStr.length) {
            0 -> 0L
            1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
            2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
            else -> (fracStr.take(3).toLongOrNull() ?: 0L)
        }
        return (mins * 60L + secs) * 1000L + millis
    }

    private fun decodeXmlEntities(text: String): String =
        text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }
}
