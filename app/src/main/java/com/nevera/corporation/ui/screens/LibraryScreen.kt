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
import com.nevera.corporation.data.model.NetworkServerConfig
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.UserPlaylist
import com.nevera.corporation.data.sources.AccountSyncManager
import com.nevera.corporation.data.sources.ServerBrowseEntry
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.data.store.LocalMediaRepository
import com.nevera.corporation.data.store.NetworkStorageRepository
import com.nevera.corporation.download.OfflineDownloadManager
import com.nevera.corporation.ui.components.NetworkStorageManagerDialog
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
    PLAYLISTS,
    SERVERS,
    RECAP
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
    val networkServers by NetworkStorageRepository.servers.collectAsState()
    val artistPlayCounts by LibraryStore.artistPlayCounts.collectAsState()
    val songPlayCounts by LibraryStore.songPlayCounts.collectAsState()
    val recentlyPlayed by LibraryStore.recentlyPlayed.collectAsState()

    var localSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selectedPlaylist by remember { mutableStateOf<UserPlaylist?>(null) }
    var selectedServer by remember { mutableStateOf<NetworkServerConfig?>(null) }
    var serverPathStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var serverEntries by remember { mutableStateOf<List<ServerBrowseEntry>>(emptyList()) }
    var isLoadingServer by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
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
            LibrarySection.PLAYLISTS to strings.playlistsTab,
            LibrarySection.SERVERS to strings.serversTab,
            LibrarySection.RECAP to strings.recapTab
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

            LibrarySection.SERVERS -> {
                val activeSrv = selectedServer?.let { sel -> networkServers.find { it.id == sel.id } }
                LaunchedEffect(activeSrv?.id, serverPathStack) {
                    if (activeSrv != null) {
                        isLoadingServer = true
                        serverEntries = NetworkStorageRepository.browse(activeSrv, serverPathStack.lastOrNull().orEmpty())
                        isLoadingServer = false
                    }
                }

                if (activeSrv != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                if (serverPathStack.isNotEmpty()) {
                                    serverPathStack = serverPathStack.dropLast(1)
                                } else {
                                    selectedServer = null
                                    serverEntries = emptyList()
                                }
                            }) {
                                Icon(Icons.Rounded.ArrowBack, strings.close, tint = Color.White)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(activeSrv.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                val sub = serverPathStack.lastOrNull().orEmpty()
                                Text(
                                    if (sub.isBlank()) activeSrv.type.label else "/$sub",
                                    color = NeveraTextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            val playableSongs = serverEntries.mapNotNull { it.song }
                            if (playableSongs.isNotEmpty()) {
                                Button(
                                    onClick = { onSongClick(playableSongs.first(), playableSongs) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text(strings.playAll, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }

                        if (isLoadingServer) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = accentColor)
                            }
                        } else if (serverEntries.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No lossless tracks found in this folder.", color = NeveraTextSecondary)
                            }
                        } else {
                            val playableSongs = remember(serverEntries) { serverEntries.mapNotNull { it.song } }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 170.dp)
                            ) {
                                items(serverEntries, key = { "srv_${it.isDirectory}_${it.path}_${it.name}" }) { entry ->
                                    if (entry.isDirectory) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { serverPathStack = serverPathStack + entry.path }
                                                .padding(horizontal = 20.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Folder, null, tint = accentColor, modifier = Modifier.size(28.dp))
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Text(entry.name, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                            Icon(Icons.Rounded.ChevronRight, null, tint = NeveraTextSecondary)
                                        }
                                    } else if (entry.song != null) {
                                        SongRow(
                                            song = entry.song,
                                            isPlaying = currentSong?.id == entry.song.id && isPlaying,
                                            isCurrent = currentSong?.id == entry.song.id,
                                            isDownloaded = downloadedSongs.any { it.id == entry.song.id },
                                            downloadProgress = downloadProgress[entry.song.id],
                                            onClick = { onSongClick(entry.song, playableSongs) },
                                            onPlayNext = { onPlayNext(entry.song) },
                                            onAddToQueue = { onAddToQueue(entry.song) },
                                            onDownloadClick = { OfflineDownloadManager.download(entry.song) },
                                            onAddToPlaylistClick = { onAddToPlaylist(entry.song) },
                                            onToggleLike = { LibraryStore.toggleLike(entry.song) },
                                            isLiked = likedSongs.any { it.id == entry.song.id }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 170.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "srv_add") {
                            ActionPillButton(
                                icon = Icons.Rounded.Dns,
                                label = "Add Subsonic / WebDAV Lossless Server",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { showServerDialog = true }
                            )
                        }
                        if (networkServers.isEmpty()) {
                            item(key = "srv_empty") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 36.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Rounded.CloudQueue, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(44.dp))
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("Stream Lossless Without Downloading", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Connect Navidrome, Jellyfin (Subsonic), or a WebDAV/NAS folder to stream FLAC & Hi-Res directly.",
                                        color = NeveraTextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        } else {
                            items(networkServers, key = { it.id }) { srv ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF14141A))
                                        .clickable {
                                            selectedServer = srv
                                            serverPathStack = emptyList()
                                        }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Dns, null, tint = accentColor, modifier = Modifier.size(26.dp))
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(srv.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text("${srv.type.label} • ${srv.baseUrl}", color = NeveraTextSecondary, fontSize = 11.sp, maxLines = 1)
                                    }
                                    Icon(Icons.Rounded.ChevronRight, null, tint = NeveraTextSecondary)
                                }
                            }
                        }
                    }
                }
            }

            LibrarySection.RECAP -> {
                val totalPlays = songPlayCounts.values.sum()
                val topArtists = remember(artistPlayCounts) {
                    artistPlayCounts.entries.sortedByDescending { it.value }.take(6)
                }
                val topSongs = remember(songPlayCounts, recentlyPlayed, likedSongs) {
                    val catalog = (recentlyPlayed + likedSongs).associateBy { it.id }
                    songPlayCounts.entries
                        .sortedByDescending { it.value }
                        .mapNotNull { (id, cnt) -> catalog[id]?.let { it to cnt } }
                        .take(8)
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 170.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item(key = "recap_summary") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RecapStatCard("Total Plays", totalPlays.toString(), accentColor, Modifier.weight(1f))
                            RecapStatCard("Top Artists", artistPlayCounts.size.toString(), accentColor, Modifier.weight(1f))
                            RecapStatCard("Liked", likedSongs.size.toString(), accentColor, Modifier.weight(1f))
                        }
                    }

                    if (topArtists.isNotEmpty()) {
                        item(key = "recap_artists_hdr") {
                            Text("Top Artists", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        val maxCount = topArtists.first().value.coerceAtLeast(1)
                        items(topArtists, key = { "ra_${it.key}" }) { (artist, count) ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF14141A))
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(artist, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("$count plays", color = NeveraTextSecondary, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth((count.toFloat() / maxCount.toFloat()).coerceIn(0.08f, 1f))
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(accentColor)
                                    )
                                }
                            }
                        }
                    }

                    if (topSongs.isNotEmpty()) {
                        item(key = "recap_songs_hdr") {
                            Text("Most Played Tracks", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        val songList = topSongs.map { it.first }
                        items(topSongs, key = { "rs_${it.first.id}" }) { (track, _) ->
                            SongRow(
                                song = track,
                                isPlaying = currentSong?.id == track.id && isPlaying,
                                isCurrent = currentSong?.id == track.id,
                                isDownloaded = downloadedSongs.any { it.id == track.id },
                                downloadProgress = downloadProgress[track.id],
                                onClick = { onSongClick(track, songList) },
                                onPlayNext = { onPlayNext(track) },
                                onAddToQueue = { onAddToQueue(track) },
                                onDownloadClick = { OfflineDownloadManager.download(track) },
                                onAddToPlaylistClick = { onAddToPlaylist(track) },
                                onToggleLike = { LibraryStore.toggleLike(track) },
                                isLiked = likedSongs.any { it.id == track.id }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showServerDialog) {
        NetworkStorageManagerDialog(
            accentColor = accentColor,
            onDismiss = { showServerDialog = false }
        )
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
private fun RecapStatCard(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF15151B))
            .border(0.6.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(vertical = 14.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = accentColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, color = NeveraTextSecondary, fontSize = 11.sp)
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
