package com.nevera.corporation.data.store

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.data.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans audio files stored locally on the Android device via MediaStore.
 */
object LocalMediaRepository {

    private val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

    suspend fun scanLocalAudio(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION
        )

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

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol).orEmpty()
                    val rawArtist = cursor.getString(artistCol).orEmpty()
                    val artist = if (rawArtist == "<unknown>" || rawArtist.isBlank()) "Artis Lokal" else rawArtist
                    val album = cursor.getString(albumCol).orEmpty()
                    val albumId = cursor.getLong(albumIdCol)
                    val durationMs = cursor.getLong(durationCol)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val artUri = ContentUris.withAppendedId(albumArtBaseUri, albumId)

                    songs.add(
                        Song(
                            id = "local_$id",
                            title = title.ifBlank { "Audio Tanpa Judul" },
                            artist = artist,
                            album = album,
                            durationSeconds = durationMs / 1000L,
                            thumbnailUrl = artUri.toString(),
                            source = SourceKind.LOCAL,
                            sourceTrackId = id.toString(),
                            localUri = contentUri.toString()
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
}
