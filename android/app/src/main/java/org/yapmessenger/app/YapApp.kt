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
internal fun YapApp(session: ChatSession, send: () -> Unit, discover: () -> Unit,
    secure: SecureModel, discoverMesh: () -> Unit, scan: () -> Unit, share: (String) -> Unit, profile: ProfileStore) {
    var screen by rememberSaveable { mutableStateOf("chats") }
    var preview by rememberSaveable { mutableStateOf(false) }
    var selectedChat by rememberSaveable { mutableStateOf("ravi") }
    var selectedMessage by rememberSaveable { mutableStateOf(0L) }
    var settingsPage by rememberSaveable { mutableStateOf("") }
    var controlsBack by rememberSaveable { mutableStateOf("network") }
    var pairingBack by rememberSaveable { mutableStateOf("chats") }
    var legacyBack by rememberSaveable { mutableStateOf("connections") }
    var profileBack by rememberSaveable { mutableStateOf("chats") }
    var reduceMotion by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val primary = screen in listOf("chats", "network", "settings")
    val secureId = selectedChat.removePrefix("secure:").takeIf { selectedChat.startsWith("secure:") && !preview }
    val secureContact = secure.snapshot.contacts.firstOrNull { it.id == secureId }
    val messages = if (preview) previewConversation(selectedChat) else if (secureId != null) secure.snapshot.history.filter { it.contact == secureId }.map { it.message } else session.messages.toList().let { stored ->
        val attempt = session.lastAttempt
        if (attempt != null && stored.none { it.id == attempt.id }) stored + attempt else stored
    }
    val chat = if (preview) previewChats.firstOrNull { it.id == selectedChat } ?: previewChats.first()
        else if (secureId != null) ChatSummary(selectedChat, secureContact?.name ?: "Retired contact", secureContact?.phase ?: "Session retired") else liveChat(session)
    val realChats = secure.snapshot.contacts.map { contact ->
        val latest = secure.snapshot.history.lastOrNull { it.contact == contact.id }?.message
        ChatSummary("secure:${contact.id}", contact.name, latest?.body ?: contact.phase, latest?.let { messageTime(it.createdAt) }.orEmpty(), contact.unread)
    } + liveChat(session)
    fun back() {
        screen = when (screen) {
            "details", "group", "contact_identity" -> "conversation"
            "pairing" -> pairingBack
            "profile" -> profileBack
            "licences" -> "preference"
            "legacy_connections" -> legacyBack
            "connections" -> controlsBack
            "preference" -> "settings"
            else -> "chats"
        }
    }
    fun connections(parent: String) { controlsBack = parent; screen = "connections" }
    fun pair() { secure.scannedQr = ""; pairingBack = screen; screen = "pairing" }
    fun profileScreen() { profileBack = screen; screen = "profile" }
    fun legacy() { legacyBack = screen; screen = "legacy_connections" }
    LaunchedEffect(screen, preview) {
        focus.clearFocus()
        if (screen != "conversation") keyboard?.hide()
    }
    LaunchedEffect(screen, messages.size, preview, secureId) {
        if (!preview && screen == "conversation") { if (secureId != null) secure.markRead(secureId) else session.markRead() }
    }
    BackHandler(enabled = screen != "chats" || preview) {
        if (screen == "chats" && preview) preview = false else back()
    }
    if (!profile.onboarded) {
        Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().imePadding()) { OnboardingScreen(profile, discoverMesh) }
        return
    }
    val link = if (secure.internetConnected) "Internet" else if (secure.meshEnabled) (if (secure.localPeers > 0) "Nearby · ${secure.localPeers}" else "Nearby · looking") else "Offline"
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
                    chats = if (preview) previewChats else realChats,
                    preview = preview,
                    open = { selectedChat = it; screen = "conversation" },
                    newChat = { if (preview) { selectedChat = "ravi"; screen = "conversation" } else pair() },
                    pairFirst = !preview && secure.snapshot.contacts.isEmpty(),
                    myName = profile.name, myColour = avatarColour(profile.colour), link = link,
                    linkActive = secure.internetConnected || secure.localPeers > 0,
                    openProfile = { profileScreen() }, openLink = { connections("chats") })
                "conversation" -> ConversationScreen(chat, messages, preview, if (secureId != null) secure.draft else session.draft,
                    { if (secureId != null) secure.draft = it else session.draft = it }, if (secureId != null) secure.busy else session.pendingText != null,
                    if (secureId != null) secureContact?.phase ?: secure.status else session.status,
                    if (secureId != null) ({ secure.send(secureId) }) else send,
                    back = { back() }, controls = { if (secureId != null) screen = "contact_identity" else connections("conversation") },
                    group = { screen = "group" }, details = { selectedMessage = it; screen = "details" },
                    maxBytes = if (secureId != null) 4096 else 20,
                    sendEnabled = secureId == null || secureContact?.ready == true && !secure.locked,
                    secure = secureId != null)
                "network" -> NetworkScreen(session, preview, reduceMotion, manage = { connections("network") }, meshPeers = secure.localPeers, internet = secure.internetConnected, meshVisible = secure.meshDiscoverable)
                "settings" -> SettingsScreen(profile, openProfile = { profileScreen() }, open = { settingsPage = it; screen = "preference" },
                    preview = { preview = true; selectedChat = "ravi"; screen = "chats" })
                "preference" -> when {
                    !preview && settingsPage == "Privacy & identity" -> SecureIdentityScreen(secure, pair = { pair() }, back = { back() })
                    !preview && settingsPage == "Data & storage" -> SecureStorageScreen(secure, back = { back() })
                    !preview && settingsPage == "Network & connections" -> SecureConnectionsScreen(secure, discoverMesh, legacy = { legacy() }, back = { back() })
                    !preview && settingsPage == "About Yap" -> AboutMessagingScreen(licences = { screen = "licences" }, back = { back() })
                    else -> PreferenceScreen(settingsPage, session, preview, reduceMotion,
                        { reduceMotion = it }, back = { back() }, network = { connections("settings") })
                }
                "connections" -> if (preview || controlsBack == "conversation" && secureId == null) ConnectionScreen(session, preview, discover, back = { back() })
                    else SecureConnectionsScreen(secure, discoverMesh, legacy = { legacy() }, back = { back() })
                "legacy_connections" -> ConnectionScreen(session, preview, discover, back = { back() })
                "pairing" -> PairingScreen(secure, scan, share, discoverMesh, profile.name, back = { back() })
                "profile" -> ProfileScreen(profile, secure.snapshot.identity, addFriend = { pair() }, back = { back() })
                "contact_identity" -> SecureContactScreen(secureContact, secure, share, back = { back() })
                "licences" -> MessagingLicencesScreen(back = { back() })
                "details" -> messages.firstOrNull { it.id == selectedMessage }?.let { MessageDetailsScreen(it, preview) { back() } }
                    ?: MissingMessageScreen { back() }
                "group" -> GroupDetailsScreen(chat, back = { back() }, message = { screen = "conversation" })
            }
        }
        if (primary) YapNavigation(screen) { screen = it }
    }
}
