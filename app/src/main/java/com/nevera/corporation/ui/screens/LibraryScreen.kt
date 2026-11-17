package com.nevera.corporation.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.UserPlaylist
import com.nevera.corporation.data.sources.AccountSyncManager
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.data.store.LocalMediaRepository
import com.nevera.corporation.download.OfflineDownloadManager
import com.nevera.corporation.ui.components.PlaylistImportDialog
import com.nevera.corporation.ui.components.SongRow
import com.nevera.corporation.ui.components.YouTubeLoginSyncDialog
import com.nevera.corporation.ui.components.syncAccountPlaylistsNow
import com.nevera.corporation.ui.components.thumbnailBorder
import com.nevera.corporation.ui.i18n.LocalStrings
import com.nevera.corporation.ui.theme.NeveraBlack
import com.nevera.corporation.ui.theme.NeveraHairline
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import kotlinx.coroutines.launch

enum class LibrarySection {
    LIKED,
    DOWNLOADS,
    LOCAL,
    PLAYLISTS
}

@Composable
fun LibraryScreen(
    currentSong: Song?,
    isPlaying: Boolean,
    accentColor: Color,
    onOpenSettings: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedSection by remember { mutableStateOf(LibrarySection.LIKED) }
    val likedSongs by LibraryStore.likedSongs.collectAsState()
    val downloadedSongs by OfflineDownloadManager.downloadedSongs.collectAsState()
    val downloadProgress by OfflineDownloadManager.downloadProgress.collectAsState()
    val playlists by LibraryStore.playlists.collectAsState()
    val isAccountSignedIn by AccountSyncManager.isSignedIn.collectAsState()

    var localSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selectedPlaylist by remember { mutableStateOf<UserPlaylist?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showLoginSyncDialog by remember { mutableStateOf(false) }

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            scope.launch { localSongs = LocalMediaRepository.scanLocalAudio(context) }
        }
    }

    LaunchedEffect(selectedSection, hasAudioPermission) {
        if (selectedSection == LibrarySection.LOCAL && hasAudioPermission && localSongs.isEmpty()) {
            localSongs = LocalMediaRepository.scanLocalAudio(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NeveraBlack)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = strings.libraryTitle,
                style = MaterialTheme.typography.displayLarge.copy(color = Color.White)
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, strings.settingsTitle, tint = Color.White.copy(alpha = 0.85f))
            }
        }

        val tabs = listOf(
            LibrarySection.LIKED to strings.likedTab,
            LibrarySection.DOWNLOADS to strings.downloadsTab,
            LibrarySection.LOCAL to strings.localTab,
            LibrarySection.PLAYLISTS to strings.playlistsTab
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tabs, key = { it.first.name }) { (tab, label) ->
                val selected = selectedSection == tab
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) Color.White else Color(0xFF17171B),
                    modifier = Modifier.clickable {
                        selectedSection = tab
                        selectedPlaylist = null
                    }
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.Black else Color.White.copy(alpha = 0.8f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedSection) {
            LibrarySection.LIKED -> {
                SongListOrEmpty(
                    songs = likedSongs,
                    emptyTitle = strings.noLikedYet,
                    emptySubtitle = "",
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    likedSongs = likedSongs,
                    downloadedSongs = downloadedSongs,
                    downloadProgress = downloadProgress,
                    onSongClick = onSongClick,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist
                )
            }

            LibrarySection.DOWNLOADS -> {
                SongListOrEmpty(
                    songs = downloadedSongs,
                    emptyTitle = strings.noDownloadsYet,
                    emptySubtitle = "",
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    likedSongs = likedSongs,
                    downloadedSongs = downloadedSongs,
                    downloadProgress = downloadProgress,
                    onSongClick = onSongClick,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist
                )
            }

            LibrarySection.LOCAL -> {
                if (!hasAudioPermission) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, null, tint = Color.White, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(strings.localTab, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { permLauncher.launch(audioPermission) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text(strings.localTab, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    SongListOrEmpty(
                        songs = localSongs,
                        emptyTitle = strings.noSearchResults,
                        emptySubtitle = "",
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        likedSongs = likedSongs,
                        downloadedSongs = downloadedSongs,
                        downloadProgress = downloadProgress,
                        onSongClick = onSongClick,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddToPlaylist
                    )
                }
            }

            LibrarySection.PLAYLISTS -> {
                val opened = playlists.find { it.id == selectedPlaylist?.id }
                if (opened != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { selectedPlaylist = null }) {
                                Icon(Icons.Rounded.ArrowBack, strings.close, tint = Color.White)
                            }
                            Text(
                                text = opened.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (opened.songs.isNotEmpty()) {
                                Button(
                                    onClick = { onSongClick(opened.songs.first(), opened.songs) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text(strings.playAll, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }

                        SongListOrEmpty(
                            songs = opened.songs,
                            emptyTitle = strings.noPlaylistsYet,
                            emptySubtitle = "",
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            likedSongs = likedSongs,
                            downloadedSongs = downloadedSongs,
                            downloadProgress = downloadProgress,
                            onSongClick = onSongClick,
                            onPlayNext = onPlayNext,
                            onAddToQueue = onAddToQueue,
                            onAddToPlaylist = onAddToPlaylist
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 170.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "pl_actions") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ActionPillButton(
                                    icon = Icons.Rounded.Add,
                                    label = strings.createPlaylistBtn,
                                    modifier = Modifier.weight(1f),
                                    onClick = { showCreateDialog = true }
                                )
                                ActionPillButton(
                                    icon = Icons.Rounded.Link,
                                    label = strings.importPlaylistBtn,
                                    modifier = Modifier.weight(1f),
                                    onClick = { showImportDialog = true }
                                )
                                ActionPillButton(
                                    icon = Icons.Rounded.Sync,
                                    label = strings.accountSyncBtn,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (isAccountSignedIn) {
                                            scope.launch { syncAccountPlaylistsNow() }
                                        } else {
                                            showLoginSyncDialog = true
                                        }
                                    }
                                )
                            }
                        }

                        if (playlists.isEmpty()) {
                            item(key = "pl_empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(strings.noPlaylistsYet, color = NeveraTextSecondary)
                                }
                            }
                        } else {
                            items(playlists, key = { it.id }) { pl ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF121216))
                                        .clickable { selectedPlaylist = pl }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PlaylistCollageThumb(songs = pl.songs)
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pl.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                        Text("${pl.songs.size} ${strings.tracksCount}", color = NeveraTextSecondary, fontSize = 12.sp)
                                    }
                                    IconButton(onClick = { LibraryStore.deletePlaylist(pl.id) }) {
                                        Icon(Icons.Rounded.DeleteOutline, strings.cancel, tint = NeveraTextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = Color(0xFF16161A),
            title = { Text(strings.createPlaylistBtn, color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        LibraryStore.createPlaylist(name)
                        showCreateDialog = false
                    }
                }) { Text(strings.save) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    if (showImportDialog) {
        PlaylistImportDialog(onDismiss = { showImportDialog = false })
    }

    if (showLoginSyncDialog) {
        YouTubeLoginSyncDialog(
            onSynced = { selectedSection = LibrarySection.PLAYLISTS },
            onDismiss = { showLoginSyncDialog = false }
        )
    }
}

@Composable
private fun ActionPillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF16161A))
            .border(0.5.dp, NeveraHairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PlaylistCollageThumb(songs: List<Song>) {
    val covers = songs.mapNotNull { it.thumbnailUrl.takeIf { url -> url.isNotBlank() } }.distinct().take(4)
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(8.dp))
            .thumbnailBorder()
            .background(Color(0xFF1E1E24)),
        contentAlignment = Alignment.Center
    ) {
        if (covers.size >= 4) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    AsyncImage(model = covers[0], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                    AsyncImage(model = covers[1], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                }
                Row(modifier = Modifier.weight(1f)) {
                    AsyncImage(model = covers[2], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                    AsyncImage(model = covers[3], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                }
            }
        } else if (covers.isNotEmpty()) {
            AsyncImage(model = covers.first(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.QueueMusic, null, tint = Color.White.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun SongListOrEmpty(
    songs: List<Song>,
    emptyTitle: String,
    emptySubtitle: String,
    currentSong: Song?,
    isPlaying: Boolean,
    likedSongs: List<Song>,
    downloadedSongs: List<Song>,
    downloadProgress: Map<String, Float>,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit
) {
    if (songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(emptyTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (emptySubtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(emptySubtitle, color = NeveraTextSecondary, fontSize = 13.sp)
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 170.dp)
        ) {
            items(songs, key = { "lib_${it.id}" }, contentType = { "song_row" }) { song ->
                SongRow(
                    song = song,
                    isPlaying = currentSong?.id == song.id && isPlaying,
                    isCurrent = currentSong?.id == song.id,
                    isDownloaded = downloadedSongs.any { it.id == song.id },
                    downloadProgress = downloadProgress[song.id],
                    onClick = { onSongClick(song, songs) },
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
