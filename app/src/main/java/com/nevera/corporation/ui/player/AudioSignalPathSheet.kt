package com.nevera.corporation.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nevera.corporation.data.model.AudioOutputRoute
import com.nevera.corporation.playback.AudioSignalPathBus
import com.nevera.corporation.ui.i18n.LocalStrings
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import java.util.Locale

/**
 * Roon / Poweramp-inspired live 3-stage Audio Signal Path Inspector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSignalPathSheet(
    accentColor: Color,
    onOpenStudioDsp: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    val signal by AudioSignalPathBus.state.collectAsState()

    val qualityDotColor = when {
        signal.usbBitPerfectExclusive -> Color(0xFF34D399)
        signal.isLossless -> Color(0xFFFBBF24)
        signal.bitPerfectBypass -> Color(0xFF34D399)
        else -> accentColor
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101014),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.28f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(qualityDotColor)
                    )
                    Column {
                        Text(strings.signalPathTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(signal.compactBadge, color = qualityDotColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenStudioDsp()
                    }
                ) {
                    Text("Studio DSP →", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Stage 1: Source
            val srLabel = if (signal.sourceSampleRateHz >= 1000) {
                String.format(Locale.US, "%.1f kHz", signal.sourceSampleRateHz / 1000f)
            } else "Auto Rate"
            val bitLabel = if (signal.sourceBitDepth > 0) "${signal.sourceBitDepth}-bit" else "Adaptive"
            val brLabel = if (signal.sourceBitrateKbps > 0) "${signal.sourceBitrateKbps} kbps" else "VBR"
            SignalPathNode(
                icon = Icons.Rounded.LibraryMusic,
                dotColor = if (signal.isLossless) Color(0xFFFBBF24) else accentColor,
                title = "Source · ${signal.sourceOriginLabel}",
                detail = "${signal.sourceCodec.uppercase(Locale.US)} · $bitLabel · $srLabel · $brLabel · ${signal.sourceChannels}ch",
                showConnector = true
            )

            // Stage 2: DSP Engine
            SignalPathNode(
                icon = Icons.Rounded.GraphicEq,
                dotColor = if (signal.bitPerfectBypass) Color(0xFF34D399) else accentColor,
                title = signal.dspEngineLabel,
                detail = signal.dspActiveSummary,
                showConnector = true
            )

            // Stage 3: Hardware Output
            val hwIcon = when (signal.outputRoute) {
                AudioOutputRoute.USB_DAC -> Icons.Rounded.Usb
                AudioOutputRoute.BLUETOOTH_AUDIO -> Icons.Rounded.BluetoothAudio
                AudioOutputRoute.WIRED_IEM -> Icons.Rounded.Headphones
                AudioOutputRoute.PHONE_SPEAKER -> Icons.Rounded.Speaker
            }
            val outSrLabel = if (signal.outputSampleRateHz >= 1000) {
                String.format(Locale.US, "%.1f kHz", signal.outputSampleRateHz / 1000f)
            } else "48.0 kHz"
            val mixerMode = if (signal.usbBitPerfectExclusive) {
                "Android 14+ Bit-Perfect USB Exclusive"
            } else {
                "Media3 High-Precision AudioTrack"
            }
            SignalPathNode(
                icon = hwIcon,
                dotColor = if (signal.usbBitPerfectExclusive) Color(0xFF34D399) else Color.White,
                title = "Output · ${signal.outputDeviceName}",
                detail = "${signal.outputEncodingLabel} · $outSrLabel · $mixerMode",
                showConnector = false
            )
        }
    }
}

@Composable
private fun SignalPathNode(
    icon: ImageVector,
    dotColor: Color,
    title: String,
    detail: String,
    showConnector: Boolean
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(34.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = 0.16f))
                    .border(1.dp, dotColor.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = dotColor, modifier = Modifier.size(16.dp))
            }
            if (showConnector) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(26.dp)
                        .background(Color.White.copy(alpha = 0.18f))
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF17171E))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(detail, color = NeveraTextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}
