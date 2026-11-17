package com.nevera.corporation.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.nevera.corporation.data.lyrics.LyricsRepository
import com.nevera.corporation.data.lyrics.LyricsResult
import com.nevera.corporation.data.model.PlayerVisualMode
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import com.nevera.corporation.data.model.VisualizerStyle
import com.nevera.corporation.data.sources.SourceResolver
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.download.OfflineDownloadManager
import com.nevera.corporation.playback.SpectrumAudioBus
import com.nevera.corporation.playback.SyncedAudioSample
import com.nevera.corporation.ui.components.PlayingBars
import com.nevera.corporation.ui.components.thumbnailBorder
import com.nevera.corporation.ui.theme.NeveraArtworkColors
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import com.nevera.corporation.ui.vfx.HarmonicLikeButton
import com.nevera.corporation.ui.vfx.HarmonicPlayButton
import com.nevera.corporation.ui.vfx.KineticThinSlider
import com.nevera.corporation.ui.vfx.LocalHarmonicFx
import com.nevera.corporation.ui.vfx.harmonicPress
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    song: Song,
    queue: List<Song>,
    currentIndex: Int,
    artworkColors: NeveraArtworkColors,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    playheadState: State<PlaybackPlayhead>,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    playbackSpeed: Float,
    sleepTimerRemainingMs: Long?,
    eqPresets: List<String>,
    selectedEqPreset: Int,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetSleepTimer: (Int?) -> Unit,
    onSelectEqPreset: (Int) -> Unit,
    onJumpQueueIndex: (Int) -> Unit,
    onRemoveQueueIndex: (Int) -> Unit,
    onCollapse: () -> Unit
) {
    val streamInfo by SourceResolver.lastResolvedStreamInfo.collectAsState()
    val settings by LibraryStore.settings.collectAsState()
    val downloadProgressMap by OfflineDownloadManager.downloadProgress.collectAsState()
    val downloadedList by OfflineDownloadManager.downloadedSongs.collectAsState()
    val isDownloaded = downloadedList.any { it.id == song.id }
    val dlProgress = downloadProgressMap[song.id]

    var showLyrics by remember { mutableStateOf(false) }
    var lyricsResult by remember(song.id) { mutableStateOf<LyricsResult?>(null) }
    var isLoadingLyrics by remember(song.id) { mutableStateOf(false) }

    var showQueueSheet by remember { mutableStateOf(false) }
    var showTimerSheet by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showEqDialog by remember { mutableStateOf(false) }
    val dragOffsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val fx = LocalHarmonicFx.current
    var dlCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    LaunchedEffect(song.id) {
        isLoadingLyrics = true
        lyricsResult = LyricsRepository.getLyrics(
            trackId = song.id,
            title = song.title,
            artist = song.artist,
            durationSeconds = song.durationSeconds
        )
        isLoadingLyrics = false
    }

    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.88f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "art_scale"
    )

    val haloPulse = rememberInfiniteTransition(label = "vinyl_halo")
    val haloPhase by haloPulse.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "halo_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val drag = dragOffsetY.value.coerceAtLeast(0f)
                translationY = drag
                val s = (1f - (drag / 3600f)).coerceIn(0.92f, 1f)
                scaleX = s
                scaleY = s
            }
            .pointerInput(Unit) {
                val velocityTracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { velocityTracker.resetTracking() },
                    onVerticalDrag = { change, dragAmount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val nextY = (dragOffsetY.value + dragAmount).coerceAtLeast(0f)
                        scope.launch { dragOffsetY.snapTo(nextY) }
                    },
                    onDragEnd = {
                        val velY = velocityTracker.calculateVelocity().y
                        if (dragOffsetY.value > 180f || velY > 950f) {
                            onCollapse()
                            scope.launch { dragOffsetY.snapTo(0f) }
                        } else {
                            scope.launch {
                                dragOffsetY.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.85f,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            dragOffsetY.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.85f,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                )
            }
    ) {
        FluidAuraBackdrop(
            primaryColor = artworkColors.primary,
            secondaryColor = artworkColors.secondary,
            isPlaying = isPlaying
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Subtle iOS-style grab handle pill
            Box(
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 10.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.30f))
                    .clickable(onClick = onCollapse)
            )

            // Top Chrome Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val collapseInteraction = remember { MutableInteractionSource() }
                IconButton(
                    modifier = Modifier.harmonicPress(0.88f, collapseInteraction),
                    interactionSource = collapseInteraction,
                    onClick = onCollapse
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Collapse",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(30.dp)
                    )
                }

                if (settings.showAudioStats) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.10f)
                    ) {
                        Text(
                            text = streamInfo,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                val lyricsInteraction = remember { MutableInteractionSource() }
                IconButton(
                    modifier = Modifier.harmonicPress(0.88f, lyricsInteraction),
                    interactionSource = lyricsInteraction,
                    onClick = { showLyrics = !showLyrics }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lyrics,
                        contentDescription = "Lirik",
                        tint = if (showLyrics) Color.White else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(23.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Center Stage: Sleeve / Vinyl Halo / Synced Lyrics + Audio-Reactive Ambient Blur Spots
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = {
                        (fadeIn(tween(260, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))) +
                            scaleIn(initialScale = 0.95f, animationSpec = tween(260, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))) togetherWith
                            (fadeOut(tween(180)) + scaleOut(targetScale = 0.96f, animationSpec = tween(180)))
                    },
                    label = "art_lyrics_toggle"
                ) { lyricsMode ->
                    if (lyricsMode) {
                        SyncedLyricsPanel(
                            lyricsResult = lyricsResult,
                            isLoading = isLoadingLyrics,
                            playheadState = playheadState,
                            accentColor = artworkColors.primary,
                            onSeekToMs = onSeekTo
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val isVinyl = settings.playerVisualMode == PlayerVisualMode.VINYL_HALO

                            // Glowing ambient blur spots behind the album canvas reacting smoothly to Bass & Treble
                            AlbumSpotGlowAura(
                                primaryColor = artworkColors.primary,
                                secondaryColor = artworkColors.secondary,
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxSize()
                            )

                            if (isVinyl && isPlaying) {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth(0.86f)
                                        .aspectRatio(1f)
                                        .graphicsLayer {
                                            scaleX = haloPhase
                                            scaleY = haloPhase
                                        }
                                ) {
                                    drawCircle(
                                        color = artworkColors.primary.copy(alpha = 0.35f),
                                        radius = size.minDimension / 2f + 16f,
                                        style = Stroke(width = 3f)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.86f)
                                    .aspectRatio(1f)
                                    .graphicsLayer {
                                        scaleX = artScale
                                        scaleY = artScale
                                    }
                                    .clip(if (isVinyl) CircleShape else RoundedCornerShape(18.dp))
                                    .border(
                                        0.8.dp,
                                        Color.White.copy(alpha = 0.16f),
                                        if (isVinyl) CircleShape else RoundedCornerShape(18.dp)
                                    )
                                    .background(Color(0xFF18181D)),
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
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(84.dp)
                                    )
                                }

                                if (isVinyl) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.85f))
                                            .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Track Info + Download + Harmonic Like
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = song.artist,
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (song.source != SourceKind.LOCAL) {
                    val dlInteraction = remember { MutableInteractionSource() }
                    IconButton(
                        modifier = Modifier
                            .onGloballyPositioned { dlCoords = it }
                            .harmonicPress(0.88f, dlInteraction),
                        interactionSource = dlInteraction,
                        onClick = {
                            if (isDownloaded) {
                                OfflineDownloadManager.deleteDownload(song.id)
                            } else {
                                OfflineDownloadManager.download(song) { ok ->
                                    if (ok) {
                                        fx.emitBokehLove(fx.resolveEffectCenter(dlCoords))
                                    }
                                }
                            }
                        }
                    ) {
                        if (dlProgress != null) {
                            CircularProgressIndicator(
                                progress = { dlProgress },
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = if (isDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                                contentDescription = "Unduh",
                                tint = if (isDownloaded) artworkColors.primary else Color.White.copy(alpha = 0.72f)
                            )
                        }
                    }
                }

                HarmonicLikeButton(
                    isLiked = isLiked,
                    onClick = onToggleLike
                )
            }

            if (settings.visualizerStyle != VisualizerStyle.OFF && !showLyrics) {
                Spacer(modifier = Modifier.height(10.dp))
                VerticalEqualizerVisualizer(
                    style = settings.visualizerStyle,
                    isPlaying = isPlaying,
                    accentColor = artworkColors.primary,
                    secondaryColor = artworkColors.secondary,
                    onToggleStyle = {
                        val nextStyle = if (settings.visualizerStyle == VisualizerStyle.UPWARD_BARS) {
                            VisualizerStyle.SYMMETRIC_BARS
                        } else {
                            VisualizerStyle.UPWARD_BARS
                        }
                        LibraryStore.updateSettings { it.copy(visualizerStyle = nextStyle) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Thin capsule scrubber with Harmonic Kinetic glow & loading sheen
            val safeDuration = durationMs.coerceAtLeast(song.durationMs).coerceAtLeast(1L)
            val progress = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

            KineticThinSlider(
                progress = progress,
                onSeekFinished = { frac -> onSeekTo((frac * safeDuration).toLong()) },
                accentColor = artworkColors.primary,
                isLoading = isBuffering
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTimeMs(positionMs),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatTimeMs(safeDuration),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Minimalist Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val shuffleInteraction = remember { MutableInteractionSource() }
                IconButton(
                    modifier = Modifier.harmonicPress(0.88f, shuffleInteraction),
                    interactionSource = shuffleInteraction,
                    onClick = onToggleShuffle
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleEnabled) Color.White else Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                val prevInteraction = remember { MutableInteractionSource() }
                IconButton(
                    modifier = Modifier
                        .size(54.dp)
                        .harmonicPress(0.85f, prevInteraction),
                    interactionSource = prevInteraction,
                    onClick = onPrevClick
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                HarmonicPlayButton(
                    isPlaying = isPlaying,
                    onClick = onPlayPauseClick,
                    accentColor = artworkColors.primary,
                    size = 68.dp
                )

                val nextInteraction = remember { MutableInteractionSource() }
                IconButton(
                    modifier = Modifier
                        .size(54.dp)
                        .harmonicPress(0.85f, nextInteraction),
                    interactionSource = nextInteraction,
                    onClick = onNextClick
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                val repeatInteraction = remember { MutableInteractionSource() }
                IconButton(
                    modifier = Modifier.harmonicPress(0.88f, repeatInteraction),
                    interactionSource = repeatInteraction,
                    onClick = onCycleRepeat
                ) {
                    Icon(
                        imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "Ulangi",
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color.White else Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom Minimal Tool Strip: Equalizer, Speed, Sleep Timer, Queue
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showEqDialog = true }) {
                    Icon(
                        Icons.Rounded.Tune,
                        "Equalizer",
                        tint = if (selectedEqPreset > 0) artworkColors.primary else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                TextButton(onClick = { showSpeedDialog = true }) {
                    Text("${playbackSpeed}x", color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                IconButton(onClick = { showTimerSheet = true }) {
                    Icon(
                        Icons.Rounded.Bedtime,
                        "Sleep Timer",
                        tint = if (sleepTimerRemainingMs != null) artworkColors.primary else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = { showQueueSheet = true }) {
                    Icon(
                        Icons.Rounded.QueueMusic,
                        "Antrean",
                        tint = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }

    // Queue Sheet
    if (showQueueSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueSheet = false },
            containerColor = Color(0xFF141418)
        ) {
            Text(
                text = "Antrean (${queue.size} lagu)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(queue) { idx, item ->
                    val isCurr = idx == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpQueueIndex(idx) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .thumbnailBorder()
                                .background(Color(0xFF222228)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(model = item.thumbnailUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (isCurr && isPlaying) {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    PlayingBars(color = Color.White)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                color = if (isCurr) artworkColors.primary else Color.White,
                                fontWeight = if (isCurr) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(item.artist, color = NeveraTextSecondary, fontSize = 12.sp, maxLines = 1)
                        }
                        if (!isCurr) {
                            IconButton(onClick = { onRemoveQueueIndex(idx) }) {
                                Icon(Icons.Rounded.Close, "Hapus", tint = NeveraTextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    // Sleep Timer Sheet
    if (showTimerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTimerSheet = false },
            containerColor = Color(0xFF141418)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Sleep Timer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(modifier = Modifier.height(10.dp))
                listOf(10, 20, 30, 45, 60).forEach { mins ->
                    ListItem(
                        headlineContent = { Text("$mins Menit", color = Color.White) },
                        leadingContent = { Icon(Icons.Rounded.Timer, null, tint = Color.White) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onSetSleepTimer(mins)
                                showTimerSheet = false
                            }
                    )
                }
                if (sleepTimerRemainingMs != null) {
                    ListItem(
                        headlineContent = { Text("Matikan Timer", color = Color(0xFFFA2D48)) },
                        leadingContent = { Icon(Icons.Rounded.Cancel, null, tint = Color(0xFFFA2D48)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onSetSleepTimer(null)
                                showTimerSheet = false
                            }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Speed Dialog
    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = Color(0xFF16161A),
            title = { Text("Kecepatan Putar", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { sp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSetSpeed(sp); showSpeedDialog = false }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${sp}x", color = Color.White, fontWeight = FontWeight.Medium)
                            if (playbackSpeed == sp) Icon(Icons.Rounded.Check, null, tint = Color.White)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("Tutup") } }
        )
    }

    // Built-in Equalizer Preset Dialog
    if (showEqDialog) {
        AlertDialog(
            onDismissRequest = { showEqDialog = false },
            containerColor = Color(0xFF16161A),
            title = { Text("Equalizer Audio", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    eqPresets.forEachIndexed { idx, name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectEqPreset(idx); showEqDialog = false }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, color = Color.White, fontWeight = FontWeight.Medium)
                            if (selectedEqPreset == idx) Icon(Icons.Rounded.Check, null, tint = Color.White)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEqDialog = false }) { Text("Tutup") } }
        )
    }
}

@Composable
private fun AlbumSpotGlowAura(
    primaryColor: Color,
    secondaryColor: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val phase = remember { mutableFloatStateOf(0f) }
    val bassSmooth = remember { mutableFloatStateOf(0f) }
    val trebleSmooth = remember { mutableFloatStateOf(0f) }
    val audioSample = remember { SyncedAudioSample() }
    val frameTick = remember { mutableLongStateOf(0L) }

    LaunchedEffect(isPlaying) {
        var lastNanos = 0L
        while (isPlaying || bassSmooth.floatValue > 0.01f || trebleSmooth.floatValue > 0.01f) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos != 0L) {
                    ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.04f)
                } else 0.016f
                lastNanos = nanos

                if (isPlaying) {
                    phase.floatValue += dt * 0.55f
                    SpectrumAudioBus.sampleAt(System.nanoTime(), audioSample)
                }
                val targetBass = if (isPlaying && audioSample.isActive) audioSample.bassEnergy else 0f
                val targetTreble = if (isPlaying && audioSample.isActive) {
                    (audioSample.trebleEnergy * 0.72f + audioSample.vocalEnergy * 0.28f)
                } else 0f

                val att = 1f - exp(-24f * dt)
                val dec = 1f - exp(-7.5f * dt)
                bassSmooth.floatValue += (targetBass - bassSmooth.floatValue) *
                    (if (targetBass > bassSmooth.floatValue) att else dec)
                trebleSmooth.floatValue += (targetTreble - trebleSmooth.floatValue) *
                    (if (targetTreble > trebleSmooth.floatValue) att else dec)
                frameTick.longValue++
            }
        }
    }

    Canvas(modifier = modifier) {
        val unusedTick = frameTick.longValue
        val cx = size.width / 2f
        val cy = size.height / 2f
        val minDim = size.minDimension.coerceAtLeast(1f)
        val p = phase.floatValue
        val bass = bassSmooth.floatValue.coerceIn(0f, 1f)
        val treble = trebleSmooth.floatValue.coerceIn(0f, 1f)

        // Warm Bass Glow Spot behind lower-left / center of album canvas
        val bassCenter = Offset(
            x = cx - minDim * (0.14f + 0.05f * cos(p)),
            y = cy + minDim * (0.10f + 0.05f * sin(p))
        )
        val bassRadius = minDim * (0.58f + bass * 0.22f)
        val bassAlpha = (0.18f + bass * 0.34f).coerceIn(0f, 0.55f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primaryColor.copy(alpha = bassAlpha), Color.Transparent),
                center = bassCenter,
                radius = bassRadius
            ),
            center = bassCenter,
            radius = bassRadius
        )

        // Bright Treble/Vocal Spot behind upper-right rim of album canvas
        val trebleCenter = Offset(
            x = cx + minDim * (0.16f + 0.05f * sin(p * 0.9f)),
            y = cy - minDim * (0.13f + 0.05f * cos(p * 0.9f))
        )
        val trebleRadius = minDim * (0.50f + treble * 0.20f)
        val trebleAlpha = (0.12f + treble * 0.30f).coerceIn(0f, 0.48f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondaryColor.copy(alpha = trebleAlpha),
                    primaryColor.copy(alpha = trebleAlpha * 0.35f),
                    Color.Transparent
                ),
                center = trebleCenter,
                radius = trebleRadius
            ),
            center = trebleCenter,
            radius = trebleRadius
        )

        // Subtle central backlight halo brightening softly on combined punch
        val haloRadius = minDim * (0.54f + (bass * 0.6f + treble * 0.4f) * 0.14f)
        val haloAlpha = (0.06f + (bass * 0.65f + treble * 0.35f) * 0.16f).coerceIn(0f, 0.26f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = haloAlpha), Color.Transparent),
                center = Offset(cx, cy),
                radius = haloRadius
            ),
            center = Offset(cx, cy),
            radius = haloRadius
        )
    }
}

private fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%02d:%02d".format(min, sec)
}
