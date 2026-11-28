package org.yapmessenger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun messageTime(value: Long, zone: ZoneId = ZoneId.systemDefault()): String = if (value == 0L) "" else
    DateTimeFormatter.ofPattern("h:mm a", Locale.UK).withZone(zone).format(Instant.ofEpochMilli(value))

/** Raised, softly lit surface used for cards and bubbles. */
internal fun Modifier.yapSurface(shape: Shape): Modifier = this.clip(shape)
    .background(Brush.verticalGradient(listOf(Color(0xFF151A26), Color(0xFF10141E))), shape)
    .border(1.dp, Surface2, shape)

internal fun Modifier.yapCard(radius: Dp = 18.dp): Modifier = yapSurface(RoundedCornerShape(radius))

internal val LimeBrush = Brush.linearGradient(listOf(Color(0xFFD8FF63), Color(0xFFB6F21F)))

/** The Yap logo. Every screen uses this one composable, so a new logo is a single file swap. */
@Composable
internal fun Wordmark(height: Dp = 64.dp) {
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.yap_logo), contentDescription = "Yap",
        modifier = Modifier.height(height), contentScale = androidx.compose.ui.layout.ContentScale.Fit, alignment = Alignment.CenterStart)
}

@Composable
internal fun YapAvatar(name: String = "", group: Boolean = false, size: Int = 52, tint: Color? = null, shape: Shape = CircleShape) {
    val accent = tint ?: if (group) Lime else if (name.startsWith("M") || name.startsWith("P")) Coral else Blue
    val onAccent = if (accent.luminance() > .45f) Ink else White
    Box(Modifier.size(size.dp).clip(shape).background(Brush.linearGradient(listOf(lerp(accent, Color.White, .3f), accent, lerp(accent, Color.Black, .25f))), shape),
        contentAlignment = Alignment.Center) {
        if (group || name.isEmpty()) Icon(if (group) Icons.Outlined.Groups else Icons.Outlined.PhoneAndroid,
            null, tint = onAccent, modifier = Modifier.size((size * .5f).dp))
        else Text(name.take(1).uppercase(), fontSize = (size * .4f).sp, fontWeight = FontWeight.Bold, color = onAccent)
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
    Row(modifier.fillMaxWidth().yapCard(14.dp).padding(14.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Info, null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Soft)
    }
}

@Composable
internal fun DetailRow(title: String, subtitle: String, icon: ImageVector, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().yapCard(16.dp)
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
internal fun LimeAction(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LimeBrush).clickable(role = Role.Button, onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).background(Ink, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Lime, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = .8f))
        }
        Icon(Icons.Default.ChevronRight, null, tint = Ink)
    }
}

@Composable
internal fun YapNavigation(screen: String, navigate: (String) -> Unit) {
    Column {
        HorizontalDivider(color = Surface2)
        Row(Modifier.fillMaxWidth().background(Color(0xFF0A0D15))) {
            listOf(Triple("chats", "Chats", Icons.Outlined.ChatBubbleOutline), Triple("network", "Network", Icons.Outlined.DeviceHub),
                Triple("settings", "Settings", Icons.Outlined.Settings)).forEach { (key, title, icon) ->
                val selected = key == screen
                Column(Modifier.weight(1f).heightIn(min = 64.dp).selectable(selected, role = Role.Tab, onClick = { navigate(key) }),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(36.dp).height(3.dp).background(if (selected) Lime else Color.Transparent, RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)))
                    Spacer(Modifier.height(8.dp))
                    Icon(icon, null, tint = if (selected) Lime else Soft, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(title, style = MaterialTheme.typography.labelMedium, color = if (selected) Lime else Soft)
                }
            }
        }
    }
}
