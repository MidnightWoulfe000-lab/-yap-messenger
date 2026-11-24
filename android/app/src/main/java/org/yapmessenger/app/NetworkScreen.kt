package org.yapmessenger.app

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun NetworkScreen(session: ChatSession, preview: Boolean, reduceMotion: Boolean, manage: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Network", style = MaterialTheme.typography.headlineLarge, color = White)
            Text(if (preview) "Sample network" else "Live Bluetooth", color = Soft, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(Surface, CircleShape).padding(horizontal = 10.dp, vertical = 7.dp))
        }
        Text("How your devices connect", color = Soft, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        NetworkVisualisation(preview, session.peer, reduceMotion)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ConnectionLegend(if (preview) "Local mesh" else "Local Bluetooth", Lime, false, Modifier.weight(1f))
            ConnectionLegend(if (preview) "Internet" else "Internet · planned", Blue, true, Modifier.weight(1f))
        }
        Text("Connections, not physical positions", style = MaterialTheme.typography.labelMedium, color = Soft,
            modifier = Modifier.padding(top = 10.dp, bottom = 20.dp))
        if (preview) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DetailRow("Ravi", "Direct · Local mesh", Icons.Default.PhoneAndroid)
                DetailRow("Priya", "Via Ravi · Local mesh", Icons.Default.PhoneAndroid)
                DetailRow("Maya", "Internet", Icons.Default.PhoneAndroid)
            }
        } else {
            if (session.peer != PeerObservation.NONE) DetailRow("Nearby Yap phone", when (session.peer) {
                PeerObservation.CONNECTED -> "Connected · Direct Bluetooth"
                PeerObservation.CONNECTING -> "Connecting · Identity not checked"
                else -> "Seen this session · Identity not checked"
            }, Icons.Default.PhoneAndroid)
            else InfoNote("No peer observed in this session. Send a message to look for a nearby Yap phone.")
            Spacer(Modifier.height(12.dp))
            DetailRow(if (session.advertising) "Visible to nearby phones" else "Make this phone visible",
                if (session.advertising) "Keep Yap open to receive messages" else "Manage Bluetooth discoverability", Icons.Default.WifiTethering, manage)
        }
        TextButton(onClick = manage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Manage connections") }
        if (!preview) Text("Only direct Bluetooth is available in this prototype. Mesh forwarding and internet links are planned.",
            color = Soft, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
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
private fun NetworkVisualisation(preview: Boolean, peer: PeerObservation, reduceMotion: Boolean) {
    val motion = systemMotionEnabled() && !reduceMotion
    val phase = if (motion) {
        val transition = rememberInfiniteTransition(label = "Network fabric")
        transition.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(24000, easing = LinearEasing)), label = "Fabric phase").value
    } else 0f
    val nodes = if (preview) listOf(
        VisualNode("You", "This phone", .38f, .52f, true),
        VisualNode("Ravi", "Local mesh", .77f, .23f),
        VisualNode("Priya", "Via Ravi", .82f, .72f),
        VisualNode("Maya", "Internet", .13f, .20f)
    ) else listOf(VisualNode("You", "This phone", .42f, .58f, true)) +
        if (peer != PeerObservation.NONE) listOf(VisualNode("Nearby phone", if (peer == PeerObservation.CONNECTED) "Connected" else "Observed", .78f, .23f)) else emptyList()
    val showLabels = LocalDensity.current.fontScale <= 1.35f
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 270.dp).aspectRatio(1.12f)) {
        val w = maxWidth
        val h = maxHeight
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val phaseNow = phase
            // Project a gently undulating fabric into the canvas. It encodes no peer count or range.
            for (row in 0..24) for (column in 0..46) {
                val u = column / 46f * 2f - 1f
                val v = row / 24f * 2f - 1f
                val wave = sin(u * 3.8f + v * 2.4f + phaseNow) * .085f
                val x = size.width * (.5f + u * .47f + sin(v * 2f) * .055f)
                val y = size.height * (.49f + v * .31f + wave + u * .055f)
                val rim = (1f - abs(v) * .5f) * (1f - abs(u) * .3f)
                val limeBand = abs(sin(u * 2.5f + v * 2f + phaseNow * .4f)) > .965f
                drawCircle((if (limeBand) Lime else Blue).copy(alpha = if (limeBand) .22f * rim else .28f * rim),
                    radius = if (limeBand) .8.dp.toPx() else .6.dp.toPx(), center = Offset(x, y))
            }
            fun point(index: Int) = Offset(size.width * nodes[index].x, size.height * nodes[index].y)
            fun link(a: Int, b: Int, internet: Boolean = false) {
                connectionLine(if (internet) Blue else Lime, point(a), point(b), internet)
            }
            if (preview) { link(0, 1); link(1, 2); link(0, 3, true) }
            else if (peer == PeerObservation.CONNECTED) link(0, 1)
            nodes.forEach { node ->
                val center = Offset(size.width * node.x, size.height * node.y)
                val colour = if (node.local) Lime else Blue
                drawCircle(colour.copy(alpha = .035f), 36.dp.toPx(), center)
                drawCircle(colour.copy(alpha = .07f), 28.dp.toPx(), center)
                drawCircle(colour.copy(alpha = .7f), 20.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            }
        }
        nodes.forEach { node ->
            Column(Modifier.offset(x = w * node.x - 50.dp, y = h * node.y - 18.dp).width(100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(36.dp).background(Ink, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PhoneAndroid, null, tint = if (node.local) Lime else White, modifier = Modifier.size(23.dp))
                }
                if (showLabels) {
                    Text(node.name, color = White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 5.dp))
                    Text(node.subtitle, color = if (node.local) Ink else Soft, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.background(if (node.local) Lime else Surface, CircleShape).padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
        }
        if (preview) Icon(Icons.Default.Language, "Internet path", tint = Blue,
            modifier = Modifier.offset(x = w * .23f, y = h * .31f).size(22.dp).background(Ink, CircleShape))
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
