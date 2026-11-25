package com.music.bitchord.data.playlist

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The `.nevera` playlist file — Nevera's own portable playlist format.
 *
 * The user asked for a format that is neither a bare `.json` nor a `.db`:
 * something that is recognisably *this app's* file in a file manager, and
 * that can grow (a cover today, more metadata later) without a breaking
 * change. So a `.nevera` file is a ZIP container under a custom extension:
 *
 *     My Playlist.nevera          (a ZIP archive)
 *     ├── playlist.json           (required — the manifest, see below)
 *     └── cover.jpg               (optional — the playlist's cover art)
 *
 * The manifest is one JSON object:
 *
 *     {
 *       "format":  "nevera-playlist",   // magic string; anything else is rejected
 *       "version": 1,                   // manifest schema version
 *       "name":    "Road Trip",         // playlist title
 *       "created": 1732531200000,       // epoch millis, informational
 *       "app":     "Nevera 0.1-beta",   // producing app, informational
 *       "tracks": [
 *         { "id": "dQw4w9WgXcQ",        // YouTube video id (the track's identity)
 *           "title": "…", "artist": "…",
 *           "duration": "3:45",         // display text, may be absent
 *           "thumbnail": "https://…",   // may be absent
 *           "isVideo": false }
 *       ]
 *     }
 *
 * Validation on import is deliberately strict about the container and
 * lenient about the contents: the bytes must start with the ZIP signature
 * (`PK\x03\x04`), a `playlist.json` entry must be present, its `format`
 * must be [FORMAT_ID] and its `version` must not exceed [FORMAT_VERSION].
 * Unknown manifest fields and unknown ZIP entries are ignored, so a newer
 * app can add both without breaking this reader.
 */
object NeveraPlaylistFile {

    const val EXTENSION = "nevera"
    const val MIME_TYPE = "application/octet-stream"
    const val FORMAT_ID = "nevera-playlist"
    const val FORMAT_VERSION = 1

    private const val MANIFEST_ENTRY = "playlist.json"
    private const val COVER_ENTRY = "cover.jpg"
    private const val MAX_FILE_BYTES = 32 * 1024 * 1024

    /** What a read `.nevera` file held. [coverJpeg] is null when the file had none. */
    data class Imported(
        val title: String,
        val songs: List<Song>,
        val coverJpeg: ByteArray?,
    )

    /** Serialises [playlist] to `.nevera` bytes, fetching its cover when it has one. */
    suspend fun buildBytes(playlist: LocalPlaylist): ByteArray = withContext(Dispatchers.IO) {
        val cover = playlist.songs.firstOrNull()?.thumbnailUrl?.let { fetchCover(it) }
        buildBytes(playlist.title, playlist.songs, cover)
    }

    fun buildBytes(title: String, songs: List<Song>, coverJpeg: ByteArray?): ByteArray {
        val manifest = JSONObject().apply {
            put("format", FORMAT_ID)
            put("version", FORMAT_VERSION)
            put("name", title)
            put("created", System.currentTimeMillis())
            put("app", "Nevera")
            val tracks = JSONArray()
            songs.forEach { song ->
                tracks.put(JSONObject().apply {
                    put("id", song.videoId)
                    put("title", song.title)
                    put("artist", song.artist)
                    song.durationText?.let { put("duration", it) }
                    song.thumbnailUrl?.let { put("thumbnail", it) }
                    put("isVideo", song.isVideo)
                })
            }
            put("tracks", tracks)
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (coverJpeg != null && coverJpeg.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(COVER_ENTRY))
                zip.write(coverJpeg)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Writes [playlist] as a `.nevera` file to the picker-chosen [uri]. */
    suspend fun writeTo(context: Context, uri: Uri, playlist: LocalPlaylist): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = buildBytes(playlist)
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: error("Could not open the chosen file for writing")
            }
        }

    /**
     * Reads and validates a `.nevera` file from [uri]. Any failure — not a
     * ZIP, no manifest, wrong format id, a newer schema — is a failed
     * [Result] with a plain-language message for the caller to show.
     */
    suspend fun readFrom(context: Context, uri: Uri): Result<Imported> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_FILE_BYTES) { "That file is too large to be a playlist." }
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                } ?: error("Could not read the chosen file.")

                // ZIP local-file-header signature: 'P' 'K' 0x03 0x04.
                require(
                    bytes.size >= 4 &&
                        bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                        bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte(),
                ) { "That file isn't a .nevera playlist." }

                var manifest: String? = null
                var cover: ByteArray? = null
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        when (entry.name) {
                            MANIFEST_ENTRY -> manifest = zip.readBytes().toString(Charsets.UTF_8)
                            COVER_ENTRY -> cover = zip.readBytes()
                            else -> Unit // Forward-compatible: skip unknown entries.
                        }
                        zip.closeEntry()
                    }
                }
                val manifestText = manifest ?: error("That file isn't a .nevera playlist.")
                val root = JSONObject(manifestText)
                require(root.optString("format") == FORMAT_ID) {
                    "That file isn't a .nevera playlist."
                }
                require(root.optInt("version", FORMAT_VERSION) <= FORMAT_VERSION) {
                    "That playlist was made by a newer version of Nevera."
                }
                val title = root.optString("name").ifBlank { "Imported Playlist" }
                val tracks = root.optJSONArray("tracks") ?: JSONArray()
                val songs = mutableListOf<Song>()
                for (i in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(i) ?: continue
                    val id = track.optString("id")
                    if (id.isBlank()) continue
                    songs.add(
                        Song(
                            videoId = id,
                            title = track.optString("title"),
                            artist = track.optString("artist"),
                            thumbnailUrl = track.optString("thumbnail").takeIf { it.isNotBlank() },
                            durationText = track.optString("duration").takeIf { it.isNotBlank() },
                            isVideo = track.optBoolean("isVideo", false),
                        ),
                    )
                }
                require(songs.isNotEmpty()) { "That playlist has no songs in it." }
                Imported(title = title, songs = songs, coverJpeg = cover)
            }
        }

    /** A suggested file name for the save picker: the title, made file-safe, plus the extension. */
    fun suggestedFileName(title: String): String {
        val safe = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Playlist" }
        return "$safe.$EXTENSION"
    }

    private fun fetchCover(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).build()
        Http.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }.getOrNull()
}
