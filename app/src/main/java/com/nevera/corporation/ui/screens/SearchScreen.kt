package com.nevera.corporation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nevera.corporation.data.model.CollectionType
import com.nevera.corporation.data.model.SearchFilter
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.sources.InnertubeClient
import com.nevera.corporation.data.sources.JioSaavnService
import com.nevera.corporation.data.sources.PlaylistImportEngine
import com.nevera.corporation.data.sources.SpotifyResolver
import com.nevera.corporation.data.sources.TrackMatcher
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.download.OfflineDownloadManager
import com.nevera.corporation.ui.components.SkeletonFeed
import com.nevera.corporation.ui.components.SongRow
import com.nevera.corporation.ui.i18n.LocalStrings
import com.nevera.corporation.ui.theme.NeveraBlack
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import com.nevera.corporation.ui.vfx.harmonicPress
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    popularSongs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    accentColor: Color,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(SearchFilter.ALL) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    val searchHistory by LibraryStore.searchHistory.collectAsState()
    val likedSongs by LibraryStore.likedSongs.collectAsState()
    val downloadedSongs by OfflineDownloadManager.downloadedSongs.collectAsState()
    val downloadProgress by OfflineDownloadManager.downloadProgress.collectAsState()

    suspend fun performSearch(q: String, filter: SearchFilter) {
        val trimmed = q.trim()
        if (trimmed.isBlank()) {
            results = emptyList()
            isSearching = false
            return
        }
        isSearching = true
        results = when {
            SpotifyResolver.isSpotifyUrl(trimmed) -> SpotifyResolver.resolveUrl(trimmed)
            PlaylistImportEngine.extractYouTubePlaylistId(trimmed) != null -> {
                PlaylistImportEngine.importFromUrl(trimmed)?.songs.orEmpty()
            }
            filter == SearchFilter.ARTISTS || filter == SearchFilter.ALBUMS -> {
                InnertubeClient.search(trimmed, filter)
            }
            else -> coroutineScope {
                val ytJob = async { InnertubeClient.search(trimmed, filter) }
                val jsJob = async {
                    if (filter == SearchFilter.ALL || filter == SearchFilter.SONGS) {
                        JioSaavnService.search(trimmed, limit = 12)
                    } else emptyList()
                }
                val yt = ytJob.await()
                val js = jsJob.await()

                val seenKeys = HashSet<String>()
                val merged = mutableListOf<Song>()
                val maxLen = maxOf(yt.size, js.size)
                for (i in 0 until maxLen) {
                    if (i < yt.size) {
                        val s = yt[i]
                        val key = if (s.isCollection) "col_${s.browseId}" else "${TrackMatcher.normalizeTitle(s.title)}_${TrackMatcher.normalizeArtist(s.artist)}"
                        if (seenKeys.add(key)) merged.add(s)
                    }
                    if (i < js.size) {
                        val s = js[i]
                        val key = "${TrackMatcher.normalizeTitle(s.title)}_${TrackMatcher.normalizeArtist(s.artist)}"
                        if (seenKeys.add(key)) merged.add(s)
                    }
                }
                merged
            }
        }
        isSearching = false
    }

    LaunchedEffect(query, activeFilter) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(340)
        performSearch(query, activeFilter)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NeveraBlack)
            .statusBarsPadding()
    ) {
        Text(
            text = strings.searchTitle,
            style = MaterialTheme.typography.displayLarge.copy(color = Color.White),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp)
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            placeholder = {
                Text(strings.searchPlaceholder, color = NeveraTextSecondary, fontSize = 15.sp)
            },
            leadingIcon = { Icon(Icons.Rounded.Search, strings.navSearch, tint = NeveraTextSecondary) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Clear, strings.cancel, tint = NeveraTextSecondary)
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    LibraryStore.addSearchQuery(query)
                    scope.launch { performSearch(query, activeFilter) }
                }
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF161619),
                unfocusedContainerColor = Color(0xFF141417),
                focusedBorderColor = Color.White.copy(alpha = 0.25f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = accentColor
            )
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Filter Tags: All | Song | Artist | Album
        val filterOptions = listOf(
            SearchFilter.ALL to strings.filterAll,
            SearchFilter.SONGS to strings.filterSong,
            SearchFilter.ARTISTS to strings.filterArtist,
            SearchFilter.ALBUMS to strings.filterAlbum
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filterOptions, key = { it.first.name }) { (filter, label) ->
                val selected = activeFilter == filter
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Color.White else Color(0xFF16161A))
                        .border(
                            0.7.dp,
                            if (selected) Color.White else Color.White.copy(alpha = 0.14f),
                            RoundedCornerShape(50)
                        )
                        .clickable { activeFilter = filter }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isSearching) {
            SkeletonFeed()
        } else if (query.isBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 170.dp)
            ) {
                if (searchHistory.isNotEmpty()) {
                    item(key = "history_hdr") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.recentSearches,
                                style = MaterialTheme.typography.titleMedium.copy(color = Color.White)
                            )
                            Text(
                                text = strings.cancel,
                                color = NeveraTextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { LibraryStore.clearSearchHistory() }
                            )
                        }
                    }

                    item(key = "history_chips") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(searchHistory, key = { "hist_$it" }) { past ->
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(Color(0xFF16161A))
                                        .clickable {
                                            query = past
                                            keyboard?.hide()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.History, null, tint = NeveraTextSecondary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(past, color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(22.dp))
                    }
                }

                if (popularSongs.isNotEmpty()) {
                    item(key = "pop_hdr") {
                        Text(
                            text = strings.popularNow,
                            style = MaterialTheme.typography.titleLarge.copy(color = Color.White),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }

                    item(key = "pop_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(popularSongs, key = { _, it -> "pop_${it.id}" }) { idx, song ->
                                PopularAlbumPickCard(
                                    rank = idx + 1,
                                    song = song,
                                    onClick = { onSongClick(song, listOf(song)) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        } else if (results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(strings.noSearchResults, color = NeveraTextSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 170.dp)
            ) {
                items(
                    items = results,
                    key = { "res_${it.id}_${it.browseId}" },
                    contentType = { if (it.isCollection) "collection_row" else "song_row" }
                ) { item ->
                    if (item.isCollection) {
                        CollectionSearchRow(
                            item = item,
                            onClick = {
                                LibraryStore.addSearchQuery(query)
                                onSongClick(item, results)
                            }
                        )
                    } else {
                        SongRow(
                            song = item,
                            isPlaying = currentSong?.id == item.id && isPlaying,
                            isCurrent = currentSong?.id == item.id,
                            isDownloaded = downloadedSongs.any { it.id == item.id },
                            downloadProgress = downloadProgress[item.id],
                            onClick = {
                                LibraryStore.addSearchQuery(query)
                                // Pass only clicked track so playback builds an algorithmic Smart Radio queue instead of queuing raw search query hits
                                onSongClick(item, listOf(item))
                            },
                            onPlayNext = { onPlayNext(item) },
                            onAddToQueue = { onAddToQueue(item) },
                            onDownloadClick = {
                                if (OfflineDownloadManager.isDownloaded(item.id)) {
                                    OfflineDownloadManager.deleteDownload(item.id)
                                } else {
                                    OfflineDownloadManager.download(item)
                                }
                            },
                            onAddToPlaylistClick = { onAddToPlaylist(item) },
                            onToggleLike = { LibraryStore.toggleLike(item) },
                            isLiked = likedSongs.any { it.id == item.id }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionSearchRow(
    item: Song,
    onClick: () -> Unit
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val isArtist = item.browseType == CollectionType.ARTIST
    val badge = when (item.browseType) {
        CollectionType.ARTIST -> strings.artistLabel
        CollectionType.ALBUM -> strings.albumLabel
        else -> strings.playlistLabel
    }
    val imgModel = remember(item.thumbnailUrl) {
        ImageRequest.Builder(context).data(item.thumbnailUrl).size(256).crossfade(true).build()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(10.dp))
                .background(Color(0xFF1A1A20)),
            contentAlignment = Alignment.Center
        ) {
            if (item.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = imgModel,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = if (isArtist) Icons.Rounded.Person else Icons.Rounded.Album,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (item.artist.isNotBlank() && item.artist != item.title) "$badge • ${item.artist}" else badge,
                color = NeveraTextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PopularAlbumPickCard(
    rank: Int,
    song: Song,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val imgModel = remember(song.thumbnailUrl) {
        ImageRequest.Builder(context).data(song.thumbnailUrl).size(384).crossfade(true).build()
    }

    Column(
        modifier = Modifier
            .width(146.dp)
            .harmonicPress(0.95f, interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(146.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF16161A))
        ) {
            if (song.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = imgModel,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                        )
                    )
            )
            Text(
                text = "#$rank",
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            )
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = song.title,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = song.artist,
            color = NeveraTextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
