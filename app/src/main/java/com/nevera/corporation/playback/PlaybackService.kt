package com.nevera.corporation.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.nevera.corporation.MainActivity
import com.nevera.corporation.R
import com.nevera.corporation.data.sources.SourceResolver
import com.nevera.corporation.data.store.LibraryStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var bitmapExecutor: ListeningExecutorService? = null

    companion object {
        const val PLAYBACK_CHANNEL_ID = "nevera_playback_channel_v2"
        const val PLAYBACK_NOTIFICATION_ID = 1001

        @Volatile
        private var sharedCache: SimpleCache? = null

        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        fun getCache(context: Context): SimpleCache {
            return sharedCache ?: synchronized(this) {
                sharedCache ?: run {
                    val limitBytes = LibraryStore.settings.value.cacheLimitMb.toLong().coerceIn(64L, 2048L) * 1024L * 1024L
                    val cacheDir = File(context.cacheDir, "nevera_audio_cache").apply { mkdirs() }
                    val evictor = LeastRecentlyUsedCacheEvictor(limitBytes)
                    SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(context)).also {
                        sharedCache = it
                    }
                }
            }
        }

        fun createCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
            val httpFactory = OkHttpDataSource.Factory(sharedHttpClient)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
            val upstreamFactory: DataSource.Factory = DefaultDataSource.Factory(context.applicationContext, httpFactory)
            return CacheDataSource.Factory()
                .setCache(getCache(context.applicationContext))
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        fun getCacheSizeMb(context: Context): Long {
            val cacheDir = File(context.cacheDir, "nevera_audio_cache")
            if (!cacheDir.exists()) return 0L
            val bytes = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            return bytes / (1024L * 1024L)
        }

        fun clearAudioCache(context: Context): Boolean {
            return try {
                sharedCache?.release()
                sharedCache = null
                File(context.cacheDir, "nevera_audio_cache").deleteRecursively()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        ensureMediaNotificationChannel()
        AudiophileDspAudioProcessor.updateLiveProfile(LibraryStore.settings.value.dspProfile)
        AudioHardwareRouter.start(this)
        MusicHapticsEngine.init(this)

        val notifProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(PLAYBACK_CHANNEL_ID)
            .setChannelName(R.string.app_name)
            .setNotificationId(PLAYBACK_NOTIFICATION_ID)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_nevera_notif)
            }
        setMediaNotificationProvider(notifProvider)

        val okHttpClient = sharedHttpClient
        val cacheDataSourceFactory = createCacheDataSourceFactory(this)

        val resolvingFactory = ResolvingDataSource.Factory(
            cacheDataSourceFactory,
            object : ResolvingDataSource.Resolver {
                override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                    val uri = dataSpec.uri
                    if (uri.scheme != "nevera") {
                        return dataSpec
                    }

                    val songId = uri.getQueryParameter("vid").orEmpty()
                    val song = SourceResolver.getRegistered(songId)
                        ?: throw IOException("Metadata lagu tidak ditemukan untuk $songId")

                    val resolved = runBlocking { SourceResolver.resolveForPlayback(song) }
                        ?: throw IOException("Stream audio tidak tersedia untuk ${song.title}")

                    val parsedUri = Uri.parse(resolved.url)
                    val newBuilder = dataSpec.buildUpon()
                        .setUri(parsedUri)
                        .setKey("nevera_cache_${song.id}")

                    if (resolved.headers.isNotEmpty()) {
                        newBuilder.setHttpRequestHeaders(resolved.headers)
                    }
                    return newBuilder.build()
                }
            }
        )

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(true)
                    .setAudioProcessors(
                        arrayOf(
                            AudiophileDspAudioProcessor(),
                            SpectrumTapAudioProcessor()
                        )
                    )
                    .build()
            }
        }

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                skipSilenceEnabled = LibraryStore.settings.value.skipSilence
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?
                    ) {
                        val bitDepth = when (format.pcmEncoding) {
                            C.ENCODING_PCM_8BIT -> 8
                            C.ENCODING_PCM_16BIT -> 16
                            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
                            else -> 0
                        }
                        AudioSignalPathBus.onDecoderFormatChanged(
                            mimeType = format.sampleMimeType,
                            sampleRateHz = format.sampleRate,
                            channelCount = format.channelCount,
                            bitrateBps = format.bitrate,
                            bitDepth = bitDepth
                        )
                        if (format.sampleRate > 0) {
                            AudioHardwareRouter.onTrackSampleRateChanged(format.sampleRate)
                        }
                    }
                })
            }

        player = exoPlayer

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exec = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))
        bitmapExecutor = exec
        val artworkLoader = MediaArtworkBitmapLoader(this, okHttpClient, exec)

        val sessionCallback = object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val playerCommands = Player.Commands.Builder()
                    .addAllCommands()
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
            }
        }

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            exoPlayer,
            sessionCallback
        )
            .setSessionActivity(sessionActivity)
            .setBitmapLoader(artworkLoader)
            .build()
    }

    private fun ensureMediaNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.deleteNotificationChannel("nevera_playback_channel")
            val existing = nm.getNotificationChannel(PLAYBACK_CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    PLAYBACK_CHANNEL_ID,
                    "Now Playing",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Active media playback controls & live capsule metadata"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val exo = player
        if (exo == null || !exo.playWhenReady || exo.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        StreamPrefetchManager.cancel()
        AudioHardwareRouter.stop()
        MusicHapticsEngine.setPlaying(false)
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        player = null
        bitmapExecutor?.shutdownNow()
        bitmapExecutor = null
        super.onDestroy()
    }
}

/**
 * High-speed LRU-cached BitmapLoader ensuring square ARGB_8888 artwork is always delivered
 * to the Android platform MediaSession & OEM Dynamic Island / Now Bar pills.
 */
@OptIn(UnstableApi::class)
private class MediaArtworkBitmapLoader(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val executor: ListeningExecutorService
) : BitmapLoader {

    private val cache = LruCache<String, Bitmap>(24)

    override fun supportsMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/", ignoreCase = true)
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return executor.submit<Bitmap> {
            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw IOException("Failed to decode artwork bytes")
            normalizeSquareArtwork(decoded)
        }
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val key = uri.toString()
        cache.get(key)?.let { return Futures.immediateFuture(it) }

        return executor.submit<Bitmap> {
            val rawBitmap = when (uri.scheme?.lowercase()) {
                "content", "file", "android.resource" -> {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                else -> {
                    val req = Request.Builder()
                        .url(key)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Nevera/6.1")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val bytes = resp.body?.bytes() ?: return@use null
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            } ?: fallbackAppIconBitmap()

            normalizeSquareArtwork(rawBitmap).also { square ->
                cache.put(key, square)
            }
        }
    }

    private fun normalizeSquareArtwork(src: Bitmap): Bitmap {
        val targetSize = 512
        val side = minOf(src.width, src.height).coerceAtLeast(1)
        val xOff = (src.width - side) / 2
        val yOff = (src.height - side) / 2
        val cropped = if (src.width != src.height) {
            Bitmap.createBitmap(src, xOff, yOff, side, side)
        } else {
            src
        }
        return if (cropped.width != targetSize || cropped.height != targetSize) {
            Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        } else {
            cropped
        }
    }

    private fun fallbackAppIconBitmap(): Bitmap {
        val d = context.packageManager.getApplicationIcon(context.applicationInfo)
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        return bmp
    }
}
