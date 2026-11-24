package org.yapmessenger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class ChatSummary(val id: String, val name: String, val snippet: String, val time: String = "", val unread: Int = 0, val group: Boolean = false)

internal fun liveChat(session: ChatSession): ChatSummary {
    val latest = (session.messages.toList() + listOfNotNull(session.lastAttempt)).maxByOrNull { it.id }
    return ChatSummary("nearby", "Nearby Yap phone", latest?.body ?: "Start a Bluetooth conversation",
        latest?.let { messageTime(it.createdAt) } ?: "", session.unreadCount)
}

@Composable
internal fun ChatsScreen(chats: List<ChatSummary>, preview: Boolean, open: (String) -> Unit, newChat: () -> Unit) {
    var filter by rememberSaveable(preview) { mutableStateOf("All") }
    var search by rememberSaveable(preview) { mutableStateOf(false) }
    var query by rememberSaveable(preview) { mutableStateOf("") }
    val visible = chats.filter { (filter != "Groups" || it.group) && (filter != "Unread" || it.unread > 0) &&
        (query.isBlank() || it.name.contains(query, true) || it.snippet.contains(query, true)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Wordmark() }
                IconButton(onClick = { search = !search; if (!search) query = "" }) { Icon(Icons.Default.Search, "Search chats", tint = White) }
                IconButton(onClick = newChat) { Icon(Icons.Default.Add, "New chat", tint = White) }
            }
            if (search) OutlinedTextField(query, { query = it }, label = { Text("Search chats") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("All", "Groups", "Unread").forEach { label ->
                    FilterChip(selected = filter == label, onClick = { filter = label },
                        shape = CircleShape,
                        border = null,
                        label = { Text(if (label == "Unread" && chats.sumOf { it.unread } > 0) "Unread ${chats.sumOf { it.unread }}" else label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lime, selectedLabelColor = Ink),
                        modifier = Modifier.widthIn(min = 72.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Surface2)
            if (!preview) {
                Text("Bluetooth prototype · Not encrypted", color = Soft, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
        }
        items(visible, key = { it.id }) { chat ->
            Row(Modifier.fillMaxWidth().clickable { open(chat.id) }.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                YapAvatar(chat.name.takeIf { preview } ?: "", chat.group)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(chat.name, style = MaterialTheme.typography.titleMedium, color = White)
                    Spacer(Modifier.height(4.dp))
                    Text(chat.snippet, style = MaterialTheme.typography.bodyMedium, color = Soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (chat.time.isNotEmpty()) Text(chat.time, style = MaterialTheme.typography.labelSmall, color = Soft)
                    if (chat.unread > 0) {
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.background(Blue, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(chat.unread.toString(), style = MaterialTheme.typography.labelMedium, color = White)
                        }
                    }
                }
            }
            HorizontalDivider(color = Surface2.copy(alpha = .6f))
        }
        if (visible.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp)) {
                Text(if (filter == "Groups" && !preview) "Groups are coming later" else "No chats here yet", style = MaterialTheme.typography.titleLarge, color = White)
                Spacer(Modifier.height(8.dp))
                Text(if (filter == "Groups" && !preview) "For now, you can exchange short messages with a nearby Yap phone."
                    else "Try All or change your search.", color = Soft)
            }
        }
    }
}
