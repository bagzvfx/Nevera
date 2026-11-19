package com.nevera.corporation.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nevera.corporation.data.model.LyricLine
import com.nevera.corporation.data.model.Song
import com.nevera.corporation.ui.i18n.LocalStrings
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * OLED Glassmorphism Lyric Share Card Generator:
 * Pick 1-4 lyric lines -> renders a 1080x1350 PNG card with album artwork & dynamic accent glow -> Android ShareSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricShareCardSheet(
    song: Song,
    lines: List<LyricLine>,
    accentColor: Color,
    secondaryColor: Color,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectable = remember(lines) { lines.filter { !it.isInstrumental && it.text.isNotBlank() } }
    var selectedIndices by remember(selectable) { mutableStateOf(setOf(0).filter { it in selectable.indices }.toSet()) }
    var isRendering by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101014),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.28f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(strings.shareLyricCardLabel, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Select up to 4 lyric lines (${selectedIndices.size}/4)", color = NeveraTextSecondary, fontSize = 12.sp)
                }
                Button(
                    enabled = selectedIndices.isNotEmpty() && !isRendering,
                    onClick = {
                        isRendering = true
                        val chosenLines = selectedIndices.sorted().mapNotNull { selectable.getOrNull(it)?.text }
                        scope.launch {
                            val shared = renderAndShareLyricCard(
                                context = context,
                                song = song,
                                lyricLines = chosenLines,
                                primaryArgb = accentColor.toArgb(),
                                secondaryArgb = secondaryColor.toArgb()
                            )
                            isRendering = false
                            if (shared) {
                                onDismiss()
                            } else {
                                Toast.makeText(context, "Unable to generate lyric card", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    if (isRendering) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                    } else {
                        Icon(Icons.Rounded.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(selectable) { idx, line ->
                    val chosen = idx in selectedIndices
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (chosen) accentColor.copy(alpha = 0.22f) else Color(0xFF17171E))
                            .border(
                                0.8.dp,
                                if (chosen) accentColor else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                selectedIndices = if (chosen) {
                                    selectedIndices - idx
                                } else if (selectedIndices.size < 4) {
                                    selectedIndices + idx
                                } else {
                                    selectedIndices
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = line.text,
                            color = if (chosen) Color.White else Color.White.copy(alpha = 0.78f),
                            fontSize = 14.sp,
                            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (chosen) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Rounded.CheckCircle, null, tint = accentColor, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

private suspend fun renderAndShareLyricCard(
    context: Context,
    song: Song,
    lyricLines: List<String>,
    primaryArgb: Int,
    secondaryArgb: Int
): Boolean = withContext(Dispatchers.IO) {
    try {
        val width = 1080
        val height = 1350
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // OLED base background
        canvas.drawColor(android.graphics.Color.parseColor("#060609"))

        // Dual radial accent glow aura
        val aura1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.25f, height * 0.25f, width * 0.85f,
                intArrayOf((primaryArgb and 0x00FFFFFF) or 0x55000000, android.graphics.Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val aura2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.80f, height * 0.78f, width * 0.85f,
                intArrayOf((secondaryArgb and 0x00FFFFFF) or 0x48000000, android.graphics.Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), aura1)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), aura2)

        // Frosted inner OLED card frame
        val cardRect = RectF(64f, 80f, width - 64f, height - 80f)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(175, 16, 16, 22)
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = android.graphics.Color.argb(55, 255, 255, 255)
        }
        canvas.drawRoundRect(cardRect, 52f, 52f, cardPaint)
        canvas.drawRoundRect(cardRect, 52f, 52f, borderPaint)

        // Header: Artwork + Track Metadata
        val artBmp = fetchArtworkBitmap(song.thumbnailUrl)
        val artRect = RectF(126f, 146f, 266f, 286f)
        if (artBmp != null) {
            val clipPath = android.graphics.Path().apply {
                addRoundRect(artRect, 24f, 24f, android.graphics.Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(artBmp, null, artRect, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            artBmp.recycle()
        } else {
            canvas.drawRoundRect(artRect, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryArgb })
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(185, 255, 255, 255)
            textSize = 32f
        }
        canvas.drawText(song.title.take(28), 300f, 206f, titlePaint)
        canvas.drawText(song.artist.take(32), 300f, 256f, artistPaint)

        // Centered bold lyrics body
        val lyricText = lyricLines.joinToString("\n\n")
        val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = when {
                lyricLines.size <= 2 -> 64f
                lyricLines.size == 3 -> 54f
                else -> 46f
            }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(22f, 0f, 0f, (primaryArgb and 0x00FFFFFF) or 0x77000000)
        }
        val layout = StaticLayout.Builder.obtain(lyricText, 0, lyricText.length, lyricPaint, width - 260)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(10f, 1.08f)
            .build()
        val bodyTop = (360f + ((height - 540f) - layout.height).coerceAtLeast(0f) * 0.45f)
        canvas.save()
        canvas.translate(130f, bodyTop)
        layout.draw(canvas)
        canvas.restore()

        // Subtle Nevera watermark footer
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(150, 255, 255, 255)
            textSize = 28f
            letterSpacing = 0.16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("NEVERA", 130f, height - 134f, footerPaint)

        val outDir = File(context.cacheDir, "lyric_cards").apply { mkdirs() }
        val outFile = File(outDir, "nevera_lyric_${System.currentTimeMillis()}.png")
        FileOutputStream(outFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 96, fos)
        }
        bmp.recycle()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.artist}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(shareIntent, "Share Nevera Lyric Card"))
        }
        true
    } catch (_: Throwable) {
        false
    }
}

private fun fetchArtworkBitmap(url: String): Bitmap? {
    if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) return null
    return try {
        val client = OkHttpClient()
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (_: Throwable) {
        null
    }
}
