package com.nevera.corporation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nevera.corporation.data.model.HomeShelf
import com.nevera.corporation.data.model.MoodGenre
import com.nevera.corporation.data.model.ShelfLayout
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.sources.InnertubeClient
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.download.OfflineDownloadManager
import com.nevera.corporation.ui.components.SkeletonFeed
import com.nevera.corporation.ui.components.SongRow
import com.nevera.corporation.ui.i18n.LocalStrings
import com.nevera.corporation.ui.theme.NeveraBlack
import com.nevera.corporation.ui.vfx.harmonicPress
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    exploreShelves: List<HomeShelf>,
    currentSong: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    isRefreshing: Boolean,
    accentColor: Color,
    onRefresh: () -> Unit,
    onItemClick: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val strings = LocalStrings.current
    val haptic = LocalHapticFeedback.current
    val pullState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val moods = remember { InnertubeClient.defaultMoodGenres }
    var activeMood by remember { mutableStateOf<MoodGenre?>(null) }
    var moodTracks by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loadingMood by remember { mutableStateOf(false) }

    val likedSongs by LibraryStore.likedSongs.collectAsState()
    val downloadedSongs by OfflineDownloadManager.downloadedSongs.collectAsState()
    val downloadProgress by OfflineDownloadManager.downloadProgress.collectAsState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onRefresh()
        },
        state = pullState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = isRefreshing,
                containerColor = Color(0xFF1A1A20),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .background(NeveraBlack)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 170.dp)
        ) {
            item(key = "explore_header", contentType = "header") {
                Text(
                    text = strings.exploreTitle,
                    style = MaterialTheme.typography.displayLarge.copy(color = Color.White),
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp)
                )
            }

            item(key = "moods_title", contentType = "shelf_header") {
                Text(
                    text = strings.moodGenresTitle,
                    style = MaterialTheme.typography.titleLarge.copy(color = Color.White),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            item(key = "moods_row", contentType = "mood_chips") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = moods,
                        key = { it.title },
                        contentType = { "mood_pill" }
                    ) { mood ->
                        val selected = activeMood?.title == mood.title
                        val tone = Color(mood.accentHex)
                        val interaction = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .height(48.dp)
                                .harmonicPress(0.95f, interaction)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = if (selected) {
                                            listOf(tone.copy(alpha = 0.85f), tone.copy(alpha = 0.45f))
                                        } else {
                                            listOf(Color(0xFF1A1A1F), Color(0xFF121216))
                                        }
                                    )
                                )
                                .border(
                                    0.7.dp,
                                    if (selected) tone else Color.White.copy(alpha = 0.12f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable(
                                    interactionSource = interaction,
                                    indication = null
                                ) {
                                    if (selected) {
                                        activeMood = null
                                        moodTracks = emptyList()
                                    } else {
                                        activeMood = mood
                                        loadingMood = true
                                        scope.launch {
                                            moodTracks = InnertubeClient.searchSongs(mood.query, songsOnly = true)
                                            loadingMood = false
                                        }
                                    }
                                }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mood.title,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (activeMood != null) {
                item(key = "active_mood_hdr", contentType = "shelf_header") {
                    Text(
                        text = activeMood!!.title,
                        style = MaterialTheme.typography.titleLarge.copy(color = Color.White),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
                if (loadingMood) {
                    item(key = "mood_loading") { SkeletonFeed() }
                } else {
                    items(
                        items = moodTracks,
                        key = { "mood_${it.id}" },
                        contentType = { "song_row" }
                    ) { song ->
                        SongRow(
                            song = song,
                            isPlaying = currentSong?.id == song.id && isPlaying,
                            isCurrent = currentSong?.id == song.id,
                            isDownloaded = downloadedSongs.any { it.id == song.id },
                            downloadProgress = downloadProgress[song.id],
                            onClick = { onItemClick(song, moodTracks) },
                            onPlayNext = { onPlayNext(song) },
                            onAddToQueue = { onAddToQueue(song) },
                            onDownloadClick = {
                                if (OfflineDownloadManager.isDownloaded(song.id)) {
                                    OfflineDownloadManager.deleteDownload(song.id)
                                } else {
                                    OfflineDownloadManager.download(song)
                                }
                            },
                            onAddToPlaylistClick = { onAddToPlaylist(song) },
                            onToggleLike = { LibraryStore.toggleLike(song) },
                            isLiked = likedSongs.any { it.id == song.id }
                        )
                    }
                }
                return@LazyColumn
            }

            if (isLoading && exploreShelves.isEmpty()) {
                item(key = "explore_skeleton") { SkeletonFeed() }
            } else {
                exploreShelves.forEach { shelf ->
                    item(key = "exp_hdr_${shelf.id}", contentType = "shelf_header") {
                        Text(
                            text = shelf.title,
                            style = MaterialTheme.typography.titleLarge.copy(color = Color.White),
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp, top = 4.dp)
                        )
                    }

                    if (shelf.layout == ShelfLayout.FOUR_ROW_PAGER) {
                        items(
                            items = shelf.items.take(10),
                            key = { "exp_row_${shelf.id}_${it.id}" },
                            contentType = { "song_row" }
                        ) { song ->
                            SongRow(
                                song = song,
                                isPlaying = currentSong?.id == song.id && isPlaying,
                                isCurrent = currentSong?.id == song.id,
                                isDownloaded = downloadedSongs.any { it.id == song.id },
                                downloadProgress = downloadProgress[song.id],
                                onClick = { onItemClick(song, shelf.items) },
                                onPlayNext = { onPlayNext(song) },
                                onAddToQueue = { onAddToQueue(song) },
                                onDownloadClick = {
                                    if (OfflineDownloadManager.isDownloaded(song.id)) {
                                        OfflineDownloadManager.deleteDownload(song.id)
                                    } else {
                                        OfflineDownloadManager.download(song)
                                    }
                                },
                                onAddToPlaylistClick = { onAddToPlaylist(song) },
                                onToggleLike = { LibraryStore.toggleLike(song) },
                                isLiked = likedSongs.any { it.id == song.id }
                            )
                        }
                        item(key = "exp_sp_${shelf.id}") { Spacer(modifier = Modifier.height(18.dp)) }
                    } else {
                        item(key = "exp_car_${shelf.id}", contentType = "carousel") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(
                                    items = shelf.items,
                                    key = { "exp_sq_${shelf.id}_${it.id}" },
                                    contentType = { "square_card" }
                                ) { song ->
                                    MinimalSquareCard(
                                        song = song,
                                        onClick = { onItemClick(song, shelf.items) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}
