package org.yapmessenger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
internal fun ConversationScreen(chat: ChatSummary, messages: List<ChatMessage>, preview: Boolean,
    draft: String, onDraft: (String) -> Unit, pending: Boolean, status: String, send: () -> Unit,
    back: () -> Unit, controls: () -> Unit, group: () -> Unit, details: (Long) -> Unit,
    maxBytes: Int = 20, sendEnabled: Boolean = true, secure: Boolean = false) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, pending) { if (messages.isNotEmpty()) listState.scrollToItem(messages.size) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = White) }
            YapAvatar(if (preview || secure) chat.name else "", chat.group, 42)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).then(if (chat.group) Modifier.clickable(onClickLabel = "Group details", onClick = group) else Modifier)) {
                Text(chat.name, color = White, style = MaterialTheme.typography.titleMedium)
                Text(when {
                    chat.group -> "Sample group · 6 members"
                    preview && chat.id == "maya" -> "Internet"
                    preview && chat.id == "updates" -> "Sample app updates"
                    secure -> if (sendEnabled) "Paired · end-to-end encrypted" else status
                    else -> "Legacy Bluetooth · unencrypted"
                },
                    color = Soft, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = controls) { Icon(Icons.Default.MoreVert, if (secure) "Contact details" else "Bluetooth controls", tint = White) }
        }
        HorizontalDivider(color = Surface2)
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("TODAY", color = Soft, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Forum, null, tint = Lime, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Say something.", style = MaterialTheme.typography.headlineMedium, color = White)
                    Text(if (secure) "Messages stay queued until a connection is available." else "Two phones. No internet. Just Yap.", color = Soft, modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(messages, key = { it.id }) { MessageBubble(it) { details(it.id) } }
            if (!preview && (pending || messages.lastOrNull()?.failureReason != null || status.contains("permission", true))) item {
                Text(status, style = MaterialTheme.typography.bodySmall, color = Soft,
                    modifier = Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (preview) Text("Preview only · Sending is disabled", style = MaterialTheme.typography.labelMedium, color = Soft,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        if (secure && !sendEnabled) Text(status, color = Soft, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Composer(draft = if (preview) "" else draft, onDraft = onDraft, pending = pending, enabled = !preview && sendEnabled, send = send, maxBytes = maxBytes)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, details: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start) {
        val file = message.fileName != null
        val bubble = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (message.mine) 18.dp else 5.dp, bottomEnd = if (message.mine) 5.dp else 18.dp)
        val look = if (file) Modifier.clip(bubble).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFF4A63FF), androidx.compose.ui.graphics.Color(0xFF2238D6))), bubble)
            else if (message.mine) Modifier.clip(bubble).background(LimeBrush, bubble) else Modifier.yapSurface(bubble)
        Column(Modifier.widthIn(max = 290.dp).then(look)
            .clickable(onClickLabel = "Message details", role = Role.Button, onClick = details)
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (file) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null, tint = White, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Column { Text(message.fileName.orEmpty(), color = White, fontWeight = FontWeight.SemiBold); Text(message.fileSize.orEmpty(), color = White, style = MaterialTheme.typography.labelMedium) }
                }
            } else Text(message.body, color = if (message.mine) Ink else White, style = MaterialTheme.typography.bodyLarge)
            if (message.createdAt > 0) Text(messageTime(if (file) message.recipientReceiptAt ?: message.acknowledgedAt ?: message.createdAt else message.createdAt),
                color = if (message.mine && !file) Ink.copy(alpha = .75f) else White,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End).padding(top = 5.dp))
        }
        if (message.state.isNotEmpty()) Text(message.state, color = if (message.failureReason != null) Coral else Soft,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, pending: Boolean, enabled: Boolean, send: () -> Unit, maxBytes: Int) {
    val bytes = draft.toByteArray(Charsets.UTF_8).size
    val canSend = enabled && bytes in 1..maxBytes && !pending
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = draft, onValueChange = onDraft, enabled = enabled, placeholder = { Text("Message") },
                singleLine = maxBytes == 20, maxLines = if (maxBytes == 20) 1 else 4, isError = bytes > maxBytes, shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    errorIndicatorColor = Coral, focusedContainerColor = Surface, unfocusedContainerColor = Surface,
                    disabledContainerColor = Surface, errorContainerColor = Surface, cursorColor = Lime),
                modifier = Modifier.weight(1f))
            FilledIconButton(onClick = send, enabled = canSend, modifier = Modifier.size(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
        if (enabled && (pending || bytes >= maxBytes * 7 / 10)) Text(when { pending -> "Saving to queue…"; bytes > maxBytes -> "$bytes / $maxBytes UTF-8 bytes · Shorten your message"; else -> "$bytes / $maxBytes UTF-8 bytes" },
            color = if (bytes > maxBytes) Coral else Soft, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 14.dp, top = 6.dp))
    }
}
