package com.nevera.corporation.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceKind {
    YOUTUBE,
    JIOSAAVN,
    SPOTIFY,
    LOCAL,
    NETWORK_SERVER
}

@Serializable
enum class AudioQuality(val label: String, val maxKbps: Int) {
    LOW("Data Saver (96 kbps)", 96),
    HIGH("Balanced (160 kbps)", 160),
    BEST("High Resolution (320 kbps)", 320)
}

@Serializable
enum class HarmonicVfxMode(val label: String, val description: String) {
    FULL("Full", "Acoustic wave rings, touch glow & adaptive bokeh"),
    SUBTLE("Subtle", "Soft spring press feedback without particles"),
    OFF("Off", "Disable extra interactive visual effects")
}

@Serializable
enum class PlayerVisualMode(val label: String) {
    CLEAN_SLEEVE("Minimalist Sleeve"),
    VINYL_HALO("Vinyl & Harmonic Halo")
}

@Serializable
enum class VisualizerStyle(val label: String) {
    UPWARD_BARS("Vertical Equalizer"),
    SYMMETRIC_BARS("Mirror Equalizer"),
    OFF("Off")
}

@Serializable
enum class CollectionType {
    ALBUM,
    PLAYLIST,
    ARTIST
}

@Serializable
enum class AppLanguage(val code: String, val nativeName: String) {
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語 (Japanese)"),
    CHINESE("zh", "中文 (Chinese)"),
    JAVANESE("jv", "Basa Jawa (Indonesia)"),
    INDONESIAN("id", "Bahasa Indonesia"),
    SUNDA("su", "Basa Sunda (Indonesia)")
}

enum class SearchFilter(val params: String?) {
    ALL(null),
    SONGS("EgWKAQIIAWoKEAkQChAFEAMQBA=="),
    ARTISTS("EgWKAQIgAWoKEAkQChAFEAMQBA=="),
    ALBUMS("EgWKAQIYAWoKEAkQChAFEAMQBA==")
}

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String = "",
    val source: SourceKind = SourceKind.YOUTUBE,
    val sourceTrackId: String = id,
    val localUri: String = "",
    val directStreamUrl: String = "",
    val browseId: String = "",
    val browseType: CollectionType? = null,
    val codecHint: String = "",
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
    val serverId: String = "",
    val remotePath: String = ""
) {
    val durationMs: Long get() = durationSeconds * 1000L
    val isCollection: Boolean get() = browseId.isNotBlank() && sourceTrackId.isBlank()
    val isLosslessHint: Boolean
        get() = codecHint.equals("FLAC", true) ||
            codecHint.equals("WAV", true) ||
            codecHint.equals("ALAC", true) ||
            codecHint.equals("AIFF", true) ||
            codecHint.equals("DSD", true)

    val playbackUri: String
        get() = when {
            localUri.isNotBlank() -> localUri
            else -> "nevera://track?s=${source.name}&id=$sourceTrackId&vid=$id"
        }
}

data class CollectionDetail(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String,
    val type: CollectionType,
    val tracks: List<Song>
)

enum class ShelfLayout {
    CAROUSEL,
    TOP_PICKS_PORTRAIT,
    FOUR_ROW_PAGER
}

data class HomeShelf(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val layout: ShelfLayout = ShelfLayout.CAROUSEL,
    val items: List<Song>
)

data class MoodGenre(
    val title: String,
    val query: String,
    val accentHex: Long
)

data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val charStart: Int = -1,
    val charEnd: Int = -1
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val endTimeMs: Long = 0L,
    val romajiText: String = "",
    val translatedText: String = "",
    val words: List<LyricWord> = emptyList(),
    val isInstrumental: Boolean = false
)

@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val songs: List<Song> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val sourceUrl: String = ""
)

data class SourceStream(
    val url: String,
    val codec: String,
    val kbps: Int,
    val qualityLabel: String,
    val headers: Map<String, String> = emptyMap(),
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val isLossless: Boolean = false,
    val originLabel: String = "Studio Stream"
)

@Serializable
data class AppSettingsState(
    val appLanguage: AppLanguage = AppLanguage.ENGLISH,
    val wifiQuality: AudioQuality = AudioQuality.BEST,
    val cellularQuality: AudioQuality = AudioQuality.HIGH,
    val downloadQuality: AudioQuality = AudioQuality.BEST,
    val wifiOnlyDownloads: Boolean = false,
    val preferHighResAudio: Boolean = true,
    val crossfadeSeconds: Int = 0,
    val skipSilence: Boolean = false,
    val loudnessNormalization: Boolean = true,
    val harmonicVfxMode: HarmonicVfxMode = HarmonicVfxMode.FULL,
    val playerVisualMode: PlayerVisualMode = PlayerVisualMode.CLEAN_SLEEVE,
    val visualizerStyle: VisualizerStyle = VisualizerStyle.UPWARD_BARS,
    val lyricsOffsetMs: Long = 0L,
    val showAudioStats: Boolean = true,
    val cacheLimitMb: Int = 512,
    val dspProfile: AudiophileDspProfile = AudiophileDspProfile(),
    val perDeviceProfiles: Map<String, AudiophileDspProfile> = emptyMap(),
    val autoSwitchDeviceProfile: Boolean = true,
    val smartQueuePrefetch: Boolean = true,
    val musicHapticsEnabled: Boolean = false,
    val preferPersonalLosslessServer: Boolean = true,
    val dualLineLyrics: Boolean = false,
    val playbackPitch: Float = 1.0f,
    val tuning432Hz: Boolean = false
)
