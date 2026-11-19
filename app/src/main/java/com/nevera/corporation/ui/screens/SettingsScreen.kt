package com.nevera.corporation.ui.screens

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nevera.corporation.BuildConfig
import com.nevera.corporation.R
import com.nevera.corporation.data.model.AppLanguage
import com.nevera.corporation.data.model.AudioQuality
import com.nevera.corporation.data.model.AudiophileDspProfile
import com.nevera.corporation.data.model.HarmonicVfxMode
import com.nevera.corporation.data.model.PlayerVisualMode
import com.nevera.corporation.data.model.VisualizerStyle
import com.nevera.corporation.data.sources.AccountSyncManager
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.data.store.NetworkStorageRepository
import com.nevera.corporation.playback.AudioHardwareRouter
import com.nevera.corporation.playback.PlaybackService
import com.nevera.corporation.ui.components.NetworkStorageManagerDialog
import com.nevera.corporation.ui.components.PlaylistImportDialog
import com.nevera.corporation.ui.components.YouTubeLoginSyncDialog
import com.nevera.corporation.ui.components.syncAccountPlaylistsNow
import com.nevera.corporation.ui.i18n.LocalStrings
import com.nevera.corporation.ui.player.AudiophileStudioSheet
import com.nevera.corporation.ui.theme.NeveraBlack
import com.nevera.corporation.ui.theme.NeveraHairline
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full Nevera OLED Settings Screen:
 * - No Backup/Restore JSON clutter.
 * - Includes 6-language switcher (English default), Account Login Sync, Playlist Link Import,
 *   Audio Quality, DynamicsProcessing Mastering, Harmonic VFX, and Storage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    accentColor: Color,
    onUpdateDspProfile: (AudiophileDspProfile) -> Unit = { AudioHardwareRouter.onUserProfileEdited(it) },
    onBack: () -> Unit
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by LibraryStore.settings.collectAsState()
    val isSignedIn by AccountSyncManager.isSignedIn.collectAsState()
    val networkServers by NetworkStorageRepository.servers.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var cacheUsedMb by remember { mutableLongStateOf(PlaybackService.getCacheSizeMb(context)) }

    var activeChoiceDialog by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showLoginSyncDialog by remember { mutableStateOf(false) }
    var showStudioDspSheet by remember { mutableStateOf(false) }
    var showNetworkServerDialog by remember { mutableStateOf(false) }

    fun matchesSearch(vararg keywords: String): Boolean {
        if (searchQuery.isBlank()) return true
        return keywords.any { it.contains(searchQuery, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NeveraBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, strings.close, tint = Color.White)
            }
            Text(
                text = strings.settingsTitle,
                style = MaterialTheme.typography.headlineMedium.copy(color = Color.White)
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            placeholder = { Text(strings.searchPlaceholder, color = NeveraTextSecondary, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = NeveraTextSecondary, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Rounded.Clear, null, tint = NeveraTextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1C1C1F),
                unfocusedContainerColor = Color(0xFF141417),
                focusedBorderColor = Color.White.copy(alpha = 0.2f),
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // 1. General: Language + Playlist Import + Account Sync
            if (matchesSearch("language", "bahasa", "sync", "login", "playlist", "import", "account")) {
                item(key = "grp_general") {
                    SettingsGroup(title = strings.settingsGeneral) {
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Language,
                            title = strings.settingsLanguage,
                            value = settings.appLanguage.nativeName.substringBefore(" ("),
                            onClick = { activeChoiceDialog = "language" }
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Sync,
                            title = strings.settingsAccountSync,
                            value = if (isSignedIn) strings.syncSignedIn else strings.syncNotSignedIn,
                            onClick = { activeChoiceDialog = "account_sync" }
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Link,
                            title = strings.settingsImportPlaylist,
                            value = "URL",
                            onClick = { showImportDialog = true }
                        )
                    }
                }
            }

            // 2. Audio & Streaming Quality
            if (matchesSearch("audio", "quality", "wifi", "mobile", "download")) {
                item(key = "grp_audio") {
                    SettingsGroup(title = strings.settingsAudioQuality) {
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Wifi,
                            title = strings.settingsWifiQuality,
                            value = settings.wifiQuality.label,
                            onClick = { activeChoiceDialog = "wifi_quality" }
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = Icons.Rounded.SignalCellularAlt,
                            title = strings.settingsCellularQuality,
                            value = settings.cellularQuality.label,
                            onClick = { activeChoiceDialog = "cell_quality" }
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Download,
                            title = strings.settingsDownloadQuality,
                            value = settings.downloadQuality.label,
                            onClick = { activeChoiceDialog = "dl_quality" }
                        )
                        SettingsDivider()
                        SettingsToggleRow(
                            icon = Icons.Rounded.WifiLock,
                            title = strings.settingsWifiOnlyDl,
                            checked = settings.wifiOnlyDownloads,
                            onCheckedChange = { c -> LibraryStore.updateSettings { it.copy(wifiOnlyDownloads = c) } }
                        )
                    }
                }
            }

            // 2.5 Audiophile Studio & Lossless Network Storage
            if (matchesSearch("audiophile", "dsp", "eq", "dac", "bit", "perfect", "lossless", "server", "subsonic", "webdav", "prefetch", "haptics")) {
                item(key = "grp_audiophile") {
                    SettingsGroup(
                        title = "AUDIOPHILE & LOSSLESS STREAMING",
                        footer = "64-bit double-precision C++17 DSP, AutoEQ IEM profiles, Bit-Perfect USB-DAC (Android 14+), Smart Queue Prefetch Cache, and personal Subsonic/WebDAV lossless servers."
                    ) {
                        SettingsChoiceRow(
                            icon = Icons.Rounded.GraphicEq,
                            title = strings.studioDspTitle,
                            value = if (settings.dspProfile.bitPerfectBypass) "Bit-Perfect" else settings.dspProfile.presetName,
                            onClick = { showStudioDspSheet = true }
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Dns,
                            title = strings.networkStorageTitle,
                            value = if (networkServers.isEmpty()) "Add" else "${networkServers.size} active",
                            onClick = { showNetworkServerDialog = true }
                        )
                        SettingsDivider()
                        SettingsToggleRow(
                            icon = Icons.Rounded.CloudDone,
                            title = strings.preferLosslessServer,
                            checked = settings.preferPersonalLosslessServer,
                            onCheckedChange = { c -> LibraryStore.updateSettings { it.copy(preferPersonalLosslessServer = c) } }
                        )
                        SettingsDivider()
                        SettingsToggleRow(
                            icon = Icons.Rounded.Bolt,
                            title = strings.smartPrefetchLabel,
                            checked = settings.smartQueuePrefetch,
                            onCheckedChange = { c -> LibraryStore.updateSettings { it.copy(smartQueuePrefetch = c) } }
                        )
                        SettingsDivider()
                        SettingsToggleRow(
                            icon = Icons.Rounded.Headphones,
                            title = strings.perDeviceAutoProfile,
                            checked = settings.autoSwitchDeviceProfile,
                            onCheckedChange = { c -> LibraryStore.updateSettings { it.copy(autoSwitchDeviceProfile = c) } }
                        )
                        SettingsDivider()
                        SettingsToggleRow(
                            icon = Icons.Rounded.Vibration,
                            title = strings.musicHapticsLabel,
                            checked = settings.musicHapticsEnabled,
                            onCheckedChange = { c -> LibraryStore.updateSettings { it.copy(musicHapticsEnabled = c) } }
                        )
                    }
                }
            }

            // 3. Playback & Audio Mastering
            if (matchesSearch("playback", "crossfade", "loudness", "silence", "equalizer")) {
                item(key = "grp_playback") {
                    SettingsGroup(title = strings.settingsPlayback) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Waves, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(strings.settingsCrossfade, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                Text(
                                    text = if (settings.crossfadeSeconds == 0) "Off" else "${settings.crossfadeSeconds}s",
                                    color = NeveraTextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                            Slider(
                                value = settings.crossfadeSeconds.toFloat(),
                                onValueChange = { v -> LibraryStore.updateSettings { it.copy(crossfadeSeconds = v.roundToInt()) } },
                                valueRange = 0f..12f,
                                steps = 11,
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accentColor)
                            )
                        }
                        SettingsDivider()
                        SettingsToggleRow(
                            icon = Icons.Rounded.FastForward,
                            title = strings.settingsSkipSilence,
                            checked = settings.skipSilence,
                            onCheckedChange = { c -> LibraryStore.updateSettings { it.copy(skipSilence = c) } }
                        )
                        SettingsDivider()
                        SettingsToggleRow(
                            icon = Icons.Rounded.GraphicEq,
                            title = strings.settingsLoudness,
                            checked = settings.loudnessNormalization,
                            onCheckedChange = { c -> LibraryStore.updateSettings { it.copy(loudnessNormalization = c) } }
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Tune,
                            title = strings.settingsSystemEq,
                            value = "→",
                            onClick = {
                                try {
                                    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                                    intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, strings.equalizerTitle, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }

            // 4. Effects & Appearance
            if (matchesSearch("effect", "vfx", "harmonic", "player", "visual", "stats")) {
                item(key = "grp_visual") {
                    SettingsGroup(title = strings.settingsAppearance) {
                        SettingsChoiceRow(
                            icon = Icons.Rounded.AutoAwesome,
                            title = strings.settingsVfxMode,
                            value = settings.harmonicVfxMode.label,
                            onClick = { activeChoiceDialog = "vfx_mode" }
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Album,
                            title = strings.settingsPlayerStyle,
                            value = settings.playerVisualMode.label,
                            onClick = { activeChoiceDialog = "player_visual" }
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Equalizer,
                            title = strings.visualizerLabel,
                            value = settings.visualizerStyle.label,
                            onClick = { activeChoiceDialog = "visualizer" }
                        )
                        SettingsDivider()
                        SettingsToggleRow(
                            icon = Icons.Rounded.BarChart,
                            title = strings.settingsAudioStats,
                            checked = settings.showAudioStats,
                            onCheckedChange = { c -> LibraryStore.updateSettings { it.copy(showAudioStats = c) } }
                        )
                    }
                }
            }

            // 5. Storage
            if (matchesSearch("storage", "cache", "history", "clear")) {
                item(key = "grp_storage") {
                    SettingsGroup(title = strings.settingsStorage) {
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Storage,
                            title = strings.settingsCacheLimit,
                            value = "${settings.cacheLimitMb} MB",
                            onClick = { activeChoiceDialog = "cache_limit" }
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = Icons.Rounded.DeleteSweep,
                            title = "Audio Cache",
                            value = "$cacheUsedMb MB",
                            onClick = {
                                PlaybackService.clearAudioCache(context)
                                cacheUsedMb = PlaybackService.getCacheSizeMb(context)
                            }
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = Icons.Rounded.History,
                            title = strings.settingsClearHistory,
                            value = "×",
                            onClick = {
                                LibraryStore.clearPlayHistory()
                                LibraryStore.clearSearchHistory()
                                Toast.makeText(context, strings.historyClearedToast, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            // 6. About Nevera
            item(key = "about_footer") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_nevera_launcher),
                        contentDescription = "Nevera Logo",
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Nevera", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("v${BuildConfig.VERSION_NAME} • com.nevera.corporation", color = NeveraTextSecondary, fontSize = 12.sp)
                }
            }
        }
    }

    activeChoiceDialog?.let { kind ->
        AlertDialog(
            onDismissRequest = { activeChoiceDialog = null },
            containerColor = Color(0xFF16161A),
            title = {
                Text(
                    when (kind) {
                        "language" -> strings.settingsLanguage
                        "account_sync" -> strings.settingsAccountSync
                        "wifi_quality" -> strings.settingsWifiQuality
                        "cell_quality" -> strings.settingsCellularQuality
                        "dl_quality" -> strings.settingsDownloadQuality
                        "vfx_mode" -> strings.settingsVfxMode
                        "player_visual" -> strings.settingsPlayerStyle
                        "visualizer" -> strings.visualizerLabel
                        else -> strings.settingsCacheLimit
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (kind) {
                        "language" -> {
                            AppLanguage.values().forEach { lang ->
                                ChoiceDialogRow(lang.nativeName, settings.appLanguage == lang) {
                                    LibraryStore.updateSettings { it.copy(appLanguage = lang) }
                                    activeChoiceDialog = null
                                }
                            }
                        }

                        "account_sync" -> {
                            if (!isSignedIn) {
                                Button(
                                    onClick = {
                                        activeChoiceDialog = null
                                        showLoginSyncDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                                ) {
                                    Text(strings.syncSignInAction, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        activeChoiceDialog = null
                                        scope.launch {
                                            val count = syncAccountPlaylistsNow()
                                            Toast.makeText(context, "${strings.syncCompleteToast} ($count)", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                                ) {
                                    Text(strings.syncNowAction, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        AccountSyncManager.signOut()
                                        activeChoiceDialog = null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(strings.syncSignOutAction, color = Color.White)
                                }
                            }
                        }

                        "wifi_quality", "cell_quality", "dl_quality" -> {
                            AudioQuality.values().forEach { q ->
                                val selected = when (kind) {
                                    "wifi_quality" -> settings.wifiQuality == q
                                    "cell_quality" -> settings.cellularQuality == q
                                    else -> settings.downloadQuality == q
                                }
                                ChoiceDialogRow(q.label, selected) {
                                    LibraryStore.updateSettings {
                                        when (kind) {
                                            "wifi_quality" -> it.copy(wifiQuality = q)
                                            "cell_quality" -> it.copy(cellularQuality = q)
                                            else -> it.copy(downloadQuality = q)
                                        }
                                    }
                                    activeChoiceDialog = null
                                }
                            }
                        }

                        "vfx_mode" -> {
                            HarmonicVfxMode.values().forEach { m ->
                                ChoiceDialogRow("${m.label}\n${m.description}", settings.harmonicVfxMode == m) {
                                    LibraryStore.updateSettings { it.copy(harmonicVfxMode = m) }
                                    activeChoiceDialog = null
                                }
                            }
                        }

                        "player_visual" -> {
                            PlayerVisualMode.values().forEach { m ->
                                ChoiceDialogRow(m.label, settings.playerVisualMode == m) {
                                    LibraryStore.updateSettings { it.copy(playerVisualMode = m) }
                                    activeChoiceDialog = null
                                }
                            }
                        }

                        "visualizer" -> {
                            VisualizerStyle.values().forEach { v ->
                                ChoiceDialogRow(v.label, settings.visualizerStyle == v) {
                                    LibraryStore.updateSettings { it.copy(visualizerStyle = v) }
                                    activeChoiceDialog = null
                                }
                            }
                        }

                        "cache_limit" -> {
                            listOf(256, 512, 1024, 2048).forEach { mb ->
                                ChoiceDialogRow("$mb MB", settings.cacheLimitMb == mb) {
                                    LibraryStore.updateSettings { it.copy(cacheLimitMb = mb) }
                                    activeChoiceDialog = null
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeChoiceDialog = null }) { Text(strings.close) }
            }
        )
    }

    if (showImportDialog) {
        PlaylistImportDialog(onDismiss = { showImportDialog = false })
    }

    if (showLoginSyncDialog) {
        YouTubeLoginSyncDialog(
            onSynced = {},
            onDismiss = { showLoginSyncDialog = false }
        )
    }

    if (showStudioDspSheet) {
        AudiophileStudioSheet(
            accentColor = accentColor,
            onUpdateProfile = onUpdateDspProfile,
            onDismiss = { showStudioDspSheet = false }
        )
    }

    if (showNetworkServerDialog) {
        NetworkStorageManagerDialog(
            accentColor = accentColor,
            onDismiss = { showNetworkServerDialog = false }
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = NeveraTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF161619))
        ) {
            content()
        }
        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                color = NeveraTextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 50.dp),
        thickness = 0.5.dp,
        color = NeveraHairline
    )
}

@Composable
private fun SettingsChoiceRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = NeveraTextSecondary, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = NeveraTextSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF2C2C32)
            )
        )
    }
}

@Composable
private fun ChoiceDialogRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Color.White)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}
