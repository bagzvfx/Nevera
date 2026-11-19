package com.nevera.corporation.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import com.nevera.corporation.data.sources.SourceResolver
import com.nevera.corporation.data.store.LibraryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mornye-inspired Smart Queue Prefetch Cache:
 * Silently pre-buffers the next 2 tracks in queue into ExoPlayer's bounded LRU SimpleCache
 * so skipping & gapless transitions start instantaneously without permanently downloading files.
 */
@OptIn(UnstableApi::class)
object StreamPrefetchManager {

    private const val MAX_PREFETCH_BYTES_PER_TRACK = 96L * 1024L * 1024L // 96 MB cap per track
    private const val PREFETCH_AHEAD_COUNT = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    @Volatile
    private var activeWriter: CacheWriter? = null
    private val activeCancel = AtomicBoolean(false)
    private val recentlyPrefetched = ConcurrentHashMap<String, Long>()

    fun prefetchUpcoming(context: Context, queue: List<Song>, currentIndex: Int) {
        val settings = LibraryStore.settings.value
        if (!settings.smartQueuePrefetch) return
        if (currentIndex < 0 || queue.isEmpty()) return

        val upcoming = mutableListOf<Song>()
        for (offset in 1..PREFETCH_AHEAD_COUNT) {
            val next = queue.getOrNull(currentIndex + offset) ?: break
            if (next.source != SourceKind.LOCAL && !next.isCollection) {
                upcoming.add(next)
            }
        }
        if (upcoming.isEmpty()) return

        cancel()
        activeCancel.set(false)

        val appCtx = context.applicationContext
        activeJob = scope.launch {
            val cache = PlaybackService.getCache(appCtx)
            val cacheDsFactory = PlaybackService.createCacheDataSourceFactory(appCtx)

            for (song in upcoming) {
                if (activeCancel.get()) break
                val cacheKey = "nevera_cache_${song.id}"

                val lastDone = recentlyPrefetched[cacheKey]
                if (lastDone != null && (System.currentTimeMillis() - lastDone) < 20 * 60 * 1000L) {
                    continue
                }
                val alreadyCached = cache.getCachedBytes(cacheKey, 0L, 2 * 1024 * 1024L)
                if (alreadyCached >= 1024 * 1024L) {
                    recentlyPrefetched[cacheKey] = System.currentTimeMillis()
                    continue
                }

                try {
                    val resolved = SourceResolver.resolveForPlayback(song, updateSignalBus = false) ?: continue
                    if (activeCancel.get()) break

                    val specBuilder = DataSpec.Builder()
                        .setUri(Uri.parse(resolved.url))
                        .setKey(cacheKey)
                        .setLength(MAX_PREFETCH_BYTES_PER_TRACK)
                    if (resolved.headers.isNotEmpty()) {
                        specBuilder.setHttpRequestHeaders(resolved.headers)
                    }

                    val ds: CacheDataSource = cacheDsFactory.createDataSource()
                    val writer = CacheWriter(ds, specBuilder.build(), null, null)
                    activeWriter = writer
                    writer.cache()
                    activeWriter = null
                    recentlyPrefetched[cacheKey] = System.currentTimeMillis()
                } catch (_: Throwable) {
                    activeWriter = null
                }
            }
        }
    }

    fun cancel() {
        activeCancel.set(true)
        try {
            activeWriter?.cancel()
        } catch (_: Throwable) {
        }
        activeWriter = null
        activeJob?.cancel()
        activeJob = null
    }
}
