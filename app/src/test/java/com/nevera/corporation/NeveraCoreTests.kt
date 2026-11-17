package com.nevera.corporation

import com.nevera.corporation.data.lyrics.LyricsRepository
import com.nevera.corporation.data.lyrics.NeveraLyricAiEngine
import com.nevera.corporation.data.lyrics.ParallelLyricSources
import com.nevera.corporation.data.model.AppLanguage
import com.nevera.corporation.data.model.AppSettingsState
import com.nevera.corporation.data.model.LyricLine
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import com.nevera.corporation.data.model.VisualizerStyle
import com.nevera.corporation.data.native.NeveraNativeEngine
import com.nevera.corporation.data.sources.AccountSyncManager
import com.nevera.corporation.data.sources.InnertubeClient
import com.nevera.corporation.data.sources.JioSaavnService
import com.nevera.corporation.data.sources.PlaylistImportEngine
import com.nevera.corporation.data.sources.RecommendationEngine
import com.nevera.corporation.data.sources.TrackMatcher
import com.nevera.corporation.playback.SpectrumAudioBus
import com.nevera.corporation.playback.SyncedAudioSample
import com.nevera.corporation.ui.i18n.AppLocalization
import com.nevera.corporation.ui.player.LyricClock
import com.nevera.corporation.ui.player.computeInstrumentalDotsState
import com.nevera.corporation.ui.player.computeLineRevealedFraction
import com.nevera.corporation.ui.vfx.AcousticRippleParticle
import com.nevera.corporation.ui.vfx.HarmonicFxController
import com.nevera.corporation.ui.vfx.HarmonicSpring
import com.nevera.corporation.ui.vfx.SpecularTouchParticle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import kotlin.math.PI
import kotlin.math.sin

class NeveraCoreTests {

    @Test
    fun testTrackMatcherNormalizationAndScoring() {
        val clean = TrackMatcher.normalizeTitle("Die With A Smile (Official Music Video)")
        assertEquals("die with a smile", clean)

        val candidate = Song(
            id = "1",
            title = "Die With A Smile",
            artist = "Lady Gaga, Bruno Mars",
            durationSeconds = 251,
            source = SourceKind.JIOSAAVN
        )
        val wrong = Song(
            id = "2",
            title = "Completely Different Song",
            artist = "Other Artist",
            durationSeconds = 120,
            source = SourceKind.YOUTUBE
        )

        val picked = TrackMatcher.pickBestMatch(
            targetTitle = "Die With A Smile (Lyrics)",
            targetArtist = "Lady Gaga & Bruno Mars",
            targetDurationSec = 252,
            candidates = listOf(wrong, candidate)
        )
        assertNotNull(picked)
        assertEquals("1", picked?.id)
    }

    @Test
    fun testJioSaavnUrlSelectionAndDetailsParsing() {
        val url = "https://aac.saavncdn.com/123/abc_96.mp4?expires=12345"
        val best = JioSaavnService.selectBestStream(url, supports320 = true, maxKbps = 320)
        assertNotNull(best)
        assertEquals(320, best?.kbps)
        assertEquals("https://aac.saavncdn.com/123/abc_320.mp4?expires=12345", best?.url)

        val mapEnvelope = """
            {
              "VaNhRJHr": {
                "id": "VaNhRJHr",
                "title": "Test Track Map",
                "subtitle": "Artist A",
                "more_info": {
                  "encrypted_media_url": "abc123==",
                  "duration": "210",
                  "320kbps": "true"
                }
              }
            }
        """.trimIndent()
        val fromMap = JioSaavnService.parseDetailsPayload(mapEnvelope)
        assertNotNull(fromMap)
        assertEquals("VaNhRJHr", fromMap?.id)
        assertTrue(fromMap?.moreInfo?.supports320 == true)
    }

    @Test
    fun testLyricAiParsingWordTimingAndJapaneseRomaji() {
        val raw = """
            [ti:Sample]
            [00:12.50]First line here
            [01:05.200]Second line here
            [00:05.00]Intro line
        """.trimIndent()
        val parsed = LyricsRepository.parseLrc(raw)
        assertEquals(3, parsed.size)
        assertEquals(5000L, parsed[0].timeMs)
        assertEquals("Intro line", parsed[0].text)

        val words = NeveraNativeEngine.allocateWordTimings("Hello beautiful world", 1000L, 4000L)
        assertEquals(3, words.size)
        assertEquals(1000L, words.first().startMs)
        assertTrue(words.last().endMs <= 4000L)

        // Character-level progressive reveal fraction check
        val line = LyricLine(timeMs = 1000L, endTimeMs = 4000L, text = "Hello beautiful world", words = words)
        val midFrac = computeLineRevealedFraction(posMs = 2500L, line = line, renderedText = line.text, isActive = true, isPassed = false)
        assertTrue("Expected mid reveal in (0.2, 0.8), got $midFrac", midFrac in 0.2f..0.8f)

        val romaji = NeveraNativeEngine.transliterateKanaToRomaji("きらきら ひかる")
        assertTrue(romaji.contains("kira"))
        assertTrue(romaji.contains("hikaru"))
        assertTrue(NeveraNativeEngine.hasJapaneseKana("きらきら"))
        assertFalse(NeveraNativeEngine.hasJapaneseKana("Hello world"))

        val ttml = """
            <tt><body><div>
              <p begin="00:01.000" end="00:04.000">
                <span begin="00:01.000" end="00:02.000">Hello</span>
                <span begin="00:02.100" end="00:03.900">World</span>
              </p>
            </div></body></tt>
        """.trimIndent()
        val ttmlLines = NeveraLyricAiEngine.parseTtml(ttml)
        assertEquals(1, ttmlLines.size)
        assertEquals("Hello World", ttmlLines[0].text)
        assertEquals(2, ttmlLines[0].words.size)
    }

    @Test
    fun testParallelSourceParsers() {
        val simpPayload = """
            {
              "success": true,
              "data": [
                {
                  "duration": 200,
                  "richSyncLyrics": "[00:01.00]&lt;00:01.00&gt;Night &lt;00:01.50&gt;glows"
                }
              ]
            }
        """.trimIndent()
        val simp = ParallelLyricSources.parseSimpMusicPayload(simpPayload, durationSec = 200)
        assertNotNull(simp)
        assertTrue(simp!!.hasNativeWordSync)
        assertEquals("Night glows", simp.lines.first().text)

        val b64Lrc = Base64.getEncoder().encodeToString("[00:10.00]Wave line".toByteArray(Charsets.UTF_8))
        val kgRaw = """{"content":"$b64Lrc"}"""
        val decoded = ParallelLyricSources.parseKuGouDownloadPayload(kgRaw)
        assertEquals("[00:10.00]Wave line", decoded)
    }

    @Test
    fun testFftSpectrumAnalyzerAndSinePeak() {
        val sr = 44100
        val pcm = FloatArray(512) { i ->
            (0.8 * sin(2.0 * PI * 440.0 * i / sr)).toFloat()
        }
        val bands = FloatArray(32)
        val state = FloatArray(40)
        repeat(4) {
            NeveraNativeEngine.analyzeSpectrumAndBpm(pcm, sr, bands, state)
        }
        for (b in bands) {
            assertTrue("Band value must be finite and within [0,1]: $b", b.isFinite() && b in 0f..1f)
        }
        val peakIdx = bands.indices.maxByOrNull { bands[it] } ?: -1
        assertTrue("440 Hz tone should peak in lower-mid bands (index 8..16), got $peakIdx", peakIdx in 8..16)
    }

    @Test
    fun testLyricClockSmoothnessAndSeekJump() {
        val clock = LyricClock(startMs = 0L)
        var out = clock.frame(nowMs = 100.0, reportedMs = 1000L, sampledAtMs = 100.0, discontinuity = 1L)
        assertEquals(1000L, out)

        // Small forward frames + jitter rejection
        out = clock.frame(nowMs = 116.0, reportedMs = 1016L, sampledAtMs = 116.0, discontinuity = 1L)
        assertTrue(out >= 1000L)

        // Deliberate seek jump resets cleanly
        out = clock.frame(nowMs = 200.0, reportedMs = 45000L, sampledAtMs = 200.0, discontinuity = 2L)
        assertEquals(45000L, out)
    }

    @Test
    fun testHarmonicSpringConvergence() {
        val spring = HarmonicSpring(x = 0f, k = 280f, d = 26f)
        spring.to = 1.0f
        repeat(120) {
            spring.step(0.016f)
        }
        assertTrue("HarmonicSpring should settle near target 1.0, got ${spring.x}", kotlin.math.abs(spring.x - 1.0f) < 0.02f)
    }

    @Test
    fun testPersonalizationAndPlaylistImportParsing() {
        val top = RecommendationEngine.computeTopArtists(
            artistPlayCounts = mapOf("RADWIMPS" to 5, "NIKI" to 2),
            likedSongs = listOf(Song(id = "s1", title = "A", artist = "RADWIMPS")),
            recentlyPlayed = emptyList()
        )
        assertEquals("RADWIMPS", top.firstOrNull())

        val ytId = PlaylistImportEngine.extractYouTubePlaylistId("https://music.youtube.com/playlist?list=PL12345_abc")
        assertEquals("PL12345_abc", ytId)
        assertTrue(PlaylistImportEngine.isSpotifyPlaylistOrAlbum("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))

        assertEquals("abc", AccountSyncManager.extractSapisid("PREF=1; SAPISID=abc; SID=xyz"))
    }

    @Test
    fun testLocalizationAndDefaultEnglish() {
        val defaults = AppSettingsState()
        assertEquals(AppLanguage.ENGLISH, defaults.appLanguage)
        assertEquals(VisualizerStyle.UPWARD_BARS, defaults.visualizerStyle)

        for (lang in AppLanguage.values()) {
            val s = AppLocalization.stringsFor(lang)
            assertTrue(s.navHome.isNotBlank())
            assertTrue(s.translateLabel.isNotBlank())
            assertTrue(s.visualizerLabel.isNotBlank())
            assertTrue(s.syncOffsetLabel.isNotBlank())
        }
    }

    @Test
    fun testDurationParsing() {
        assertEquals(213L, InnertubeClient.parseDurationToSec("3:33"))
        assertEquals(213L, InnertubeClient.parseDurationToSec("3.33"))
        assertEquals(3730L, InnertubeClient.parseDurationToSec("1:02:10"))
    }

    @Test
    fun testKaraokeSyllableSpacingAndNetEaseYrc() {
        val syllableTtml = """
            <tt><body><div>
              <p begin="00:01.000" end="00:05.000">
                <span begin="00:01.000" end="00:01.400">Hel</span><span begin="00:01.400" end="00:02.000">lo</span>
                <span begin="00:02.100" end="00:02.500">beau</span><span begin="00:02.500" end="00:03.200">ti</span><span begin="00:03.200" end="00:04.200">ful</span>
              </p>
            </div></body></tt>
        """.trimIndent()
        val ttmlParsed = NeveraLyricAiEngine.parseTtml(syllableTtml)
        assertEquals(1, ttmlParsed.size)
        assertEquals("Hello beautiful", ttmlParsed[0].text)
        assertEquals(5, ttmlParsed[0].words.size)
        assertEquals(0, ttmlParsed[0].words[0].charStart)
        assertEquals(3, ttmlParsed[0].words[0].charEnd)
        assertEquals(3, ttmlParsed[0].words[1].charStart)
        assertEquals(5, ttmlParsed[0].words[1].charEnd)

        val elrc = "[00:02.00]<00:02.00>be<00:02.25>au<00:02.50>ti<00:02.75>ful <00:03.10>day"
        val elrcParsed = LyricsRepository.parseLrc(elrc)
        assertEquals(1, elrcParsed.size)
        assertEquals("beautiful day", elrcParsed[0].text)

        val yrcJson = """
            {
              "yrc": {
                "lyric": "[1000,3000](1000,400,0)Good (1400,400,0)mor(1800,500,0)ning\n"
              }
            }
        """.trimIndent()
        val yrcCand = ParallelLyricSources.parseNetEaseLyricPayload(yrcJson)
        assertNotNull(yrcCand)
        assertTrue(yrcCand!!.hasNativeWordSync)
        assertEquals("Good morning", yrcCand.lines[0].text)

        val fracMid = computeLineRevealedFraction(
            posMs = 1700L,
            line = ttmlParsed[0],
            renderedText = ttmlParsed[0].text,
            isActive = true,
            isPassed = false
        )
        assertTrue("Expected smooth reveal around 'lo' (0.20..0.42), got $fracMid", fracMid in 0.20f..0.42f)
    }

    @Test
    fun testSpectrumLatencyCompensationAndZones() {
        SpectrumAudioBus.setIdle()
        val sr = 44100
        val bassTone = FloatArray(1024) { i -> (0.85 * sin(2.0 * PI * 90.0 * i / sr)).toFloat() }
        val baseNanos = 1_000_000_000L
        SpectrumAudioBus.outputLatencyMs = 150L
        SpectrumAudioBus.pushMonoSampleBlock(bassTone, bassTone.size, sr, nowNanos = baseNanos)

        val sample = SyncedAudioSample()
        // Before latency elapses (~50ms): buffered frame is not released yet
        SpectrumAudioBus.sampleAt(baseNanos + 50_000_000L, sample)
        assertEquals(0f, sample.bassEnergy, 0.001f)

        // After latency elapses (~220ms): Bass zone dominates over Vocal & Treble
        SpectrumAudioBus.sampleAt(baseNanos + 220_000_000L, sample)
        assertTrue(sample.isActive)
        assertTrue("Expected Bass energy (${sample.bassEnergy}) > Treble (${sample.trebleEnergy})", sample.bassEnergy > sample.trebleEnergy)

        SpectrumAudioBus.flushTimeline()
        assertEquals(0f, SpectrumAudioBus.latestBassEnergy, 0.001f)
        SpectrumAudioBus.setIdle()
    }

    @Test
    fun testSmartQueueRankingRejectsNoiseAndDuplicates() {
        val seed = Song(id = "seed1", title = "Blinding Lights", artist = "The Weeknd", durationSeconds = 200)
        val candidates = listOf(
            Song(id = "c1", title = "Blinding Lights (Slowed + Reverb)", artist = "Remix Channel", durationSeconds = 240),
            Song(id = "c2", title = "Save Your Tears", artist = "The Weeknd", durationSeconds = 215),
            Song(id = "c3", title = "Save Your Tears (Official Video)", artist = "The Weeknd", durationSeconds = 216),
            Song(id = "c4", title = "Midnight City", artist = "M83", durationSeconds = 243),
            Song(id = "c5", title = "Starboy", artist = "The Weeknd", durationSeconds = 230),
            Song(id = "c6", title = "Levitating (Karaoke Instrumental)", artist = "Karaoke Pro", durationSeconds = 203)
        )

        val ranked = RecommendationEngine.rankAndFilterSmartQueue(
            seedSong = seed,
            rawCandidates = candidates,
            recentlyPlayed = emptyList(),
            likedSongs = emptyList(),
            artistPlayCounts = mapOf("M83" to 3)
        )
        assertTrue(ranked.none { it.id == "c1" || it.id == "c6" })
        assertTrue(ranked.none { it.id == "c3" }) // Duplicate normalized title + artist filtered
        assertTrue(ranked.any { it.id == "c2" } && ranked.any { it.id == "c4" })
    }

    @Test
    fun testHarmonicTouchPointResolution() {
        val fx = HarmonicFxController()
        val tapPoint = Offset(342f, 815f)
        fx.recordPointerDown(tapPoint, nowNanos = 10_000_000_000L)

        // Fresh touch falls back cleanly to exact fingertip point even without target LayoutCoordinates
        val resolved = fx.resolveEffectCenter(null, nowNanos = 10_100_000_000L)
        assertEquals(tapPoint, resolved)

        // Stale touch never invents Offset.Zero or screen center
        val stale = fx.resolveEffectCenter(null, nowNanos = 11_000_000_000L)
        assertNull(stale)
    }

    @Test
    fun testVfxParticlesDrawAtExactTargetPosNotCanvasCenter() {
        val tapPos = Offset(92f, 1850f)
        val touchParticle = SpecularTouchParticle(targetPos = tapPos).apply { elapsedSec = 0.10f }
        val waveParticle = AcousticRippleParticle(targetPos = tapPos).apply { elapsedSec = 0.15f }

        val recordedCenters = mutableListOf<Offset>()
        val proxyCanvas = Proxy.newProxyInstance(
            Canvas::class.java.classLoader,
            arrayOf(Canvas::class.java)
        ) { _, method, args ->
            if ((method.name == "drawCircle" || method.name.startsWith("drawCircle-")) && args != null && args.isNotEmpty()) {
                when (val first = args[0]) {
                    is Offset -> recordedCenters.add(first)
                    is Long -> {
                        val x = java.lang.Float.intBitsToFloat((first shr 32).toInt())
                        val y = java.lang.Float.intBitsToFloat((first and 0xFFFFFFFFL).toInt())
                        recordedCenters.add(Offset(x, y))
                    }
                }
            }
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Float::class.javaPrimitiveType -> 0f
                else -> null
            }
        } as Canvas

        val canvasScope = CanvasDrawScope()
        canvasScope.draw(Density(1f), LayoutDirection.Ltr, proxyCanvas, Size(1080f, 2400f)) {
            with(touchParticle) { draw(Color.White) }
            with(waveParticle) { draw(Color.Cyan) }
        }

        assertTrue("Expected drawCircle calls to record centers", recordedCenters.size >= 4)
        assertTrue("All drawn circles must target $tapPos, got $recordedCenters", recordedCenters.all { it == tapPos })
        assertFalse("Drawn circles must never fall back to DrawScope.center (540, 1200)", recordedCenters.any { it == Offset(540f, 1200f) })
    }

    @Test
    fun testInstrumentalDotsProgressAndVocalEntryAnticipation() {
        val mid = computeInstrumentalDotsState(posMs = 3000L, startMs = 1000L, endMs = 5000L)
        assertTrue(mid.dotProgress[0] > 0.7f)
        assertTrue(mid.dotProgress[1] in 0.4f..0.9f)
        assertEquals(0f, mid.entryAnticipation, 0.001f)

        val cue = computeInstrumentalDotsState(posMs = 4600L, startMs = 1000L, endMs = 5000L)
        assertTrue("Expected vocal entry anticipation > 0.4f near end, got ${cue.entryAnticipation}", cue.entryAnticipation > 0.4f)
        assertTrue(cue.dotProgress[2] > 0.5f)
    }
}
