package com.nevera.corporation.data.store

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans audio files stored locally on the Android device via MediaStore,
 * capturing Hi-Res / Lossless format tags (FLAC, WAV, ALAC, AIFF, Opus, AAC, MP3) and bitrate.
 */
object LocalMediaRepository {

    private val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

    suspend fun scanLocalAudio(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val projectionList = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projectionList.add(MediaStore.Audio.Media.BITRATE)
        }
        val projection = projectionList.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val displayCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val bitrateCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                } else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol).orEmpty()
                    val rawArtist = cursor.getString(artistCol).orEmpty()
                    val artist = if (rawArtist == "<unknown>" || rawArtist.isBlank()) "Local Artist" else rawArtist
                    val album = cursor.getString(albumCol).orEmpty()
                    val albumId = cursor.getLong(albumIdCol)
                    val durationMs = cursor.getLong(durationCol)
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                    val displayName = if (displayCol >= 0) cursor.getString(displayCol).orEmpty() else ""
                    val bitrateBps = if (bitrateCol >= 0) cursor.getInt(bitrateCol) else 0

                    val codec = detectLocalCodec(mime, displayName)
                    val isLossless = codec in setOf("FLAC", "WAV", "ALAC", "AIFF")

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val artUri = ContentUris.withAppendedId(albumArtBaseUri, albumId)

                    songs.add(
                        Song(
                            id = "local_$id",
                            title = title.ifBlank { displayName.substringBeforeLast('.').ifBlank { "Untitled Audio" } },
                            artist = artist,
                            album = album,
                            durationSeconds = durationMs / 1000L,
                            thumbnailUrl = artUri.toString(),
                            source = SourceKind.LOCAL,
                            sourceTrackId = id.toString(),
                            localUri = contentUri.toString(),
                            codecHint = codec,
                            bitrateKbps = if (bitrateBps > 0) bitrateBps / 1000 else if (isLossless) 1000 else 320,
                            bitDepth = if (isLossless) 16 else 0
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission not granted yet
        } catch (_: Exception) {
        }

        songs
    }

    private fun detectLocalCodec(mime: String, displayName: String): String {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val m = mime.lowercase()
        return when {
            m.contains("flac") || ext == "flac" -> "FLAC"
            m.contains("wav") || m.contains("x-wav") || ext == "wav" -> "WAV"
            m.contains("alac") || ext == "alac" -> "ALAC"
            m.contains("aiff") || ext == "aiff" || ext == "aif" -> "AIFF"
            m.contains("opus") || ext == "opus" -> "Opus"
            m.contains("ogg") || ext == "ogg" -> "Vorbis"
            m.contains("mp4") || m.contains("aac") || ext == "m4a" || ext == "aac" -> "AAC"
            m.contains("mpeg") || ext == "mp3" -> "MP3"
            else -> ext.uppercase().ifBlank { "Audio" }
        }
    }
}
