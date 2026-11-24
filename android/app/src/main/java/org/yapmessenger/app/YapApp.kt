package org.yapmessenger.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp

@Composable
internal fun YapApp(session: ChatSession, send: () -> Unit, discover: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf("chats") }
    var preview by rememberSaveable { mutableStateOf(false) }
    var selectedChat by rememberSaveable { mutableStateOf("ravi") }
    var selectedMessage by rememberSaveable { mutableStateOf(0L) }
    var settingsPage by rememberSaveable { mutableStateOf("") }
    var controlsBack by rememberSaveable { mutableStateOf("network") }
    var reduceMotion by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val primary = screen in listOf("chats", "network", "settings")
    val messages = if (preview) previewConversation(selectedChat) else session.messages.toList().let { stored ->
        val attempt = session.lastAttempt
        if (attempt != null && stored.none { it.id == attempt.id }) stored + attempt else stored
    }
    val chat = if (preview) previewChats.firstOrNull { it.id == selectedChat } ?: previewChats.first() else liveChat(session)
    fun back() {
        screen = when (screen) {
            "details", "group" -> "conversation"
            "connections" -> controlsBack
            "preference" -> "settings"
            else -> "chats"
        }
    }
    fun connections(parent: String) { controlsBack = parent; screen = "connections" }
    LaunchedEffect(screen, preview) {
        focus.clearFocus()
        if (screen != "conversation") keyboard?.hide()
    }
    LaunchedEffect(screen, session.messages.size, preview) {
        if (!preview && screen == "conversation") session.markRead()
    }
    BackHandler(enabled = screen != "chats" || preview) {
        if (screen == "chats" && preview) preview = false else back()
    }
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().imePadding()) {
        if (preview) {
            Row(Modifier.fillMaxWidth().background(Blue.copy(alpha = .15f)).padding(start = 16.dp)) {
                Text("Design preview · Sample data", style = MaterialTheme.typography.labelMedium,
                    color = White, modifier = Modifier.weight(1f).padding(vertical = 16.dp))
                TextButton(onClick = { preview = false; screen = "chats" }) { Text("Exit preview") }
            }
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                "chats" -> ChatsScreen(
                    chats = if (preview) previewChats else listOf(liveChat(session)),
                    preview = preview,
                    open = { selectedChat = it; screen = "conversation" },
                    newChat = { selectedChat = "ravi"; screen = "conversation" })
                "conversation" -> ConversationScreen(chat, messages, preview, session.draft,
                    { session.draft = it }, session.pendingText != null, session.status, send,
                    back = { back() }, controls = { connections("conversation") },
                    group = { screen = "group" }, details = { selectedMessage = it; screen = "details" })
                "network" -> NetworkScreen(session, preview, reduceMotion, manage = { connections("network") })
                "settings" -> SettingsScreen(open = { settingsPage = it; screen = "preference" },
                    preview = { preview = true; selectedChat = "ravi"; screen = "chats" })
                "preference" -> PreferenceScreen(settingsPage, session, preview, reduceMotion,
                    { reduceMotion = it }, back = { back() }, network = { connections("settings") })
                "connections" -> ConnectionScreen(session, preview, discover, back = { back() })
                "details" -> messages.firstOrNull { it.id == selectedMessage }?.let { MessageDetailsScreen(it, preview) { back() } }
                    ?: MissingMessageScreen { back() }
                "group" -> GroupDetailsScreen(chat, back = { back() }, message = { screen = "conversation" })
            }
        }
        if (primary) YapNavigation(screen) { screen = it }
    }
}
