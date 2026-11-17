package com.nevera.corporation.data.sources

import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import com.nevera.corporation.data.model.SourceStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SaavnArtistItem(val id: String = "", val name: String = "")

@Serializable
data class SaavnArtistMap(
    @SerialName("primary_artists") val primaryArtists: List<SaavnArtistItem> = emptyList()
)

@Serializable
data class SaavnMoreInfo(
    val album: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    val duration: String = "",
    @SerialName("320kbps") val has320: String = "",
    val artistMap: SaavnArtistMap = SaavnArtistMap()
) {
    val supports320: Boolean get() = has320.equals("true", ignoreCase = true)
}

@Serializable
data class SaavnSongItem(
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val image: String = "",
    @SerialName("explicit_content") val explicitContent: String = "",
    @SerialName("more_info") val moreInfo: SaavnMoreInfo = SaavnMoreInfo()
) {
    val isExplicit: Boolean get() = explicitContent == "1" || explicitContent.equals("true", ignoreCase = true)
}

@Serializable
data class SaavnSearchResponse(
    val results: List<SaavnSongItem> = emptyList()
)

data class SaavnStreamInfo(val url: String, val kbps: Int)

/**
 * High-bitrate 320kbps AAC/MP4 resolver handling both `{"songs":[...]}` and `{"<id>":{...}}` response envelopes.
 */
object JioSaavnService {

    private const val DES_KEY = "38346591"
    private const val BASE_URL = "https://www.jiosaavn.com/api.php"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    internal fun decryptUrl(encryptedUrl: String): String {
        if (encryptedUrl.isBlank()) return ""
        return try {
            val secretKey = SecretKeySpec(DES_KEY.toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decodedBytes = Base64.getDecoder().decode(encryptedUrl.trim())
            String(cipher.doFinal(decodedBytes), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    internal fun selectBestStream(decryptedUrl: String, supports320: Boolean, maxKbps: Int = 320): SaavnStreamInfo? {
        if (decryptedUrl.isBlank()) return null
        val rendition = Regex(
            "_(48|96|160|320)\\.(mp4|aac|mp3)(?=[?#]|$)",
            RegexOption.IGNORE_CASE
        ).find(decryptedUrl) ?: return SaavnStreamInfo(decryptedUrl, 160)

        val offered = rendition.groupValues[1].toIntOrNull() ?: 96
        val ext = rendition.groupValues[2]

        val targetKbps = when {
            maxKbps <= 96 -> 96
            maxKbps <= 160 -> 160
            supports320 -> 320
            else -> maxOf(offered, 160)
        }

        val finalUrl = decryptedUrl.replaceRange(rendition.range, "_${targetKbps}.$ext")
        return SaavnStreamInfo(finalUrl, targetKbps)
    }

    internal fun parseDetailsPayload(rawJson: String): SaavnSongItem? {
        return try {
            val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return null
            val songEl = (root["songs"] as? JsonArray)?.firstOrNull()
                ?: root.values.firstOrNull { it is JsonObject }
                ?: return null
            json.decodeFromJsonElement(SaavnSongItem.serializer(), songEl)
        } catch (_: Exception) {
            null
        }
    }

    private fun upgradeCoverArt(url: String): String {
        return url.replace("150x150", "500x500").replace("50x50", "500x500")
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun SaavnSongItem.toSong(): Song? {
        val streamInfo = selectBestStream(decryptUrl(moreInfo.encryptedMediaUrl), moreInfo.supports320)
            ?: return null
        val artists = moreInfo.artistMap.primaryArtists.joinToString(", ") { decodeHtmlEntities(it.name) }
            .ifBlank { decodeHtmlEntities(subtitle).substringBefore(" - ").ifBlank { "Artis" } }

        return Song(
            id = "saavn_$id",
            title = decodeHtmlEntities(title),
            artist = artists,
            album = decodeHtmlEntities(moreInfo.album),
            durationSeconds = moreInfo.duration.toLongOrNull() ?: 0L,
            thumbnailUrl = upgradeCoverArt(image),
            source = SourceKind.JIOSAAVN,
            sourceTrackId = id,
            directStreamUrl = streamInfo.url
        )
    }

    suspend fun search(query: String, limit: Int = 15): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL?__call=search.getResults&_format=json&_marker=0&cc=in&includeMetaTags=1&q=$encoded&n=$limit&p=1&api_version=4&_ctx=android"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-Forwarded-For", "49.36.0.1")
                .header("Cookie", "explicit_content=1")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string().orEmpty()
                val parsed = json.decodeFromString<SaavnSearchResponse>(body)
                return@withContext parsed.results
                    .sortedByDescending { it.isExplicit }
                    .mapNotNull { it.toSong() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getStreamById(saavnId: String, maxKbps: Int = 320): SourceStream? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL?__call=song.getDetails&_format=json&_marker=0&pids=$saavnId&api_version=4&_ctx=android"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-Forwarded-For", "49.36.0.1")
                .header("Cookie", "explicit_content=1")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val raw = resp.body?.string().orEmpty()
                val item = parseDetailsPayload(raw) ?: return@withContext null
                val decrypted = decryptUrl(item.moreInfo.encryptedMediaUrl)
                val best = selectBestStream(decrypted, item.moreInfo.supports320, maxKbps) ?: return@withContext null
                SourceStream(
                    url = best.url,
                    codec = "AAC",
                    kbps = best.kbps,
                    qualityLabel = "HQ Audio • ${best.kbps} kbps"
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
