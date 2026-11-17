package com.nevera.corporation.data.lyrics

import com.nevera.corporation.data.model.LyricLine
import com.nevera.corporation.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class LyricsResult(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val hasRomaji: Boolean = false
)

/**
 * Parallel 11+ Timed Lyric Racing Repository powered by Nevera Lyric AI Engine.
 * Strictly prioritizes native word/syllable-synced sources, falling back smoothly to
 * line-synced LRC and plain lyrics. Never exposes internal source names.
 */
object LyricsRepository {

    private val cache = ConcurrentHashMap<String, LyricsResult>()

    suspend fun getLyrics(
        trackId: String,
        title: String,
        artist: String,
        durationSeconds: Long
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val cleanTitle = TrackMatcher.normalizeTitle(title).ifBlank { title }
        val cleanArtist = TrackMatcher.normalizeArtist(artist)
            .substringBefore(",").substringBefore("&").trim().ifBlank { artist }
        val cacheKey = "${cleanTitle}_${cleanArtist}_$durationSeconds"
        cache[cacheKey]?.let { return@withContext it }

        val winner = raceAllSources(trackId, cleanTitle, cleanArtist, durationSeconds)
            ?: return@withContext null

        val enriched = NeveraLyricAiEngine.enrichLines(winner.lines, isSynced = winner.isSynced)
        if (enriched.isEmpty()) return@withContext null

        LyricsResult(
            lines = enriched,
            isSynced = winner.isSynced,
            hasRomaji = enriched.any { it.romajiText.isNotBlank() }
        ).also { cache[cacheKey] = it }
    }

    private suspend fun raceAllSources(
        trackId: String,
        title: String,
        artist: String,
        durationSec: Long
    ): SourceCandidate? = coroutineScope {
        val simplifiedTitle = title.substringBefore(" - ").substringBefore("(").trim().ifBlank { title }
        val jobs = mutableListOf(
            async { ParallelLyricSources.fetchSimpMusic(trackId, durationSec) },
            async { ParallelLyricSources.fetchBiniLyrics(title, artist, durationSec) },
            async { ParallelLyricSources.fetchBetterLyrics(title, artist, durationSec, portato = false) },
            async { ParallelLyricSources.fetchBetterLyrics(title, artist, durationSec, portato = true) },
            async { ParallelLyricSources.fetchLyricsPlusRace(title, artist, durationSec) },
            async { ParallelLyricSources.fetchNetEase(title, artist, durationSec) },
            async { ParallelLyricSources.fetchYouTubeTimed(trackId) },
            async { ParallelLyricSources.fetchPaxsenix(title, artist, "apple-music/lyrics") },
            async { ParallelLyricSources.fetchPaxsenix(title, artist, "musixmatch/lyrics") },
            async { ParallelLyricSources.fetchSimpMusicSearch(simplifiedTitle, artist, durationSec) },
            async { ParallelLyricSources.fetchKuGou(simplifiedTitle, artist, durationSec) },
            async { ParallelLyricSources.fetchLrcLib(title, artist, durationSec) }
        )

        if (!simplifiedTitle.equals(title, ignoreCase = true)) {
            jobs.add(async { ParallelLyricSources.fetchLrcLib(simplifiedTitle, artist, durationSec) })
            jobs.add(async { ParallelLyricSources.fetchLyricsPlusRace(simplifiedTitle, artist, durationSec) })
        }

        var bestSyncedLine: SourceCandidate? = null
        var bestPlain: SourceCandidate? = null
        val graceJob = async { delay(1150L); "TIMEOUT" }

        try {
            var waitingGrace = false
            while (jobs.isNotEmpty()) {
                val res = select<Any?> {
                    jobs.forEach { d -> d.onAwait { d to it } }
                    if (waitingGrace) graceJob.onAwait { "GRACE_DONE" }
                }

                if (res == "GRACE_DONE") {
                    break
                }

                @Suppress("UNCHECKED_CAST")
                val pair = res as Pair<kotlinx.coroutines.Deferred<SourceCandidate?>, SourceCandidate?>
                jobs.remove(pair.first)
                val cand = pair.second ?: continue
                if (cand.lines.size < 2) continue

                if (cand.hasNativeWordSync && cand.isSynced) {
                    return@coroutineScope cand
                }
                if (cand.isSynced && bestSyncedLine == null) {
                    bestSyncedLine = cand
                    waitingGrace = true
                } else if (!cand.isSynced && bestPlain == null) {
                    bestPlain = cand
                }
            }
            bestSyncedLine ?: bestPlain
        } finally {
            graceJob.cancel()
            jobs.forEach { it.cancel() }
        }
    }

    internal fun parseLrc(lrcContent: String): List<LyricLine> {
        return NeveraLyricAiEngine.parseLrcOrEnhanced(lrcContent)
    }
}
