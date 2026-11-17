package com.nevera.corporation.download

import android.content.Context
import android.net.Uri
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.sources.SourceResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages offline audio downloads inside app-private storage (`filesDir/offline`).
 */
object OfflineDownloadManager {

    private lateinit var downloadDir: File
    private lateinit var manifestFile: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _downloadedSongs = MutableStateFlow<List<Song>>(emptyList())
    val downloadedSongs: StateFlow<List<Song>> = _downloadedSongs.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    fun init(context: Context) {
        downloadDir = File(context.filesDir, "offline").apply { mkdirs() }
        manifestFile = File(downloadDir, "manifest.json")
        if (manifestFile.exists()) {
            try {
                val list = json.decodeFromString<List<Song>>(manifestFile.readText())
                    .filter { fileForSong(it.id).exists() }
                _downloadedSongs.value = list
            } catch (_: Exception) {
            }
        }
    }

    private fun safeName(id: String): String = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    private fun fileForSong(songId: String): File = File(downloadDir, "${safeName(songId)}.m4a")

    fun isDownloaded(songId: String): Boolean {
        if (!::downloadDir.isInitialized) return false
        return _downloadedSongs.value.any { it.id == songId } && fileForSong(songId).exists()
    }

    fun getDownloadedFileUri(songId: String): String? {
        if (!::downloadDir.isInitialized) return null
        val file = fileForSong(songId)
        return if (file.exists() && file.length() > 1024) Uri.fromFile(file).toString() else null
    }

    fun download(song: Song, onComplete: (Boolean) -> Unit = {}) {
        if (!::downloadDir.isInitialized || song.localUri.isNotBlank()) return
        if (isDownloaded(song.id) || _downloadProgress.value.containsKey(song.id)) return

        _downloadProgress.value = _downloadProgress.value + (song.id to 0.05f)

        scope.launch {
            var success = false
            val targetFile = fileForSong(song.id)
            val tempFile = File(downloadDir, "${safeName(song.id)}.tmp")

            try {
                val stream = SourceResolver.resolveForPlayback(song)
                if (stream != null && stream.url.startsWith("http")) {
                    val reqBuilder = Request.Builder().url(stream.url)
                    stream.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                    reqBuilder.header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )

                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        if (resp.isSuccessful && resp.body != null) {
                            val totalBytes = resp.body!!.contentLength()
                            var copied = 0L
                            val buf = ByteArray(8192)
                            resp.body!!.byteStream().use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    while (true) {
                                        val read = input.read(buf)
                                        if (read == -1) break
                                        output.write(buf, 0, read)
                                        copied += read
                                        if (totalBytes > 0) {
                                            val p = (copied.toFloat() / totalBytes.toFloat()).coerceIn(0.05f, 0.99f)
                                            _downloadProgress.value = _downloadProgress.value + (song.id to p)
                                        }
                                    }
                                }
                            }
                            if (tempFile.exists() && tempFile.length() > 4096) {
                                if (targetFile.exists()) targetFile.delete()
                                tempFile.renameTo(targetFile)
                                success = true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                tempFile.delete()
            }

            _downloadProgress.value = _downloadProgress.value - song.id
            if (success) {
                val updated = listOf(song) + _downloadedSongs.value.filterNot { it.id == song.id }
                _downloadedSongs.value = updated
                persistManifest()
            }
            onComplete(success)
        }
    }

    fun deleteDownload(songId: String) {
        if (!::downloadDir.isInitialized) return
        fileForSong(songId).delete()
        _downloadedSongs.value = _downloadedSongs.value.filterNot { it.id == songId }
        persistManifest()
    }

    @Synchronized
    private fun persistManifest() {
        try {
            manifestFile.writeText(json.encodeToString(_downloadedSongs.value))
        } catch (_: Exception) {
        }
    }
}
