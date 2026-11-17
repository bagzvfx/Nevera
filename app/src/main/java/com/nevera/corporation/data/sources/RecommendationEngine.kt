package com.nevera.corporation.data.sources

import com.nevera.corporation.data.model.HomeShelf
import com.nevera.corporation.data.model.ShelfLayout
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.ui.i18n.NeveraStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Dynamic personalization engine building a user-tailored Home feed
 * distinct from Explore/Charts using listening affinities, liked songs, and time of day.
 */
object RecommendationEngine {

    /**
     * Ranks top artists using combined play affinity and liked counts.
     */
    fun computeTopArtists(
        artistPlayCounts: Map<String, Int>,
        likedSongs: List<Song>,
        recentlyPlayed: List<Song>,
        limit: Int = 5
    ): List<String> {
        val score = linkedMapOf<String, Int>()
        artistPlayCounts.forEach { (artist, count) ->
            if (artist.isNotBlank()) score[artist] = (score[artist] ?: 0) + count * 2
        }
        likedSongs.forEach { s ->
            val a = s.artist.substringBefore(",").substringBefore("&").trim()
            if (a.isNotBlank()) score[a] = (score[a] ?: 0) + 4
        }
        recentlyPlayed.take(15).forEachIndexed { idx, s ->
            val a = s.artist.substringBefore(",").substringBefore("&").trim()
            if (a.isNotBlank()) score[a] = (score[a] ?: 0) + (15 - idx)
        }
        return score.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit)
    }

    fun pickTimeOfDayQuery(hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Pair<String, String> {
        return when (hourOfDay) {
            in 5..10 -> "Morning acoustic pop feel good" to "morning"
            in 11..16 -> "Upbeat afternoon focus indie pop" to "afternoon"
            in 17..21 -> "Sunset chill R&B drive" to "evening"
            else -> "Late night lofi midnight chill" to "night"
        }
    }

    suspend fun buildPersonalizedHome(
        recentlyPlayed: List<Song>,
        likedSongs: List<Song>,
        artistPlayCounts: Map<String, Int>,
        strings: NeveraStrings,
        shuffleSeed: Int = 0
    ): List<HomeShelf> = withContext(Dispatchers.IO) {
        coroutineScope {
            val topArtists = computeTopArtists(artistPlayCounts, likedSongs, recentlyPlayed, limit = 4)
            val anchorSong = (likedSongs.take(4) + recentlyPlayed.take(6))
                .firstOrNull { !it.isCollection && it.sourceTrackId.isNotBlank() }

            val homeFeedJob = async { InnertubeClient.getHomeFeedOnly() }
            val upNextJob = async {
                anchorSong?.let { InnertubeClient.getUpNext(it.sourceTrackId) }.orEmpty()
            }
            val artistSeedJob = async {
                val chosenArtist = topArtists.getOrNull(shuffleSeed % maxOf(1, topArtists.size))
                if (!chosenArtist.isNullOrBlank()) {
                    InnertubeClient.searchSongs("$chosenArtist mix", songsOnly = true)
                } else emptyList()
            }
            val (timeQuery, _) = pickTimeOfDayQuery()
            val timeVibeJob = async {
                InnertubeClient.searchSongs(timeQuery, songsOnly = true)
            }

            val baseHome = homeFeedJob.await()
            val upNextTracks = upNextJob.await()
            val artistTracks = artistSeedJob.await()
            val timeTracks = timeVibeJob.await()

            val shelves = mutableListOf<HomeShelf>()

            // 1. Top Picks 3:4 Portrait Shelf (personalized if user has history!)
            val personalizedTopPicks = (upNextTracks.take(6) + baseHome.firstOrNull()?.items.orEmpty())
                .distinctBy { it.id }
                .take(10)
            if (personalizedTopPicks.isNotEmpty()) {
                shelves.add(
                    HomeShelf(
                        id = "home_top_picks",
                        title = strings.pickedForYou,
                        layout = ShelfLayout.TOP_PICKS_PORTRAIT,
                        items = personalizedTopPicks
                    )
                )
            }

            // 2. Artist-affinity 4-row quick mix ("Based on <Top Artist>")
            val chosenArtist = topArtists.getOrNull(shuffleSeed % maxOf(1, topArtists.size))
            if (!chosenArtist.isNullOrBlank() && artistTracks.isNotEmpty()) {
                shelves.add(
                    HomeShelf(
                        id = "home_artist_mix_${chosenArtist.hashCode()}",
                        title = "${strings.becauseYouListen} $chosenArtist",
                        layout = ShelfLayout.FOUR_ROW_PAGER,
                        items = artistTracks.take(16)
                    )
                )
            }

            // 3. Time-of-day vibe carousel
            if (timeTracks.isNotEmpty()) {
                val timeTitle = when (pickTimeOfDayQuery().second) {
                    "morning" -> strings.greetingMorning
                    "afternoon" -> strings.greetingAfternoon
                    "evening" -> strings.greetingEvening
                    else -> strings.greetingNight
                }
                shelves.add(
                    HomeShelf(
                        id = "home_time_vibe",
                        title = timeTitle,
                        layout = ShelfLayout.CAROUSEL,
                        items = timeTracks.take(12)
                    )
                )
            }

            // 4. Remaining unique Home feed shelves (skipping first if merged into Top Picks)
            baseHome.drop(1).forEach { shelf ->
                if (shelves.none { it.title.equals(shelf.title, ignoreCase = true) }) {
                    shelves.add(shelf)
                }
            }

            shelves
        }
    }

    private val noisyVariantTerms = listOf(
        "slowed", "reverb", "sped up", "speed up", "nightcore", "8d audio",
        "karaoke", "instrumental", "1 hour", "10 hours", "loop", "reaction", "tutor", "tutorial"
    )

    /**
     * Ranks and filters algorithmic Up-Next radio candidates:
     * - Deduplicates by track ID and normalized `title|primaryArtist`
     * - Rejects slowed/sped-up/karaoke/1-hour noise unless the seed track itself is that variant
     * - Weights user artist/liked affinity and avoids repeating very recently played tracks
     * - Interleaves artists cleanly so consecutive tracks feel curated and varied
     */
    fun rankAndFilterSmartQueue(
        seedSong: Song,
        rawCandidates: List<Song>,
        recentlyPlayed: List<Song>,
        likedSongs: List<Song>,
        artistPlayCounts: Map<String, Int>,
        existingQueueIds: Set<String> = emptySet(),
        limit: Int = 18
    ): List<Song> {
        val seedCleanTitle = TrackMatcher.normalizeTitle(seedSong.title).lowercase()
        val seedCleanArtist = TrackMatcher.normalizeArtist(seedSong.artist)
            .substringBefore(",").substringBefore("&").trim().lowercase()
        val seedLowerRaw = seedSong.title.lowercase()
        val seedWantsVariant = noisyVariantTerms.filter { seedLowerRaw.contains(it) }.toSet()

        val recentIds = recentlyPlayed.take(6).map { it.id }.toSet()
        val likedIds = likedSongs.map { it.id }.toSet()
        val likedArtists = likedSongs.map {
            TrackMatcher.normalizeArtist(it.artist).substringBefore(",").substringBefore("&").trim().lowercase()
        }.toSet()
        val normalizedPlayCounts = artistPlayCounts.entries.associate {
            TrackMatcher.normalizeArtist(it.key).substringBefore(",").substringBefore("&").trim().lowercase() to it.value
        }

        val seenKeys = hashSetOf<String>().apply {
            add("${seedCleanTitle}|$seedCleanArtist")
        }
        val seenIds = hashSetOf<String>().apply {
            add(seedSong.id)
            addAll(existingQueueIds)
        }

        data class ScoredCandidate(val song: Song, val primaryArtist: String, val score: Double)

        val scored = mutableListOf<ScoredCandidate>()
        rawCandidates.forEachIndexed { idx, cand ->
            if (cand.isCollection) return@forEachIndexed
            if (!seenIds.add(cand.id)) return@forEachIndexed

            val rawTitleLower = cand.title.lowercase()
            val unwantedVariant = noisyVariantTerms.any { term ->
                term !in seedWantsVariant && rawTitleLower.contains(term)
            }
            if (unwantedVariant) return@forEachIndexed

            val normTitle = TrackMatcher.normalizeTitle(cand.title).lowercase()
            val primaryArtist = TrackMatcher.normalizeArtist(cand.artist)
                .substringBefore(",").substringBefore("&").trim().lowercase()
            if (normTitle.isBlank()) return@forEachIndexed
            if (!seenKeys.add("$normTitle|$primaryArtist")) return@forEachIndexed

            // Also reject different uploads of the exact same song as seed
            if (normTitle == seedCleanTitle && (primaryArtist == seedCleanArtist || seedCleanArtist.isBlank())) {
                return@forEachIndexed
            }

            var score = (36 - idx).coerceAtLeast(1).toDouble()
            if (cand.id in likedIds) score += 10.0
            if (primaryArtist in likedArtists) score += 6.0
            score += ((normalizedPlayCounts[primaryArtist] ?: 0) * 1.5).coerceAtMost(12.0)
            if (primaryArtist == seedCleanArtist && primaryArtist.isNotBlank()) {
                // Keep 1–2 tracks from the seed artist in the mix without dominating
                score += 4.0
            }
            if (cand.id in recentIds) score -= 22.0
            if (cand.durationSeconds in 60L..480L) score += 3.0

            scored.add(ScoredCandidate(cand, primaryArtist, score))
        }

        val sorted = scored.sortedByDescending { it.score }.toMutableList()
        val ordered = mutableListOf<Song>()
        var lastArtist = seedCleanArtist
        var sameArtistStreak = 0

        while (sorted.isNotEmpty() && ordered.size < limit) {
            val chosenIdx = sorted.indexOfFirst { item ->
                item.primaryArtist.isBlank() ||
                    item.primaryArtist != lastArtist ||
                    sameArtistStreak < 1
            }.let { if (it >= 0) it else 0 }

            val picked = sorted.removeAt(chosenIdx)
            if (picked.primaryArtist.isNotBlank() && picked.primaryArtist == lastArtist) {
                sameArtistStreak++
            } else {
                sameArtistStreak = 0
                lastArtist = picked.primaryArtist
            }
            ordered.add(picked.song)
        }
        return ordered
    }

    /**
     * Builds a smart Up-Next recommendation radio queue for a clicked song (especially from Search),
     * combining official Up-Next graph with artist/taste fallback and filtering low-quality variants.
     */
    suspend fun buildSmartUpNextQueue(
        seedSong: Song,
        recentlyPlayed: List<Song>,
        likedSongs: List<Song>,
        artistPlayCounts: Map<String, Int>,
        existingQueueIds: Set<String> = emptySet(),
        limit: Int = 18
    ): List<Song> = withContext(Dispatchers.IO) {
        coroutineScope {
            val upNextJob = async {
                if (seedSong.sourceTrackId.isNotBlank() && !seedSong.id.startsWith("local_") && !seedSong.id.startsWith("saavn_")) {
                    InnertubeClient.getUpNext(seedSong.sourceTrackId)
                } else emptyList()
            }

            val cleanArtist = TrackMatcher.normalizeArtist(seedSong.artist)
                .substringBefore(",").substringBefore("&").trim()
            val fallbackJob = async {
                val up = upNextJob.await()
                if (up.size >= 8) {
                    emptyList()
                } else {
                    val query = if (cleanArtist.isNotBlank() && !cleanArtist.equals("Artist", ignoreCase = true)) {
                        "$cleanArtist similar songs mix"
                    } else {
                        "${TrackMatcher.normalizeTitle(seedSong.title)} mix"
                    }
                    InnertubeClient.searchSongs(query, songsOnly = true)
                }
            }

            val combined = upNextJob.await() + fallbackJob.await()
            rankAndFilterSmartQueue(
                seedSong = seedSong,
                rawCandidates = combined,
                recentlyPlayed = recentlyPlayed,
                likedSongs = likedSongs,
                artistPlayCounts = artistPlayCounts,
                existingQueueIds = existingQueueIds,
                limit = limit
            )
        }
    }
}
