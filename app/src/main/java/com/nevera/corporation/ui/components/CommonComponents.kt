package com.nevera.corporation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import com.nevera.corporation.data.model.UserPlaylist
import com.nevera.corporation.ui.theme.NeveraHairline
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import com.nevera.corporation.ui.vfx.LocalHarmonicFx
import com.nevera.corporation.ui.vfx.harmonicPress
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

fun Modifier.thumbnailBorder(): Modifier =
    this.border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))

@Composable
fun PlayingBars(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eq_bars")
    val b1 by transition.animateFloat(0.28f, 1f, infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse), label = "b1")
    val b2 by transition.animateFloat(0.95f, 0.25f, infiniteRepeatable(tween(340, easing = LinearEasing), RepeatMode.Reverse), label = "b2")
    val b3 by transition.animateFloat(0.4f, 0.9f, infiniteRepeatable(tween(490, easing = LinearEasing), RepeatMode.Reverse), label = "b3")

    Row(
        modifier = modifier.size(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(b1, b2, b3).forEach { h ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(h)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SongRow(
    song: Song,
    isPlaying: Boolean,
    isCurrent: Boolean,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    showDivider: Boolean = true,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownloadClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onToggleLike: () -> Unit,
    isLiked: Boolean
) {
    var showActionSheet by remember { mutableStateOf(false) }
    val fx = LocalHarmonicFx.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showActionSheet = true }
                )
                .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .thumbnailBorder()
                    .background(Color(0xFF161619)),
                contentAlignment = Alignment.Center
            ) {
                if (song.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.5f))
                }

                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isPlaying) {
                            PlayingBars(color = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDownloaded) {
                        Icon(
                            imageVector = Icons.Rounded.DownloadDone,
                            contentDescription = "Offline",
                            tint = NeveraTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                    Text(
                        text = buildString {
                            append(song.artist)
                            if (song.durationSeconds > 0) {
                                append(" • ")
                                append(formatDurationSec(song.durationSeconds))
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(color = NeveraTextSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (downloadProgress != null) {
                CircularProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 6.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            }

            IconButton(
                onClick = { showActionSheet = true },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = "Opsi",
                    tint = NeveraTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 84.dp, end = 20.dp),
                thickness = 0.5.dp,
                color = NeveraHairline.copy(alpha = 0.7f)
            )
        }
    }

    if (showActionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showActionSheet = false },
            containerColor = Color(0xFF161619)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .thumbnailBorder()
                            .background(Color(0xFF222228))
                    ) {
                        AsyncImage(
                            model = song.thumbnailUrl,
                            contentDescription = song.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, color = NeveraTextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                HorizontalDivider(color = NeveraHairline, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))

                SheetActionRow(
                    icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    tint = if (isLiked) Color(0xFFFA2D48) else Color.White,
                    label = if (isLiked) "Hapus dari Disukai" else "Sukai Lagu",
                    onClick = {
                        showActionSheet = false
                        onToggleLike()
                    }
                )
                SheetActionRow(
                    icon = Icons.Rounded.PlaylistPlay,
                    label = "Putar Berikutnya",
                    onClick = { showActionSheet = false; onPlayNext() }
                )
                SheetActionRow(
                    icon = Icons.Rounded.QueueMusic,
                    label = "Tambah ke Antrean",
                    onClick = { showActionSheet = false; onAddToQueue() }
                )
                SheetActionRow(
                    icon = Icons.Rounded.PlaylistAdd,
                    label = "Tambah ke Playlist",
                    onClick = { showActionSheet = false; onAddToPlaylistClick() }
                )
                if (song.source != SourceKind.LOCAL) {
                    SheetActionRow(
                        icon = if (isDownloaded) Icons.Rounded.DeleteOutline else Icons.Rounded.Download,
                        label = if (isDownloaded) "Hapus Unduhan Offline" else "Unduh untuk Offline",
                        onClick = {
                            showActionSheet = false
                            onDownloadClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Floating Frosted Glass MiniPlayer Pill (`56.dp` height, `RoundedCornerShape(50)`).
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun MiniPlayerPill(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    hazeState: HazeState,
    accentColor: Color,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fx = LocalHarmonicFx.current
    var playBtnCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val pillInteraction = remember { MutableInteractionSource() }

    val glassStyle = remember {
        HazeStyle(
            backgroundColor = Color(0xFF141418),
            tints = listOf(HazeTint(Color(0xFF16161B).copy(alpha = 0.74f))),
            blurRadius = 22.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(Color(0xFF19191F).copy(alpha = 0.94f))
        )
    }

    val rimBrush = remember(accentColor) {
        Brush.linearGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.42f),
                Color.White.copy(alpha = 0.14f),
                accentColor.copy(alpha = 0.25f)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(56.dp)
            .shadow(14.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(28.dp))
            .hazeEffect(state = hazeState, style = glassStyle) {
                inputScale = HazeInputScale.Fixed(0.33f)
            }
            .border(0.8.dp, rimBrush, RoundedCornerShape(28.dp))
            .harmonicPress(pressedScale = 0.97f, interactionSource = pillInteraction)
            .clickable(
                interactionSource = pillInteraction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .thumbnailBorder()
                    .background(Color(0xFF202026)),
                contentAlignment = Alignment.Center
            ) {
                if (song.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.6f))
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .onGloballyPositioned { coords -> playBtnCoords = coords }
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        fx.emitAcousticWave(fx.resolveEffectCenter(playBtnCoords))
                        onPlayPauseClick()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onNextClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Berikutnya",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
fun SkeletonFeed() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "shimmer_x"
    )
    val brush = Brush.linearGradient(
        colors = listOf(Color(0xFF131316), Color(0xFF212126), Color(0xFF131316)),
        start = Offset(shift - 300f, 0f),
        end = Offset(shift, 200f)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(brush)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(3) {
                Box(
                    modifier = Modifier
                        .width(196.dp)
                        .height(260.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(brush)
                )
            }
        }
        repeat(4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(brush)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.width(190.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(brush))
                    Box(modifier = Modifier.width(120.dp).height(11.dp).clip(RoundedCornerShape(4.dp)).background(brush))
                }
            }
        }
    }
}

@Composable
fun AddToPlaylistDialog(
    song: Song,
    playlists: List<UserPlaylist>,
    onCreatePlaylist: (String) -> Unit,
    onSelectPlaylist: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161A),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("Simpan ke Playlist", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("Nama playlist baru...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                onCreatePlaylist(newName)
                                newName = ""
                            }
                        }
                    ) { Text("Buat") }
                }
                if (playlists.isEmpty()) {
                    Text("Belum ada playlist.", color = NeveraTextSecondary, fontSize = 13.sp)
                } else {
                    playlists.forEach { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onSelectPlaylist(pl.id)
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.QueueMusic, null, tint = Color.White)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(pl.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text("${pl.songs.size} lagu", fontSize = 12.sp, color = NeveraTextSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

fun formatDurationSec(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val min = s / 60
    val rem = s % 60
    return "%d:%02d".format(min, rem)
}
