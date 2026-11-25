package org.yapmessenger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
internal fun MessageDetailsScreen(message: ChatMessage, preview: Boolean, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("Message details", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp)) {
            // A soft outer field and a precise inner stroke carry the approved framed timeline.
            Box(Modifier.fillMaxWidth().background(Brush.radialGradient(listOf(Lime.copy(alpha = .07f), Color.Transparent)), RoundedCornerShape(26.dp)).padding(5.dp)) {
                Column(Modifier.fillMaxWidth().drawBehind {
                    listOf(12f to .025f, 8f to .045f, 4f to .08f).forEach { (width, alpha) ->
                        drawRoundRect(Lime.copy(alpha = alpha), cornerRadius = CornerRadius(22.dp.toPx()), style = Stroke(width.dp.toPx()))
                    }
                }.border(1.dp, Lime.copy(alpha = .9f), RoundedCornerShape(22.dp))
                    .background(Ink, RoundedCornerShape(22.dp)).padding(16.dp)) {
                    DetailRow(message.fileName ?: "Text message", if (message.fileName != null) "${message.fileSize} · Sent at ${messageTime(message.createdAt)}"
                        else message.body, if (message.fileName != null) Icons.Default.Description else Icons.Default.ChatBubbleOutline)
                    Spacer(Modifier.height(24.dp))
                    if (message.mine) {
                        TimelineStep(if (preview || message.secure) "Queued" else "Send started", if (message.secure) "Ciphertext saved on this phone" else if (preview) "Waiting for a nearby peer" else "Looking for a nearby Yap phone",
                            messageTime(message.createdAt), Icons.Default.Check, complete = true, colour = Lime)
                        TimelineStep(if (message.secure) "Relayed" else if (preview) "Sent to peer" else "Bluetooth write", when {
                            message.acknowledgedAt != null -> if (message.secure) "Durable custody acknowledged by a peer or relay" else if (preview) "Handed to a nearby device" else "Acknowledged by the Bluetooth peer"
                            message.failureReason != null -> message.failureReason
                            else -> if (message.secure) "Waiting for a connection or custody acknowledgement" else "Waiting for Bluetooth confirmation"
                        }, message.acknowledgedAt?.let(::messageTime).orEmpty(), Icons.Default.Hub,
                            complete = message.acknowledgedAt != null, colour = if (message.failureReason != null) Coral else Blue)
                        TimelineStep("Delivered", if (message.recipientReceiptAt != null) "Recipient acknowledged receipt" else "Recipient receipt not confirmed",
                            message.recipientReceiptAt?.let(::messageTime).orEmpty(), Icons.Default.Check,
                            complete = message.recipientReceiptAt != null, colour = Lime, last = true)
                        InfoNote(if (message.recipientReceiptAt != null)
                            "Delivery confirms recipient receipt. It does not confirm the message was read."
                            else if (message.secure) "Relay custody does not confirm recipient delivery or reading." else "A Bluetooth acknowledgement does not confirm recipient delivery or reading.")
                    } else {
                        TimelineStep("Received on this phone", if (message.secure) "Authenticated and saved from a paired key" else "Received over Bluetooth · Identity not checked", messageTime(message.createdAt),
                            Icons.Default.Bluetooth, complete = true, colour = Lime, last = true)
                        InfoNote(if (message.secure) "A device receipt was queued after durable acceptance. Reading is not reported." else "This records reception on this phone only. No read receipt was sent.")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TimelineStep(title: String, description: String, time: String, icon: ImageVector, complete: Boolean, colour: Color, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(38.dp).border(1.5.dp, if (complete) colour else Soft, CircleShape)
                .background(if (complete && colour == Lime) Lime else Surface, CircleShape), contentAlignment = Alignment.Center) {
                if (complete) Icon(icon, null, tint = if (colour == Lime) Ink else White, modifier = Modifier.size(23.dp))
            }
            if (!last) Box(Modifier.width(1.5.dp).weight(1f).heightIn(min = 20.dp).background(Blue.copy(alpha = .65f)))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, bottom = 26.dp)) {
            Text(title, color = White, style = MaterialTheme.typography.titleMedium)
            Text(description, color = Soft, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
            if (time.isNotEmpty()) Text(time, color = Soft, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
internal fun MissingMessageScreen(back: () -> Unit) {
    Column { PageHeader("Message details", back); InfoNote("This message is no longer in this session.", Modifier.padding(24.dp)) }
}
