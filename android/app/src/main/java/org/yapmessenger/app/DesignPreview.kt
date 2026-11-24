package org.yapmessenger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId

/** Explicitly labelled, read-only fixtures. Never passed to the Bluetooth transport. */
internal val previewChats = listOf(
    ChatSummary("ravi", "Ravi", "You: Meeting at the café?", "2:28 PM"),
    ChatSummary("anfield", "Anfield Crew", "Priya: See you there.", "2:14 PM", 3, true),
    ChatSummary("maya", "Maya", "File: route_map.jpg", "1:03 PM"),
    ChatSummary("design", "Design Circle", "Arjun: New mockups are in", "12:11 PM", group = true),
    ChatSummary("updates", "Yap updates", "What's new in Yap", "11:47 AM")
)

private fun sampleTime(hour: Int, minute: Int) = LocalDate.now().atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

internal val previewMessages = listOf(
    ChatMessage(false, "Are you still around Anfield?", id = 101, createdAt = sampleTime(14, 20)),
    ChatMessage(true, "Yeah, just left the shop.", id = 102, createdAt = sampleTime(14, 23)),
    ChatMessage(false, "Nice. Can you send the notes?", id = 103, createdAt = sampleTime(14, 24)),
    ChatMessage(true, "meeting_notes.pdf", "Delivered", id = 104, createdAt = sampleTime(14, 25),
        acknowledgedAt = sampleTime(14, 26), fileName = "meeting_notes.pdf", fileSize = "2.4 MB", recipientReceiptAt = sampleTime(14, 28)),
    ChatMessage(true, "Meeting at the café?", id = 105, createdAt = sampleTime(14, 28))
)

internal fun previewConversation(chat: String): List<ChatMessage> = when (chat) {
    "anfield" -> listOf(ChatMessage(false, "See you there.", id = 201, createdAt = sampleTime(14, 14)))
    "design" -> listOf(ChatMessage(false, "New mockups are in", id = 202, createdAt = sampleTime(12, 11)))
    "maya" -> listOf(ChatMessage(false, "route_map.jpg", id = 203, createdAt = sampleTime(13, 3), fileName = "route_map.jpg", fileSize = "Sample attachment"))
    "updates" -> listOf(ChatMessage(false, "Explore the design preview. These are sample messages, not live network updates.", id = 204, createdAt = sampleTime(11, 47)))
    else -> previewMessages
}

@Composable
internal fun GroupDetailsScreen(chat: ChatSummary, back: () -> Unit, message: () -> Unit) {
    var dialog by remember { mutableStateOf("") }
    val members = listOf("Ravi", "Maya", "Priya", "Arjun", "Sam", "Alex")
    Column(Modifier.fillMaxSize()) {
        PageHeader("Group details", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            YapAvatar(group = true, size = 76)
            Spacer(Modifier.height(16.dp))
            Text(chat.name, style = MaterialTheme.typography.headlineMedium, color = White)
            Text("6 members · Sample group", color = Soft, modifier = Modifier.padding(top = 5.dp))
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = message, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Message") }
                FilledTonalButton(onClick = { dialog = "Members" },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Surface, contentColor = White),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Members") }
                FilledTonalIconButton(onClick = { dialog = "About this preview" },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Surface, contentColor = White),
                    modifier = Modifier.size(56.dp)) { Icon(Icons.Default.MoreHoriz, "More group options") }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Members", style = MaterialTheme.typography.titleMedium, color = White, modifier = Modifier.weight(1f))
                TextButton(onClick = { dialog = "Members" }) { Text("View all 6") }
            }
            Column(Modifier.background(Surface, MaterialTheme.shapes.large).padding(14.dp)) {
                members.take(4).forEachIndexed { index, name ->
                    if (index > 0) HorizontalDivider(color = Surface2, modifier = Modifier.padding(vertical = 10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        YapAvatar(name, size = 36)
                        Text(name, color = White, modifier = Modifier.weight(1f).padding(start = 14.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            DetailRow("Membership requests", "Review invitations and requests", Icons.Default.GroupAdd) { dialog = "Membership requests" }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (dialog.isNotEmpty()) AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(dialog) },
        text = { Text(if (dialog == "Members") members.joinToString("\n") else
            "This is a read-only design preview. Group messaging and membership requests are planned; no invitations are sent here.") },
        confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } })
}
