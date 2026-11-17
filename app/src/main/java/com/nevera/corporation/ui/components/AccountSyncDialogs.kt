package com.nevera.corporation.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nevera.corporation.data.sources.AccountSyncManager
import com.nevera.corporation.data.sources.InnertubeClient
import com.nevera.corporation.data.sources.PlaylistImportEngine
import com.nevera.corporation.data.store.LibraryStore
import com.nevera.corporation.ui.i18n.LocalStrings
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import kotlinx.coroutines.launch

@Composable
fun PlaylistImportDialog(
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        containerColor = Color(0xFF16161A),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text(strings.importUrlTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    enabled = !isImporting,
                    placeholder = { Text(strings.importUrlHint, color = NeveraTextSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (isImporting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Text(
                            text = progressText.ifBlank { strings.importingLabel },
                            color = NeveraTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isImporting && urlInput.isNotBlank(),
                onClick = {
                    isImporting = true
                    progressText = strings.importingLabel
                    scope.launch {
                        val imported = PlaylistImportEngine.importFromUrl(urlInput) { done, total ->
                            progressText = "${strings.importingLabel} ($done/$total)"
                        }
                        isImporting = false
                        if (imported != null) {
                            Toast.makeText(
                                context,
                                "${strings.importSuccessToast} (${imported.songs.size})",
                                Toast.LENGTH_SHORT
                            ).show()
                            onDismiss()
                        } else {
                            Toast.makeText(context, strings.importFailedToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(enabled = !isImporting, onClick = onDismiss) {
                Text(strings.cancel)
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginSyncDialog(
    onSynced: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSyncing by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0D))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = strings.settingsAccountSync,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, strings.close, tint = Color.White)
                }
            }

            if (isSyncing) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(strings.importingLabel, color = NeveraTextSecondary)
                    }
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                                    if (AccountSyncManager.saveCookie(cookie) && !isSyncing) {
                                        isSyncing = true
                                        scope.launch {
                                            val count = syncAccountPlaylistsNow()
                                            Toast.makeText(
                                                context,
                                                "${strings.syncCompleteToast} ($count)",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            onSynced(count)
                                            onDismiss()
                                        }
                                    }
                                }
                            }
                            loadUrl("https://accounts.google.com/ServiceLogin?continue=https://music.youtube.com/")
                        }
                    }
                )
            }
        }
    }
}

suspend fun syncAccountPlaylistsNow(): Int {
    if (!AccountSyncManager.isSignedIn.value) return 0
    val playlists = InnertubeClient.getUserAccountPlaylists()
    var synced = 0
    for (pl in playlists.take(15)) {
        val detail = InnertubeClient.browseCollection(pl)
        if (detail != null && detail.tracks.isNotEmpty()) {
            LibraryStore.saveImportedPlaylist(
                name = detail.title,
                songs = detail.tracks,
                sourceUrl = "ytm:${pl.browseId}",
                fixedId = "ytm_${pl.browseId.hashCode()}"
            )
            synced++
        }
    }
    AccountSyncManager.markSynced()
    return synced
}
