package com.nevera.corporation.ui.player

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nevera.corporation.data.audio.AutoEqCatalog
import com.nevera.corporation.data.model.AudioOutputRoute
import com.nevera.corporation.data.model.AudiophileDspProfile
import com.nevera.corporation.data.model.CrossfeedMode
import com.nevera.corporation.data.native.NeveraNativeEngine
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.playback.AudioHardwareRouter
import com.nevera.corporation.ui.i18n.LocalStrings
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Nevera v2.0 Studio Audiophile Sheet:
 * - Real-time 64-bit Biquad Frequency Response Curve Canvas
 * - Bit-Perfect Direct Bypass & Per-Device Hardware Route Banner
 * - AutoEQ IEM/Headphone Presets + Custom AutoEQ .txt Importer
 * - 10-Band Parametric Gain Sliders, Auto-Preamp, BS2B Crossfeed, M/S Width, L/R Balance, Tube Warmth, EBU R128 & Music Haptics
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiophileStudioSheet(
    accentColor: Color,
    onUpdateProfile: (AudiophileDspProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val settings by LibraryStore.settings.collectAsState()
    val profile = settings.dspProfile
    val activeRoute by AudioHardwareRouter.activeRoute.collectAsState()
    val activeDeviceName by AudioHardwareRouter.activeDeviceName.collectAsState()
    val usbBitPerfect by AudioHardwareRouter.usbBitPerfectActive.collectAsState()

    var showAutoEqImport by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101014),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.28f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 660.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.studioDspTitle,
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (profile.bitPerfectBypass) {
                            "Bit-Perfect Direct Path • Zero DSP"
                        } else {
                            "64-bit Double-Precision PCM Engine"
                        },
                        color = if (profile.bitPerfectBypass) Color(0xFF34D399) else NeveraTextSecondary,
                        fontSize = 12.sp
                    )
                }

                TextButton(
                    onClick = {
                        onUpdateProfile(
                            profile.copy(
                                enabled = true,
                                bitPerfectBypass = false,
                                presetName = "Flat Reference",
                                bands = AudiophileDspProfile.DEFAULT_TEN_BANDS,
                                preampDb = 0f,
                                stereoWidth = 1.0f,
                                channelBalance = 0f,
                                tubeWarmth = 0f
                            )
                        )
                    }
                ) {
                    Text("Reset", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
                }
            }

            // Hardware Route & Bit-Perfect Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF17171D))
                    .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val routeIcon = when (activeRoute) {
                        AudioOutputRoute.USB_DAC -> Icons.Rounded.Usb
                        AudioOutputRoute.BLUETOOTH_AUDIO -> Icons.Rounded.BluetoothAudio
                        AudioOutputRoute.WIRED_IEM -> Icons.Rounded.Headphones
                        AudioOutputRoute.PHONE_SPEAKER -> Icons.Rounded.Speaker
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(routeIcon, null, tint = accentColor, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(activeDeviceName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = when {
                                usbBitPerfect -> "Android 14+ USB Bit-Perfect Exclusive"
                                settings.autoSwitchDeviceProfile -> "${activeRoute.displayLabel} • Auto-Profile Active"
                                else -> activeRoute.displayLabel
                            },
                            color = if (usbBitPerfect) Color(0xFF34D399) else NeveraTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = profile.bitPerfectBypass,
                        onCheckedChange = { bypass ->
                            onUpdateProfile(profile.copy(bitPerfectBypass = bypass))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color(0xFF34D399)
                        )
                    )
                }
                Text(
                    text = if (profile.bitPerfectBypass) {
                        "Bit-Perfect Direct ON — All EQ, Crossfeed & Loudness stages are bypassed for untouched PCM output."
                    } else {
                        "Toggle right for Bit-Perfect Direct Bypass (recommended for pure USB-DAC playback)."
                    },
                    color = NeveraTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            // Live 64-bit Frequency Response Curve
            FrequencyResponseCurveCard(
                profile = profile,
                accentColor = if (profile.bitPerfectBypass) Color(0xFF34D399) else accentColor
            )

            // AutoEQ & Studio Presets Row + Import .txt
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Studio & AutoEQ Profiles", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier.clickable { showAutoEqImport = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Rounded.FileUpload, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text("Import .txt", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AutoEqCatalog.presets, key = { it.name }) { preset ->
                    val selected = !profile.bitPerfectBypass && profile.presetName.equals(preset.name, true)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) accentColor else Color(0xFF191920),
                        modifier = Modifier
                            .border(
                                0.7.dp,
                                if (selected) accentColor else Color.White.copy(alpha = 0.08f),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                onUpdateProfile(AutoEqCatalog.applyPreset(profile, preset))
                            }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = preset.name,
                                color = if (selected) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = preset.category,
                                color = if (selected) Color.Black.copy(alpha = 0.7f) else NeveraTextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // 10-Band Parametric Gain Controls (scroll-friendly horizontal rack)
            val bands = profile.normalizedBands()
            val eqEnabled = profile.enabled && !profile.bitPerfectBypass
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                bands.forEachIndexed { idx, band ->
                    Column(
                        modifier = Modifier
                            .width(62.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF16161C))
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = String.format(Locale.US, "%+.1f", band.gainDb),
                            color = if (eqEnabled && kotlin.math.abs(band.gainDb) > 0.1f) accentColor else NeveraTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // Quick +/- fine-tune stepper + compact slider for precision touch on mobile
                        IconButton(
                            onClick = {
                                val nextGain = (band.gainDb + 1.0f).coerceIn(-12f, 12f)
                                val updatedBands = bands.toMutableList().also { it[idx] = band.copy(gainDb = nextGain) }
                                onUpdateProfile(profile.copy(enabled = true, bitPerfectBypass = false, presetName = "Custom", bands = updatedBands))
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                        }
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(64.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center
                        ) {
                            val norm = (band.gainDb / 12f).coerceIn(-1f, 1f)
                            val barFraction = kotlin.math.abs(norm) * 0.5f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(barFraction.coerceAtLeast(0.04f))
                                    .align(if (norm >= 0f) Alignment.TopCenter else Alignment.BottomCenter)
                                    .offset(y = if (norm >= 0f) (32f * (1f - kotlin.math.abs(norm))).dp else (-32f * (1f - kotlin.math.abs(norm))).dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (eqEnabled) accentColor else Color.White.copy(alpha = 0.25f))
                            )
                        }
                        IconButton(
                            onClick = {
                                val nextGain = (band.gainDb - 1.0f).coerceIn(-12f, 12f)
                                val updatedBands = bands.toMutableList().also { it[idx] = band.copy(gainDb = nextGain) }
                                onUpdateProfile(profile.copy(enabled = true, bitPerfectBypass = false, presetName = "Custom", bands = updatedBands))
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Rounded.Remove, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                        }
                        Text(
                            text = formatHzLabel(band.frequencyHz),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // BS2B Headphone Crossfeed
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF16161C))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("BS2B Headphone Crossfeed", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CrossfeedMode.values().forEach { mode ->
                        val active = !profile.bitPerfectBypass && profile.crossfeedMode == mode
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) accentColor else Color(0xFF22222A),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    onUpdateProfile(profile.copy(enabled = true, bitPerfectBypass = false, crossfeedMode = mode))
                                }
                        ) {
                            Text(
                                text = mode.label,
                                color = if (active) Color.Black else Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                // Preamp
                StudioSliderRow(
                    label = "Preamp Headroom",
                    valueText = String.format(Locale.US, "%+.1f dB", profile.preampDb),
                    value = profile.preampDb,
                    range = -12f..6f,
                    accentColor = accentColor,
                    enabled = eqEnabled,
                    onValueChange = { v ->
                        onUpdateProfile(profile.copy(preampDb = (v * 2f).roundToInt() / 2f))
                    }
                )

                // Stereo Width (M/S)
                StudioSliderRow(
                    label = "Stereo Width (M/S)",
                    valueText = "${(profile.stereoWidth * 100f).roundToInt()}%",
                    value = profile.stereoWidth,
                    range = 0.5f..1.6f,
                    accentColor = accentColor,
                    enabled = eqEnabled,
                    onValueChange = { v ->
                        onUpdateProfile(profile.copy(stereoWidth = (v * 20f).roundToInt() / 20f))
                    }
                )

                // L/R Balance
                val balLabel = when {
                    profile.channelBalance < -0.04f -> "L ${(kotlin.math.abs(profile.channelBalance) * 100).roundToInt()}%"
                    profile.channelBalance > 0.04f -> "R ${(profile.channelBalance * 100).roundToInt()}%"
                    else -> "Center"
                }
                StudioSliderRow(
                    label = "Channel Balance (L / R)",
                    valueText = balLabel,
                    value = profile.channelBalance,
                    range = -0.8f..0.8f,
                    accentColor = accentColor,
                    enabled = eqEnabled,
                    onValueChange = { v ->
                        val snapped = if (kotlin.math.abs(v) < 0.05f) 0f else (v * 20f).roundToInt() / 20f
                        onUpdateProfile(profile.copy(channelBalance = snapped))
                    }
                )

                // Tube Warmth
                StudioSliderRow(
                    label = "Analog Tube Warmth",
                    valueText = "${(profile.tubeWarmth * 100f).roundToInt()}%",
                    value = profile.tubeWarmth,
                    range = 0f..1f,
                    accentColor = accentColor,
                    enabled = eqEnabled,
                    onValueChange = { v ->
                        onUpdateProfile(profile.copy(tubeWarmth = (v * 20f).roundToInt() / 20f))
                    }
                )
            }

            // Quick Mastering Toggles
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF16161C))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                StudioSwitchRow(
                    title = "EBU R128 Loudness (-14 LUFS)",
                    subtitle = "Consistent studio volume without squashing peaks",
                    checked = profile.lufsNormalization && !profile.bitPerfectBypass,
                    accentColor = accentColor,
                    onCheckedChange = { c -> onUpdateProfile(profile.copy(lufsNormalization = c)) }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                StudioSwitchRow(
                    title = "Auto-Headroom Clipping Guard",
                    subtitle = "Automatic negative preamp + True-Peak -0.3 dBFS limiter",
                    checked = profile.autoPreamp,
                    accentColor = accentColor,
                    onCheckedChange = { c -> onUpdateProfile(profile.copy(autoPreamp = c)) }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                StudioSwitchRow(
                    title = strings.musicHapticsLabel,
                    subtitle = "Subtle tactile bass pulses synced to C++ FFT beat detection",
                    checked = settings.musicHapticsEnabled,
                    accentColor = accentColor,
                    onCheckedChange = { c -> LibraryStore.updateSettings { it.copy(musicHapticsEnabled = c) } }
                )
            }
        }
    }

    if (showAutoEqImport) {
        AutoEqImportDialog(
            onDismiss = { showAutoEqImport = false },
            onImported = { parsed ->
                onUpdateProfile(
                    profile.copy(
                        enabled = true,
                        bitPerfectBypass = false,
                        presetName = parsed.name,
                        preampDb = parsed.preampDb,
                        bands = parsed.bands
                    )
                )
                Toast.makeText(
                    context,
                    "Imported ${parsed.parsedFilterCount} AutoEQ filters (${parsed.name})",
                    Toast.LENGTH_SHORT
                ).show()
                showAutoEqImport = false
            }
        )
    }
}

@Composable
private fun FrequencyResponseCurveCard(
    profile: AudiophileDspProfile,
    accentColor: Color
) {
    val sampleCount = 72
    val evalFreqs = remember {
        FloatArray(sampleCount) { i ->
            val t = i.toFloat() / (sampleCount - 1).toFloat()
            (25.0 * (20000.0 / 25.0).pow(t.toDouble())).toFloat()
        }
    }
    val curveDb = remember(profile) {
        NeveraNativeEngine.computeEqCurveDb(profile, evalFreqs, 48000)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A0A0E))
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val midY = h * 0.5f

            // Grid lines (-12, -6, 0, +6, +12 dB)
            listOf(-12f, -6f, 0f, 6f, 12f).forEach { db ->
                val y = midY - (db / 14f) * (h * 0.45f)
                drawLine(
                    color = if (db == 0f) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = if (db == 0f) 1.5f else 1f
                )
            }

            // Build response curve path
            val linePath = Path()
            val fillPath = Path()
            for (i in curveDb.indices) {
                val x = (i.toFloat() / (curveDb.size - 1).toFloat()) * w
                val y = (midY - (curveDb[i] / 14f) * (h * 0.45f)).coerceIn(4f, h - 4f)
                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, midY)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(w, midY)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(accentColor.copy(alpha = 0.28f), accentColor.copy(alpha = 0.02f))
                )
            )
            drawPath(
                path = linePath,
                color = accentColor,
                style = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("31Hz", "125Hz", "500Hz", "2kHz", "8kHz", "16kHz").forEach { lbl ->
                Text(lbl, color = Color.White.copy(alpha = 0.35f), fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun StudioSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accentColor: Color,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White.copy(alpha = if (enabled) 0.9f else 0.45f), fontSize = 12.sp)
            Text(valueText, color = if (enabled) accentColor else NeveraTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accentColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.14f)
            )
        )
    }
}

@Composable
private fun StudioSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accentColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = NeveraTextSecondary, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = accentColor
            )
        )
    }
}

@Composable
private fun AutoEqImportDialog(
    onDismiss: () -> Unit,
    onImported: (AutoEqCatalog.ParsedAutoEqResult) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161C),
        title = { Text("Import AutoEQ / ParametricEQ (.txt)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name (e.g. IEM Model)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; errorMsg = null },
                    label = { Text("Paste AutoEQ / Squig.link filters") },
                    placeholder = { Text("Preamp: -4.2 dB\nFilter 1: ON PK Fc 62 Hz Gain 3.5 dB Q 1.4") },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                errorMsg?.let { Text(it, color = Color(0xFFFA2D48), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = AutoEqCatalog.parseAutoEqText(text, name.ifBlank { "Custom AutoEQ" })
                    if (parsed != null) {
                        onImported(parsed)
                    } else {
                        errorMsg = "No valid AutoEQ filters found. Use 'Filter 1: ON PK Fc ... Hz Gain ... dB Q ...'."
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) {
                Text("Apply", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.7f)) }
        }
    )
}

private fun formatHzLabel(hz: Float): String {
    return if (hz >= 1000f) {
        val k = hz / 1000f
        if (k % 1f == 0f) "${k.toInt()}k" else String.format(Locale.US, "%.1fk", k)
    } else {
        "${hz.toInt()}"
    }
}
