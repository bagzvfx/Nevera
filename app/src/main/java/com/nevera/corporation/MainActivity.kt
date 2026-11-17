package com.nevera.corporation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nevera.corporation.data.model.CollectionDetail
import com.nevera.corporation.data.model.HomeShelf
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.sources.InnertubeClient
import com.nevera.corporation.data.sources.RecommendationEngine
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.playback.PlayerConnection
import com.nevera.corporation.playback.QueuePlayMode
import com.nevera.corporation.ui.components.AddToPlaylistDialog
import com.nevera.corporation.ui.components.MiniPlayerPill
import com.nevera.corporation.ui.i18n.AppLocalization
import com.nevera.corporation.ui.i18n.LocalStrings
import com.nevera.corporation.ui.player.NowPlayingScreen
import com.nevera.corporation.ui.screens.CollectionDetailScreen
import com.nevera.corporation.ui.screens.ExploreScreen
import com.nevera.corporation.ui.screens.HomeScreen
import com.nevera.corporation.ui.screens.LibraryScreen
import com.nevera.corporation.ui.screens.SearchScreen
import com.nevera.corporation.ui.screens.SettingsScreen
import com.nevera.corporation.ui.theme.NeveraTheme
import com.nevera.corporation.ui.theme.rememberArtworkColors
import com.nevera.corporation.ui.vfx.GlassFloatingNavBar
import com.nevera.corporation.ui.vfx.HarmonicFxController
import com.nevera.corporation.ui.vfx.HarmonicFxOverlay
import com.nevera.corporation.ui.vfx.LocalHarmonicFx
import com.nevera.corporation.ui.vfx.NeveraNavItem
import com.nevera.corporation.ui.vfx.harmonicTouchTracker
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var playerConnection: PlayerConnection

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        playerConnection = PlayerConnection(this).apply { connect() }

        setContent {
            val hazeState = remember { HazeState() }
            val harmonicController = remember { HarmonicFxController() }
            val settings by LibraryStore.settings.collectAsState()
            val likedSongs by LibraryStore.likedSongs.collectAsState()
            val recentlyPlayed by LibraryStore.recentlyPlayed.collectAsState()
            val artistPlayCounts by LibraryStore.artistPlayCounts.collectAsState()
            val playlists by LibraryStore.playlists.collectAsState()

            val currentSong by playerConnection.currentSong.collectAsState()
            val queue by playerConnection.queue.collectAsState()
            val currentIndex by playerConnection.currentIndex.collectAsState()
            val isPlaying by playerConnection.isPlaying.collectAsState()
            val isBuffering by playerConnection.isBuffering.collectAsState()
            val positionMs by playerConnection.positionMs.collectAsState()
            val durationMs by playerConnection.durationMs.collectAsState()
            val playheadState = playerConnection.playhead.collectAsState()
            val repeatMode by playerConnection.repeatMode.collectAsState()
            val shuffleEnabled by playerConnection.shuffleEnabled.collectAsState()
            val playbackSpeed by playerConnection.playbackSpeed.collectAsState()
            val sleepTimerMs by playerConnection.sleepTimerRemainingMs.collectAsState()
            val eqPresets by playerConnection.eqPresets.collectAsState()
            val selectedEqPreset by playerConnection.selectedEqPreset.collectAsState()
            val errorBanner by playerConnection.errorBanner.collectAsState()

            val strings = remember(settings.appLanguage) {
                AppLocalization.stringsFor(settings.appLanguage)
            }
            val artworkColors = rememberArtworkColors(currentSong?.thumbnailUrl)

            LaunchedEffect(artworkColors, settings.harmonicVfxMode) {
                harmonicController.mode = settings.harmonicVfxMode
                harmonicController.accentColor = artworkColors.primary
            }

            LaunchedEffect(errorBanner) {
                if (!errorBanner.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, errorBanner, Toast.LENGTH_SHORT).show()
                    playerConnection.clearError()
                }
            }

            var selectedTabIndex by remember { mutableIntStateOf(0) }
            var homeShelves by remember { mutableStateOf<List<HomeShelf>>(emptyList()) }
            var exploreShelves by remember { mutableStateOf<List<HomeShelf>>(emptyList()) }
            var isLoadingFeeds by remember { mutableStateOf(true) }
            var isRefreshingFeeds by remember { mutableStateOf(false) }
            var refreshSeed by remember { mutableIntStateOf(0) }

            var isPlayerExpanded by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }
            var playlistTargetSong by remember { mutableStateOf<Song?>(null) }

            var showCollectionDetail by remember { mutableStateOf(false) }
            var activeCollectionDetail by remember { mutableStateOf<CollectionDetail?>(null) }
            var isLoadingCollection by remember { mutableStateOf(false) }

            fun loadFeeds(pullRefresh: Boolean = false) {
                lifecycleScope.launch {
                    if (pullRefresh) {
                        isRefreshingFeeds = true
                        refreshSeed++
                    } else {
                        isLoadingFeeds = true
                    }

                    val homeJob = async {
                        RecommendationEngine.buildPersonalizedHome(
                            recentlyPlayed = LibraryStore.recentlyPlayed.value,
                            likedSongs = LibraryStore.likedSongs.value,
                            artistPlayCounts = LibraryStore.artistPlayCounts.value,
                            strings = strings,
                            shuffleSeed = refreshSeed
                        )
                    }
                    val exploreJob = async { InnertubeClient.getExploreShelves() }

                    homeShelves = homeJob.await()
                    exploreShelves = exploreJob.await()
                    isLoadingFeeds = false
                    isRefreshingFeeds = false
                }
            }

            fun handleItemClick(
                item: Song,
                contextQueue: List<Song>,
                playMode: QueuePlayMode = QueuePlayMode.ORDERED_COLLECTION
            ) {
                if (item.isCollection) {
                    showCollectionDetail = true
                    isLoadingCollection = true
                    activeCollectionDetail = null
                    lifecycleScope.launch {
                        activeCollectionDetail = InnertubeClient.browseCollection(item)
                        isLoadingCollection = false
                    }
                } else {
                    playerConnection.playSong(item, contextQueue, playMode)
                }
            }

            val homeListState = rememberLazyListState()
            val exploreListState = rememberLazyListState()
            val motionEase = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
            val backdropDepthScale by animateFloatAsState(
                targetValue = if (isPlayerExpanded && currentSong != null) 0.94f else 1f,
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 360f),
                label = "backdrop_depth_scale"
            )
            val backdropDepthAlpha by animateFloatAsState(
                targetValue = if (isPlayerExpanded && currentSong != null) 0.65f else 1f,
                animationSpec = tween(320, easing = motionEase),
                label = "backdrop_depth_alpha"
            )

            LaunchedEffect(Unit) {
                loadFeeds(pullRefresh = false)
            }

            BackHandler(enabled = isPlayerExpanded || showSettings || showCollectionDetail) {
                when {
                    isPlayerExpanded -> isPlayerExpanded = false
                    showSettings -> showSettings = false
                    showCollectionDetail -> {
                        showCollectionDetail = false
                        activeCollectionDetail = null
                    }
                }
            }

            val navItems = remember(strings) {
                listOf(
                    NeveraNavItem("home", strings.navHome, Icons.Rounded.Home),
                    NeveraNavItem("explore", strings.navExplore, Icons.Rounded.Explore),
                    NeveraNavItem("library", strings.navLibrary, Icons.Rounded.LibraryMusic),
                    NeveraNavItem("search", strings.navSearch, Icons.Rounded.Search)
                )
            }

            CompositionLocalProvider(
                LocalStrings provides strings,
                LocalHarmonicFx provides harmonicController
            ) {
                NeveraTheme(accentColor = artworkColors.primary) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .harmonicTouchTracker(harmonicController)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = backdropDepthScale
                                    scaleY = backdropDepthScale
                                    alpha = backdropDepthAlpha
                                }
                                .hazeSource(state = hazeState)
                        ) {
                            AnimatedContent(
                                targetState = selectedTabIndex,
                                transitionSpec = {
                                    (fadeIn(tween(200, easing = motionEase)) +
                                        scaleIn(initialScale = 0.985f, animationSpec = tween(220, easing = motionEase))) togetherWith
                                        (fadeOut(tween(150)) + scaleOut(targetScale = 0.985f, animationSpec = tween(150)))
                                },
                                label = "main_tab_switch"
                            ) { tabIdx ->
                                when (tabIdx) {
                                    0 -> HomeScreen(
                                        shelves = homeShelves,
                                        recentlyPlayed = recentlyPlayed,
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                        isLoading = isLoadingFeeds,
                                        isRefreshing = isRefreshingFeeds,
                                        accentColor = artworkColors.primary,
                                        onRefresh = { loadFeeds(pullRefresh = true) },
                                        onOpenSettings = { showSettings = true },
                                        onItemClick = { item, ctxQueue ->
                                            handleItemClick(item, ctxQueue, QueuePlayMode.ORDERED_COLLECTION)
                                        },
                                        onPlayNext = { playerConnection.playNextInQueue(it) },
                                        onAddToQueue = { playerConnection.addToQueue(it) },
                                        onAddToPlaylist = { playlistTargetSong = it },
                                        listState = homeListState
                                    )

                                    1 -> ExploreScreen(
                                        exploreShelves = exploreShelves,
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                        isLoading = isLoadingFeeds,
                                        isRefreshing = isRefreshingFeeds,
                                        accentColor = artworkColors.primary,
                                        onRefresh = { loadFeeds(pullRefresh = true) },
                                        onItemClick = { item, ctxQueue ->
                                            handleItemClick(item, ctxQueue, QueuePlayMode.ORDERED_COLLECTION)
                                        },
                                        onPlayNext = { playerConnection.playNextInQueue(it) },
                                        onAddToQueue = { playerConnection.addToQueue(it) },
                                        onAddToPlaylist = { playlistTargetSong = it },
                                        listState = exploreListState
                                    )

                                    2 -> LibraryScreen(
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                        accentColor = artworkColors.primary,
                                        onOpenSettings = { showSettings = true },
                                        onSongClick = { item, ctxQueue ->
                                            handleItemClick(item, ctxQueue, QueuePlayMode.ORDERED_COLLECTION)
                                        },
                                        onPlayNext = { playerConnection.playNextInQueue(it) },
                                        onAddToQueue = { playerConnection.addToQueue(it) },
                                        onAddToPlaylist = { playlistTargetSong = it }
                                    )

                                    3 -> SearchScreen(
                                        popularSongs = (exploreShelves + homeShelves)
                                            .flatMap { it.items }
                                            .filterNot { it.isCollection }
                                            .distinctBy { it.id }
                                            .take(12),
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                        accentColor = artworkColors.primary,
                                        onSongClick = { item, ctxQueue ->
                                            handleItemClick(item, ctxQueue, QueuePlayMode.SMART_RADIO)
                                        },
                                        onPlayNext = { playerConnection.playNextInQueue(it) },
                                        onAddToQueue = { playerConnection.addToQueue(it) },
                                        onAddToPlaylist = { playlistTargetSong = it }
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = showCollectionDetail,
                                enter = slideInHorizontally(animationSpec = tween(280, easing = motionEase)) { it / 3 } +
                                    fadeIn(tween(240, easing = motionEase)),
                                exit = slideOutHorizontally(animationSpec = tween(220, easing = motionEase)) { it / 3 } +
                                    fadeOut(tween(180))
                            ) {
                                CollectionDetailScreen(
                                    detail = activeCollectionDetail,
                                    isLoading = isLoadingCollection,
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                    accentColor = artworkColors.primary,
                                    onBack = {
                                        showCollectionDetail = false
                                        activeCollectionDetail = null
                                    },
                                    onSongClick = { song, ctxQueue ->
                                        playerConnection.playSong(song, ctxQueue, QueuePlayMode.ORDERED_COLLECTION)
                                    },
                                    onPlayNext = { playerConnection.playNextInQueue(it) },
                                    onAddToQueue = { playerConnection.addToQueue(it) },
                                    onAddToPlaylist = { playlistTargetSong = it }
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.align(Alignment.BottomCenter)
                        ) {
                            AnimatedVisibility(
                                visible = currentSong != null && !isPlayerExpanded && !showSettings,
                                enter = slideInVertically(animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f)) { it } + fadeIn(tween(180)),
                                exit = slideOutVertically(animationSpec = tween(180, easing = motionEase)) { it } + fadeOut(tween(140))
                            ) {
                                currentSong?.let { song ->
                                    MiniPlayerPill(
                                        song = song,
                                        isPlaying = isPlaying,
                                        isBuffering = isBuffering,
                                        hazeState = hazeState,
                                        accentColor = artworkColors.primary,
                                        onPlayPauseClick = { playerConnection.togglePlayPause() },
                                        onNextClick = { playerConnection.skipNext() },
                                        onClick = { isPlayerExpanded = true }
                                    )
                                }
                            }

                            if (!showSettings) {
                                GlassFloatingNavBar(
                                    items = navItems,
                                    selectedIndex = selectedTabIndex,
                                    hazeState = hazeState,
                                    accentColor = artworkColors.primary,
                                    onSelect = { idx ->
                                        selectedTabIndex = idx
                                        showCollectionDetail = false
                                        activeCollectionDetail = null
                                    }
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = showSettings,
                            enter = slideInVertically(animationSpec = tween(300, easing = motionEase)) { it } + fadeIn(tween(240)),
                            exit = slideOutVertically(animationSpec = tween(240, easing = motionEase)) { it } + fadeOut(tween(180))
                        ) {
                            SettingsScreen(
                                accentColor = artworkColors.primary,
                                onBack = { showSettings = false }
                            )
                        }

                        AnimatedVisibility(
                            visible = isPlayerExpanded && currentSong != null,
                            enter = slideInVertically(
                                animationSpec = tween(350, easing = motionEase)
                            ) { it } + fadeIn(tween(260)),
                            exit = slideOutVertically(
                                animationSpec = tween(260, easing = motionEase)
                            ) { it } + fadeOut(tween(200))
                        ) {
                            currentSong?.let { song ->
                                NowPlayingScreen(
                                    song = song,
                                    queue = queue,
                                    currentIndex = currentIndex,
                                    artworkColors = artworkColors,
                                    isPlaying = isPlaying,
                                    isBuffering = isBuffering,
                                    positionMs = positionMs,
                                    durationMs = durationMs,
                                    playheadState = playheadState,
                                    repeatMode = repeatMode,
                                    shuffleEnabled = shuffleEnabled,
                                    playbackSpeed = playbackSpeed,
                                    sleepTimerRemainingMs = sleepTimerMs,
                                    eqPresets = eqPresets,
                                    selectedEqPreset = selectedEqPreset,
                                    isLiked = likedSongs.any { it.id == song.id },
                                    onToggleLike = { LibraryStore.toggleLike(song) },
                                    onPlayPauseClick = { playerConnection.togglePlayPause() },
                                    onNextClick = { playerConnection.skipNext() },
                                    onPrevClick = { playerConnection.skipPrevious() },
                                    onSeekTo = { playerConnection.seekTo(it) },
                                    onCycleRepeat = { playerConnection.cycleRepeatMode() },
                                    onToggleShuffle = { playerConnection.toggleShuffle() },
                                    onSetSpeed = { playerConnection.setPlaybackSpeed(it) },
                                    onSetSleepTimer = { playerConnection.startSleepTimer(it) },
                                    onSelectEqPreset = { playerConnection.selectEqualizerPreset(it) },
                                    onJumpQueueIndex = { playerConnection.jumpToQueueIndex(it) },
                                    onRemoveQueueIndex = { playerConnection.removeFromQueue(it) },
                                    onCollapse = { isPlayerExpanded = false }
                                )
                            }
                        }

                        playlistTargetSong?.let { song ->
                            AddToPlaylistDialog(
                                song = song,
                                playlists = playlists,
                                onCreatePlaylist = { LibraryStore.createPlaylist(it) },
                                onSelectPlaylist = { plId -> LibraryStore.addSongToPlaylist(plId, song) },
                                onDismiss = { playlistTargetSong = null }
                            )
                        }

                        HarmonicFxOverlay(controller = harmonicController)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        playerConnection.release()
        super.onDestroy()
    }
}
