package org.yapmessenger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
internal fun ChatsScreen(chats: List<ChatSummary>, preview: Boolean, open: (String) -> Unit, newChat: () -> Unit, pairFirst: Boolean = false,
    myName: String = "", myColour: Color = Lime, link: String = "", linkActive: Boolean = false, openProfile: () -> Unit = {}, openLink: () -> Unit = {}) {
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
                IconButton(onClick = newChat) { Icon(Icons.Default.Add, "Add a friend", tint = White) }
                if (!preview) Box(Modifier.size(48.dp).clickable(onClickLabel = "Your profile", role = Role.Button, onClick = openProfile), contentAlignment = Alignment.Center) {
                    YapAvatar(myName, size = 36, tint = myColour, shape = RoundedCornerShape(11.dp))
                }
            }
            if (search) OutlinedTextField(query, { query = it }, label = { Text("Search chats") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                listOf("All", "Groups", "Unread").forEach { label ->
                    val on = filter == label
                    val unread = chats.sumOf { it.unread }
                    Column(Modifier.heightIn(min = 48.dp).selectable(on, role = Role.Tab, onClick = { filter = label }), verticalArrangement = Arrangement.Center) {
                        Text(if (label == "Unread" && unread > 0) "Unread $unread" else label, style = MaterialTheme.typography.bodyLarge, color = if (on) Lime else Soft)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.height(2.dp).width(28.dp).background(if (on) Lime else Color.Transparent))
                    }
                }
                if (!preview && link.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    ConnectionStatus(link, linkActive, openLink)
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Surface2)
            if (pairFirst) {
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth().yapCard(18.dp).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PersonAdd, null, tint = Lime, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Add your first friend", color = White, style = MaterialTheme.typography.titleLarge)
                    Text("Meet in person, show your code and scan theirs. Then you can chat securely.", color = Soft, modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = newChat, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)) { Text("Add a friend") }
                }
                Spacer(Modifier.height(20.dp))
                Text("Unencrypted test chat", color = Soft, style = MaterialTheme.typography.labelMedium)
            }
        }
        items(visible, key = { it.id }) { chat ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).yapCard(18.dp).clickable { open(chat.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                YapAvatar(chat.name.takeIf { preview || chat.id.startsWith("secure:") } ?: "", chat.group)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(chat.name, style = MaterialTheme.typography.titleMedium, color = White)
                    Spacer(Modifier.height(4.dp))
                    Text(chat.snippet, style = MaterialTheme.typography.bodyMedium, color = Soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.align(Alignment.Top).padding(top = 3.dp), horizontalAlignment = Alignment.End) {
                    if (chat.time.isNotEmpty()) Text(chat.time, style = MaterialTheme.typography.labelSmall, color = Soft)
                    if (chat.unread > 0) {
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.background(Blue, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                            Text(chat.unread.toString(), style = MaterialTheme.typography.labelMedium, color = White)
                        }
                    }
                }
            }
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
