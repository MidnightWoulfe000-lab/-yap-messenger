package org.yapmessenger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(profile: ProfileStore, openProfile: () -> Unit, open: (String) -> Unit, preview: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, color = White)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface).clickable(onClickLabel = "Edit profile", onClick = openProfile)
            .heightIn(min = 80.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            YapAvatar(profile.name, size = 52, tint = avatarColour(profile.colour))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name.ifBlank { "Set up your profile" }, color = White, style = MaterialTheme.typography.titleMedium)
                Text("Your name and avatar", color = Soft, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Soft)
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Surface)
            .border(1.dp, Surface2.copy(alpha = .6f), RoundedCornerShape(20.dp))) {
            listOf(
                Triple("Network & connections", "Nearby messaging and internet relay", Icons.Outlined.DeviceHub),
                Triple("Privacy & identity", "Your fingerprint and contacts", Icons.Outlined.Lock),
                Triple("Notifications", "Messages and groups", Icons.Outlined.NotificationsNone),
                Triple("Appearance", "Theme, text and motion", Icons.Outlined.Palette),
                Triple("Data & storage", "History and storage", Icons.Outlined.Storage),
                Triple("About Yap", "Version 0.2.0-debug", Icons.Outlined.Info)
            ).forEachIndexed { index, (title, subtitle, icon) ->
                if (index > 0) HorizontalDivider(color = Surface2.copy(alpha = .6f), modifier = Modifier.padding(start = 56.dp, end = 16.dp))
                SettingsRow(title, subtitle, icon) { open(title) }
            }
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = preview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Visibility, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Preview the full design")
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, icon: ImageVector, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open).heightIn(min = 72.dp)
        .padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Soft, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Soft, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Default.ChevronRight, null, tint = Soft, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun ConnectionScreen(session: ChatSession, preview: Boolean, discover: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("Connections", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            DetailRow("Direct Bluetooth", if (preview) "Sample connection · Preview only" else if (session.advertising) "Visible to nearby phones" else "Foreground messaging", Icons.Default.Bluetooth)
            Spacer(Modifier.height(16.dp))
            if (!preview) {
                Text(session.status, color = Soft, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = discover, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Make this phone discoverable") }
            }
            Spacer(Modifier.height(16.dp))
            InfoNote("Keep Yap open on both phones. Messages are limited to 20 UTF-8 bytes and are not encrypted or saved.", accent = Coral)
            Spacer(Modifier.height(20.dp))
            DetailRow("Local mesh", "Forwarding through other devices · Planned", Icons.Default.Hub)
            Spacer(Modifier.height(6.dp))
            DetailRow("Internet", "Internet transport · Planned", Icons.Default.Language)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun PreferenceScreen(page: String, session: ChatSession, preview: Boolean, reduceMotion: Boolean, onReduceMotion: (Boolean) -> Unit,
    back: () -> Unit, network: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(page, back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (page) {
                "Network & connections" -> {
                    DetailRow("Bluetooth controls", "Discoverability and connection status", Icons.Default.Bluetooth, network)
                    InfoNote("The current prototype connects directly to a nearby phone. Mesh forwarding and internet links are not implemented.")
                }
                "Privacy & identity" -> {
                    InfoNote("Encryption and verified identities are not available in this prototype.", accent = Coral)
                    Text("Nearby discovery does not verify who owns a phone. Do not share private information in this test chat.", color = Soft)
                }
                "Notifications" -> InfoNote("Incoming messages appear while Yap is open. Background message notifications are not available in this prototype.")
                "Appearance" -> {
                    Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Reduce motion", style = MaterialTheme.typography.titleMedium, color = White)
                            Text("Keep the network fabric still", style = MaterialTheme.typography.bodySmall, color = Soft)
                        }
                        Switch(checked = reduceMotion, onCheckedChange = onReduceMotion,
                            modifier = Modifier.semantics { contentDescription = "Reduce motion" })
                    }
                    InfoNote("Yap also honours Android's Remove animations setting. Text follows your system font size.")
                }
                "Data & storage" -> {
                    InfoNote("No saved history. Messages stay in memory for this app session, including screen rotation. They may disappear when Android closes the app.")
                    OutlinedButton(onClick = { confirmClear = true }, enabled = !preview && session.pendingText == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Clear this session") }
                    if (session.pendingText != null) Text("Wait for the current send to finish before clearing the session.", color = Soft)
                    if (preview) Text("Live history cannot be cleared from the design preview.", color = Soft)
                }
                else -> {
                    Wordmark()
                    Text("Nobody gets to silence you.", color = Lime, style = MaterialTheme.typography.titleLarge)
                    Text("Communication is a right, not a privilege.", color = White)
                    InfoNote("Free, open source and offline first. GPLv3. This is an experimental Bluetooth prototype, not a secure messenger.")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear this session?") },
        text = { Text("Remove this phone's in-memory message history. This does not delete messages from another phone.") },
        confirmButton = { TextButton(onClick = { session.clearHistory(); confirmClear = false }) { Text("Clear session") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}
