package com.nevera.corporation.data.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Verifies that a resolved audio URL actually responds with playable bytes (HTTP 200/206)
 * before handing it to ExoPlayer, eliminating silent stream failures.
 */
object StreamProbe {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun isPlayable(url: String, headers: Map<String, String> = emptyMap()): Boolean = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext true
        try {
            val builder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1023")
            var hasUa = false
            headers.forEach { (k, v) ->
                if (k.equals("User-Agent", true)) hasUa = true
                builder.header(k, v)
            }
            if (!hasUa) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
                )
            }
            client.newCall(builder.build()).execute().use { resp ->
                val ok = resp.code == 200 || resp.code == 206
                if (!ok) return@withContext false
                val contentType = resp.header("Content-Type").orEmpty()
                // Reject HTML error pages disguised as 200 OK
                if (contentType.contains("text/html", ignoreCase = true)) return@withContext false
                val bytes = resp.body?.byteStream()?.use { stream ->
                    val buf = ByteArray(64)
                    stream.read(buf)
                } ?: 0
                bytes > 0
            }
        } catch (_: Exception) {
            false
        }
    }
}
