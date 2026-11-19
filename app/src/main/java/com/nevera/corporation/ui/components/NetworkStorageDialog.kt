package com.nevera.corporation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nevera.corporation.data.model.NetworkServerConfig
import com.nevera.corporation.data.model.NetworkServerType
import com.nevera.corporation.data.store.NetworkStorageRepository
import com.nevera.corporation.ui.theme.NeveraTextSecondary
import kotlinx.coroutines.launch

/**
 * Dialog for configuring & testing personal Lossless Servers (Subsonic / OpenSubsonic / WebDAV / HTTP).
 */
@Composable
fun NetworkStorageManagerDialog(
    accentColor: Color,
    onDismiss: () -> Unit
) {
    val servers by NetworkStorageRepository.servers.collectAsState()
    var showAddForm by remember { mutableStateOf(servers.isEmpty()) }

    if (showAddForm) {
        AddNetworkServerDialog(
            accentColor = accentColor,
            onDismiss = {
                if (servers.isEmpty()) onDismiss() else showAddForm = false
            },
            onSaved = { showAddForm = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161C),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Lossless Servers", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                IconButton(onClick = { showAddForm = true }) {
                    Icon(Icons.Rounded.Add, "Add Server", tint = accentColor)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                servers.forEach { srv ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1F1F27))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Dns, null, tint = accentColor, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(srv.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("${srv.type.label} • ${srv.baseUrl}", color = NeveraTextSecondary, fontSize = 11.sp, maxLines = 1)
                        }
                        IconButton(onClick = { NetworkStorageRepository.removeServer(srv.id) }) {
                            Icon(Icons.Rounded.DeleteOutline, "Remove", tint = Color(0xFFFA2D48), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = Color.White) }
        }
    )
}

@Composable
private fun AddNetworkServerDialog(
    accentColor: Color,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var serverType by remember { mutableStateOf(NetworkServerType.SUBSONIC) }
    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161C),
        title = { Text("Add Lossless Server", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NetworkServerType.values().forEach { t ->
                        val active = serverType == t
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) accentColor else Color(0xFF23232C),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { serverType = t; testResult = null }
                        ) {
                            Text(
                                text = if (t == NetworkServerType.SUBSONIC) "Subsonic / Navidrome" else "WebDAV / HTTP",
                                color = if (active) Color.Black else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server Name") },
                    placeholder = { Text("Home NAS / Navidrome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; testResult = null },
                    label = { Text("Server URL") },
                    placeholder = {
                        Text(
                            if (serverType == NetworkServerType.SUBSONIC) "https://music.example.com"
                            else "https://nas.local:6008/dav/Music"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (optional for HTTP)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        enabled = baseUrl.isNotBlank() && !testing,
                        onClick = {
                            testing = true
                            testResult = null
                            val cfg = NetworkServerConfig(
                                id = "test",
                                name = name,
                                type = serverType,
                                baseUrl = baseUrl,
                                username = username,
                                password = password
                            )
                            scope.launch {
                                testResult = NetworkStorageRepository.testConnection(cfg)
                                testing = false
                            }
                        }
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Text("Test", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    testResult?.let { (ok, msg) ->
                        Text(
                            text = msg,
                            color = if (ok) Color(0xFF34D399) else Color(0xFFFA2D48),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = baseUrl.isNotBlank(),
                onClick = {
                    val cfg = NetworkServerConfig(
                        id = "srv_${System.currentTimeMillis()}",
                        name = name.ifBlank { if (serverType == NetworkServerType.SUBSONIC) "Subsonic Server" else "WebDAV Music" },
                        type = serverType,
                        baseUrl = baseUrl.trim(),
                        username = username.trim(),
                        password = password
                    )
                    NetworkStorageRepository.addOrUpdateServer(cfg)
                    onSaved()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.7f)) }
        }
    )
}
