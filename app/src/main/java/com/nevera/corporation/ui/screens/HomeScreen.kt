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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nevera.corporation.data.model.HomeShelf
import com.nevera.corporation.data.model.ShelfLayout
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.download.OfflineDownloadManager
import com.nevera.corporation.ui.components.SkeletonFeed
import com.nevera.corporation.ui.components.SongRow
import com.nevera.corporation.ui.components.thumbnailBorder
import com.nevera.corporation.ui.i18n.LocalStrings
import com.nevera.corporation.ui.theme.NeveraBlack
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import com.nevera.corporation.ui.vfx.harmonicPress
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    shelves: List<HomeShelf>,
    recentlyPlayed: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    isRefreshing: Boolean,
    accentColor: Color,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onItemClick: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val strings = LocalStrings.current
    val haptic = LocalHapticFeedback.current
    val pullState = rememberPullToRefreshState()
    val likedSongs by LibraryStore.likedSongs.collectAsState()
    val downloadedSongs by OfflineDownloadManager.downloadedSongs.collectAsState()
    val downloadProgress by OfflineDownloadManager.downloadProgress.collectAsState()

    val greeting = remember(strings) {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> strings.greetingMorning
            in 11..16 -> strings.greetingAfternoon
            in 17..21 -> strings.greetingEvening
            else -> strings.greetingNight
        }
    }

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
            item(key = "home_header", contentType = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = strings.navHome,
                            style = MaterialTheme.typography.displayLarge.copy(color = Color.White)
                        )
                        Text(
                            text = greeting,
                            color = NeveraTextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = strings.settingsTitle,
                            tint = Color.White.copy(alpha = 0.88f),
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }
            }

            if (recentlyPlayed.isNotEmpty()) {
                item(key = "listen_again_header", contentType = "shelf_header") {
                    ShelfTitleHeader(strings.listenAgain)
                }
                item(key = "listen_again_row", contentType = "carousel") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = recentlyPlayed.take(12),
                            key = { "recent_${it.id}" },
                            contentType = { "square_card" }
                        ) { song ->
                            MinimalSquareCard(
                                song = song,
                                onClick = { onItemClick(song, recentlyPlayed) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            if (isLoading && shelves.isEmpty()) {
                item(key = "home_skeleton", contentType = "skeleton") {
                    SkeletonFeed()
                }
            } else {
                shelves.forEach { shelf ->
                    item(key = "hdr_${shelf.id}", contentType = "shelf_header") {
                        ShelfTitleHeader(shelf.title)
                    }

                    when (shelf.layout) {
                        ShelfLayout.TOP_PICKS_PORTRAIT -> {
                            item(key = "top_picks_${shelf.id}", contentType = "portrait_carousel") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(
                                        items = shelf.items,
                                        key = { "tp_${shelf.id}_${it.id}" },
                                        contentType = { "portrait_card" }
                                    ) { item ->
                                        TopPickPortraitCard(
                                            song = item,
                                            onClick = { onItemClick(item, shelf.items) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(26.dp))
                            }
                        }

                        ShelfLayout.FOUR_ROW_PAGER -> {
                            item(key = "pager_${shelf.id}", contentType = "four_row_pager") {
                                val pages = remember(shelf.items) { shelf.items.chunked(4) }
                                val pagerState = rememberPagerState(pageCount = { pages.size })
                                HorizontalPager(
                                    state = pagerState,
                                    contentPadding = PaddingValues(horizontal = 0.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) { pageIdx ->
                                    val pageSongs = pages[pageIdx]
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        pageSongs.forEachIndexed { idx, song ->
                                            SongRow(
                                                song = song,
                                                isPlaying = currentSong?.id == song.id && isPlaying,
                                                isCurrent = currentSong?.id == song.id,
                                                isDownloaded = downloadedSongs.any { it.id == song.id },
                                                downloadProgress = downloadProgress[song.id],
                                                showDivider = idx < pageSongs.lastIndex,
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
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        ShelfLayout.CAROUSEL -> {
                            item(key = "car_${shelf.id}", contentType = "carousel") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(
                                        items = shelf.items,
                                        key = { "sq_${shelf.id}_${it.id}" },
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
}

@Composable
private fun ShelfTitleHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(color = Color.White),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp, top = 4.dp)
    )
}

@Composable
fun TopPickPortraitCard(
    song: Song,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val imageModel = remember(song.thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(song.thumbnailUrl)
            .size(512)
            .crossfade(true)
            .build()
    }

    Box(
        modifier = Modifier
            .width(198.dp)
            .height(264.dp)
            .harmonicPress(0.95f, interactionSource)
            .clip(RoundedCornerShape(14.dp))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .background(Color(0xFF16161A))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        AsyncImage(
            model = imageModel,
            contentDescription = song.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.88f))
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
        ) {
            Text(
                text = song.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MinimalSquareCard(
    song: Song,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val imageModel = remember(song.thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(song.thumbnailUrl)
            .size(384)
            .crossfade(true)
            .build()
    }

    Column(
        modifier = Modifier
            .width(152.dp)
            .harmonicPress(0.95f, interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(152.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                .background(Color(0xFF16161A)),
            contentAlignment = Alignment.Center
        ) {
            if (song.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.4f))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyLarge.copy(color = Color.White, fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodyMedium.copy(color = NeveraTextSecondary, fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
