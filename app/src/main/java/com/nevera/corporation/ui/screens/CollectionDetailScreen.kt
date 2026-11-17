package com.nevera.corporation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nevera.corporation.data.model.CollectionDetail
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.download.OfflineDownloadManager
import com.nevera.corporation.ui.components.SkeletonFeed
import com.nevera.corporation.ui.components.SongRow
import com.nevera.corporation.ui.components.thumbnailBorder
import com.nevera.corporation.ui.theme.NeveraBlack
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import com.nevera.corporation.ui.i18n.LocalStrings
import com.nevera.corporation.ui.vfx.harmonicPress

@Composable
fun CollectionDetailScreen(
    detail: CollectionDetail?,
    isLoading: Boolean,
    currentSong: Song?,
    isPlaying: Boolean,
    accentColor: Color,
    onBack: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit
) {
    val strings = LocalStrings.current
    val likedSongs by LibraryStore.likedSongs.collectAsState()
    val downloadedSongs by OfflineDownloadManager.downloadedSongs.collectAsState()
    val downloadProgress by OfflineDownloadManager.downloadProgress.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeveraBlack)
    ) {
        if (isLoading || detail == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.Rounded.ArrowBack, "Kembali", tint = Color.White)
                }
                SkeletonFeed()
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 160.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(accentColor.copy(alpha = 0.25f), NeveraBlack)
                            )
                        )
                        .statusBarsPadding()
                        .padding(bottom = 20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Rounded.ArrowBack, "Kembali", tint = Color.White)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Box(
                            modifier = Modifier
                                .size(210.dp)
                                .shadow(24.dp, RoundedCornerShape(14.dp))
                                .clip(RoundedCornerShape(14.dp))
                                .thumbnailBorder()
                                .background(Color(0xFF1A1A1F))
                        ) {
                            AsyncImage(
                                model = detail.thumbnailUrl,
                                contentDescription = detail.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = detail.title,
                            style = MaterialTheme.typography.headlineMedium.copy(color = Color.White),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "${detail.subtitle} • ${detail.tracks.size} ${strings.tracksCount}",
                            style = MaterialTheme.typography.bodyMedium.copy(color = NeveraTextSecondary),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        if (detail.tracks.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { onSongClick(detail.tracks.first(), detail.tracks) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.playAll, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val shuffled = detail.tracks.shuffled()
                                        onSongClick(shuffled.first(), shuffled)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF1E1E24),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(Icons.Rounded.Shuffle, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.shufflePlay, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            if (detail.tracks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Tidak ada lagu ditemukan di koleksi ini", color = NeveraTextSecondary)
                    }
                }
            } else {
                items(detail.tracks, key = { "col_tr_${it.id}" }) { song ->
                    SongRow(
                        song = song,
                        isPlaying = currentSong?.id == song.id && isPlaying,
                        isCurrent = currentSong?.id == song.id,
                        isDownloaded = downloadedSongs.any { it.id == song.id },
                        downloadProgress = downloadProgress[song.id],
                        onClick = { onSongClick(song, detail.tracks) },
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
    }
}
