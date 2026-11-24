package org.yapmessenger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun messageTime(value: Long): String = if (value == 0L) "" else
    DateTimeFormatter.ofPattern("h:mm a", Locale.UK).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

@Composable
internal fun Wordmark() {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("yap", fontSize = 46.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp, color = White)
        Text(".", fontSize = 46.sp, fontWeight = FontWeight.Black, color = Lime)
    }
}

@Composable
internal fun YapAvatar(name: String = "", group: Boolean = false, size: Int = 52) {
    Box(Modifier.size(size.dp).background(if (group) Lime else Blue, CircleShape), contentAlignment = Alignment.Center) {
        Icon(if (group) Icons.Default.Groups else if (name.isEmpty()) Icons.Default.Bluetooth else Icons.Default.Person,
            null, tint = if (group) Ink else White, modifier = Modifier.size((size * .58f).dp))
    }
}

@Composable
internal fun PageHeader(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = White) }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = White)
    }
}

@Composable
internal fun InfoNote(text: String, modifier: Modifier = Modifier, accent: Color = Soft) {
    Row(modifier.fillMaxWidth().background(Surface, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Info, null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Soft)
    }
}

@Composable
internal fun DetailRow(title: String, subtitle: String, icon: ImageVector, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = White, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = White)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Soft)
        }
        if (onClick != null) { Spacer(Modifier.width(8.dp)); Icon(Icons.Default.ChevronRight, null, tint = Soft) }
    }
}

@Composable
internal fun YapNavigation(screen: String, navigate: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Surface.copy(alpha = .45f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        listOf(Triple("chats", "Chats", Icons.Default.ChatBubbleOutline), Triple("network", "Network", Icons.Default.Hub),
            Triple("settings", "Settings", Icons.Default.Settings)).forEach { (key, title, icon) ->
            val selected = key == screen
            Column(Modifier.weight(1f).heightIn(min = 56.dp).selectable(selected, role = Role.Tab, onClick = { navigate(key) })
                .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 48.dp, height = 28.dp).background(if (selected) Lime else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = if (selected) Ink else Soft, modifier = Modifier.size(23.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, color = if (selected) Lime else Soft)
            }
        }
    }
}
