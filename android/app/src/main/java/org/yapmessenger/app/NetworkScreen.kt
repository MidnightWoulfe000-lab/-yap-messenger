package org.yapmessenger.app

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable
internal fun NetworkScreen(session: ChatSession, preview: Boolean, reduceMotion: Boolean, manage: () -> Unit,
    meshPeers: Int = 0, internet: Boolean = false, meshVisible: Boolean = false) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Network", style = MaterialTheme.typography.headlineLarge, color = White, modifier = Modifier.weight(1f))
            IconButton(onClick = manage) { Icon(Icons.Outlined.Tune, "Network details", tint = Soft) }
        }
        Text(if (preview) "3 devices" else if (meshPeers > 0 || internet) "$meshPeers local link(s)${if (internet) " · Internet connected" else ""}" else if (session.peer != PeerObservation.NONE) "1 device seen" else "Your local network",
            color = Soft, style = MaterialTheme.typography.bodyMedium)
        NetworkVisualisation(preview, session.peer, reduceMotion, meshPeers, internet)
        if (preview || session.peer == PeerObservation.CONNECTED || meshPeers > 0 || internet) Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.Center) {
            if (preview || meshPeers > 0 || session.peer == PeerObservation.CONNECTED) ConnectionLegend(if (preview || meshPeers > 0) "Local mesh" else "Bluetooth", Lime, false, Modifier.padding(end = 28.dp))
            if (preview || internet) ConnectionLegend("Internet", Blue, true, Modifier)
        }
        if (preview) {
            NetworkPanel {
                NetworkPeerRow("Ravi", "Direct · Local mesh")
                HorizontalDivider(color = Surface2, modifier = Modifier.padding(horizontal = 16.dp))
                NetworkPeerRow("Priya", "Via Ravi · Local mesh")
                HorizontalDivider(color = Surface2, modifier = Modifier.padding(horizontal = 16.dp))
                NetworkPeerRow("Maya", "Internet")
            }
        } else {
            if (meshPeers > 0 || internet) NetworkPanel {
                repeat(meshPeers) { index -> NetworkPeerRow("Nearby mesh node ${index + 1}", "Connected · local Bluetooth link") }
                if (internet) NetworkPeerRow("Configured relay", "Connected · TLS")
            }
            if (meshPeers > 3) Text("Map shows three of $meshPeers active local links", color = Soft, style = MaterialTheme.typography.bodySmall)
            if (session.peer != PeerObservation.NONE) NetworkPanel {
                NetworkPeerRow("Nearby Yap phone", when (session.peer) {
                    PeerObservation.CONNECTED -> "Connected · Bluetooth"
                    PeerObservation.CONNECTING -> "Connecting…"
                    else -> "Seen recently"
                })
            }
            else if (meshPeers == 0 && !internet) Text("No devices nearby yet", color = Soft, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface)
                .clickable(onClick = manage).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WifiTethering, null, tint = if (session.advertising || meshVisible) Lime else Soft, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(if (session.advertising || meshVisible) "You're visible" else "Make yourself visible", color = White, style = MaterialTheme.typography.titleMedium)
                    Text(if (session.advertising || meshVisible) "Nearby phones can find you" else "Let nearby phones find you", color = Soft, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Soft, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun NetworkPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface)
        .border(1.dp, Surface2.copy(alpha = .6f), RoundedCornerShape(18.dp)), content = content)
}

@Composable
private fun NetworkPeerRow(name: String, connection: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.PhoneAndroid, null, tint = Soft, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(name, color = White, style = MaterialTheme.typography.titleMedium)
            Text(connection, color = Soft, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConnectionLegend(label: String, colour: Color, dashed: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 28.dp, height = 18.dp).clearAndSetSemantics {}) {
            connectionLine(colour, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), dashed)
        }
        Text(label, color = Soft, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

private data class VisualNode(val name: String, val subtitle: String, val x: Float, val y: Float, val local: Boolean = false)

/** Decorative fabric and observed/sample edges are deliberately distinct layers. */
@Composable
private fun NetworkVisualisation(preview: Boolean, peer: PeerObservation, reduceMotion: Boolean, meshPeers: Int, internet: Boolean) {
    val motion = systemMotionEnabled() && !reduceMotion
    val phase = if (motion) {
        val transition = rememberInfiniteTransition(label = "Network fabric")
        transition.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(24000, easing = LinearEasing)), label = "Fabric phase").value
    } else 0f
    val nodes = if (preview) listOf(
        VisualNode("You", "", .5f, .5f, true),
        VisualNode("Ravi", "Local mesh", .8f, .18f),
        VisualNode("Priya", "Via Ravi", .8f, .78f),
        VisualNode("Maya", "Internet", .18f, .23f)
    ) else if (meshPeers > 0 || internet) listOf(VisualNode("You", "", .5f, .5f, true)) +
        listOf(VisualNode("Node 1", "Local link", .8f, .18f), VisualNode("Node 2", "Local link", .8f, .78f), VisualNode("Node 3", "Local link", .2f, .78f)).take(meshPeers.coerceAtMost(3)) +
        if (internet) listOf(VisualNode("Relay", "Internet", .18f, .23f)) else emptyList()
    else listOf(VisualNode("You", "", .5f, .5f, true)) +
        if (peer != PeerObservation.NONE) listOf(VisualNode("Nearby phone", if (peer == PeerObservation.CONNECTED) "Connected" else if (peer == PeerObservation.CONNECTING) "Connecting…" else "Seen recently", .79f, .2f)) else emptyList()
    val showLabels = LocalDensity.current.fontScale <= 1.35f
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).testTag("network-map")) {
        val w = maxWidth
        val h = maxHeight
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val phaseNow = phase
            // A continuous folded fabric, softly fading into the same surface as the UI.
            // This is atmosphere, never a graph of additional devices or measured distance.
            for (row in 0..52) for (column in 0..72) {
                val u = column / 72f * 2.3f - 1.15f
                val v = row / 52f * 2.3f - 1.15f
                val r = sqrt(u * u + v * v)
                if (r > 1.15f) continue
                val wave = sin(u * 3.6f + v * 1.6f + phaseNow) * .09f
                val x = size.width * (.5f + u * .46f + sin(v * 2f) * .025f)
                val y = size.height * (.49f + v * .35f + wave)
                val fade = (1f - r / 1.15f).pow(.55f)
                val band = exp(-((v - sin(u * 3f + phaseNow * .5f) * .35f).pow(2)) * 28f)
                val colour = androidx.compose.ui.graphics.lerp(Blue, Lime, band * .8f)
                drawCircle(colour.copy(alpha = (.38f + band * .24f) * fade),
                    radius = (.55f + band * .2f).dp.toPx(), center = Offset(x, y))
            }
            fun point(index: Int) = Offset(size.width * nodes[index].x, size.height * nodes[index].y)
            fun link(a: Int, b: Int, internet: Boolean = false) {
                connectionLine(if (internet) Blue else Lime, point(a), point(b), internet)
            }
            if (preview) {
                link(0, 1)
                // Route the relay around the labels instead of drawing through them.
                val from = point(1) + Offset(22.dp.toPx(), 0f)
                val to = point(2) + Offset(22.dp.toPx(), 0f)
                val relay = Path().apply {
                    moveTo(from.x, from.y)
                    cubicTo(size.width * 1.03f, from.y + size.height * .12f,
                        size.width * 1.03f, to.y - size.height * .12f, to.x, to.y)
                }
                drawPath(relay, Lime, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                link(0, 3, true)
            }
            else if (meshPeers > 0 || internet) {
                for (index in 1..meshPeers.coerceAtMost(3)) link(0, index)
                if (internet) link(0, nodes.lastIndex, true)
            } else if (peer == PeerObservation.CONNECTED) link(0, 1)
            nodes.forEach { node ->
                val center = Offset(size.width * node.x, size.height * node.y)
                val colour = if (node.local) Lime else Blue
                val radius = (if (node.local) 27 else 22).dp.toPx()
                drawCircle(Brush.radialGradient(listOf(colour.copy(alpha = .23f), Color.Transparent), center, radius * 2.2f), radius * 2.2f, center)
                drawCircle(Ink, radius, center)
                drawCircle(colour.copy(alpha = if (node.local) .9f else .65f), radius, center, style = Stroke(1.dp.toPx()))
            }
        }
        nodes.forEach { node ->
            val diameter = if (node.local) 52.dp else 42.dp
            Column(Modifier.offset(x = w * node.x - 58.dp, y = h * node.y - diameter / 2).width(116.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(diameter).then(if (node.local) Modifier.testTag("network-self") else Modifier), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PhoneAndroid, if (node.local) "Your phone" else null, tint = if (node.local) Lime else White, modifier = Modifier.size(if (node.local) 27.dp else 22.dp))
                }
                if (showLabels) {
                    Text(node.name, color = if (node.local) Lime else White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 7.dp))
                    if (node.subtitle.isNotEmpty()) Text(node.subtitle, color = Soft, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Explicit segments keep the internet pattern consistent on older hardware renderers. */
private fun DrawScope.connectionLine(colour: Color, start: Offset, end: Offset, dashed: Boolean) {
    if (!dashed) {
        drawLine(colour, start, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        return
    }
    val delta = end - start
    val distance = delta.getDistance()
    if (distance <= 0f) return
    val direction = delta / distance
    var position = 0f
    val dash = 5.dp.toPx()
    val gap = 4.dp.toPx()
    while (position < distance) {
        drawLine(colour, start + direction * position, start + direction * min(position + dash, distance),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Butt)
        position += dash + gap
    }
}

@Composable
private fun systemMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun enabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var enabled by remember(resolver) { mutableStateOf(enabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = enabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}
