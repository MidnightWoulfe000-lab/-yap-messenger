package org.yapmessenger.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private lateinit var bluetooth: YapBluetooth
    private val session = ChatSession()
    private var screen by mutableStateOf("chats")
    private var permissionAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(23, 25, 35)),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(23, 25, 35))
        )
        bluetooth = YapBluetooth(this) { update ->
            runOnUiThread {
                session.onBluetoothUpdate(update)
            }
        }
        setContent {
            YapTheme {
                YapApp(
                    screen = screen,
                    navigate = { screen = it },
                    draft = session.draft,
                    onDraft = { session.draft = it },
                    messages = session.messages,
                    status = session.status,
                    pending = session.pendingText != null,
                    send = { sendMessage() },
                    discover = { withBluetoothPermissions { bluetooth.startAdvertising() } }
                )
            }
        }
    }

    private fun sendMessage() {
        withBluetoothPermissions {
            session.beginSend()?.let(bluetooth::send)
        }
    }

    private fun withBluetoothPermissions(action: () -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            permissionAction = action
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val action = permissionAction
            permissionAction = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                action?.invoke()
            else session.status = "Bluetooth permission is needed. Allow the requested permissions and try again."
        }
    }

    override fun onStop() {
        bluetooth.stop()
        session.onStopped()
        super.onStop()
    }
}

@Composable
private fun YapApp(
    screen: String, navigate: (String) -> Unit, draft: String, onDraft: (String) -> Unit,
    messages: List<ChatMessage>, status: String, pending: Boolean,
    send: () -> Unit, discover: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(screen) {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    BackHandler(enabled = screen != "chats") {
        navigate(if (screen == "controls") "conversation" else "chats")
    }
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().imePadding()) {
        when (screen) {
            "conversation" -> {
                ConversationHeader(onBack = { navigate("chats") }, onControls = { navigate("controls") })
                HorizontalDivider(color = Surface2, thickness = 1.dp)
                val listState = rememberLazyListState()
                LaunchedEffect(messages.size) {
                    if (messages.isNotEmpty()) listState.scrollToItem(messages.size + 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    item { SafetyNote() }
                    if (messages.isEmpty()) item { EmptyConversation() }
                    itemsIndexed(messages) { _, message -> MessageBubble(message) }
                    item { Text(status, color = Soft, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 14.dp).semantics { liveRegion = LiveRegionMode.Polite }, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
                Composer(draft, onDraft, pending, send)
            }
            else -> {
                Box(Modifier.weight(1f)) {
                    when (screen) {
                        "network" -> NetworkScreen(status, discover)
                        "settings" -> SettingsScreen()
                        "controls" -> ControlsScreen(status, discover) { navigate("conversation") }
                        else -> ChatsScreen(messages, { navigate("conversation") })
                    }
                }
                if (screen != "controls") BottomBar(screen, navigate)
            }
        }
    }
}

@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("yap", color = White, fontWeight = FontWeight.Black, fontSize = 46.sp, letterSpacing = (-2).sp)
        Text(".", color = Lime, fontWeight = FontWeight.Black, fontSize = 46.sp)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Lime, letterSpacing = 2.sp)
}

@Composable
private fun Avatar(size: Int = 54) {
    Box(
        Modifier.size(size.dp).background(Blue, RoundedCornerShape(19.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("Y", fontSize = (size / 2).sp, fontWeight = FontWeight.Black, color = White)
    }
}

@Composable
private fun ChatsScreen(messages: List<ChatMessage>, open: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = YapSpacing.gutter)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            IconButton(onClick = open, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Add, "Open chat", tint = Lime, modifier = Modifier.size(30.dp))
            }
        }
        Text("YOUR SPACE. YOUR VOICE.", fontSize = 11.sp, color = Soft, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(34.dp))
        Text("Messages", style = MaterialTheme.typography.headlineLarge, color = White)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = true, onClick = {}, label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lime, selectedLabelColor = Ink),
                modifier = Modifier.heightIn(min = YapSpacing.touchTarget))
            AssistChip(onClick = {}, enabled = false, label = { Text("Groups · coming later") }, modifier = Modifier.heightIn(min = YapSpacing.touchTarget))
        }
        Spacer(Modifier.height(29.dp))
        SectionTitle(if (messages.isEmpty()) "START HERE" else "THIS SESSION")
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = open)
                .background(Surface).padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Bluetooth test chat", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = White)
                Spacer(Modifier.height(5.dp))
                Text(messages.lastOrNull()?.body ?: "Start a conversation", fontSize = 14.sp,
                    color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(7.dp))
                Text("Finds a phone when you send", fontSize = 12.sp, color = Soft)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Soft)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.WifiTethering, null, tint = Lime)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Made for the moments without signal.", fontSize = 15.sp, color = White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("Direct Bluetooth messaging is live. Groups and relays are still being built.",
                    color = Soft, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConversationHeader(onBack: () -> Unit, onControls: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Lime)
        }
        Avatar(44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Bluetooth test chat", color = White, style = MaterialTheme.typography.titleMedium)
            Text("Unverified · Direct Bluetooth", color = Soft, fontSize = 11.sp)
        }
        IconButton(onClick = onControls, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreVert, "Bluetooth controls", tint = White)
        }
    }
}

@Composable
private fun SafetyNote() {
    Row(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Info, null, tint = Coral, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Text("Test chat · Not encrypted · Don't share private information",
            color = Soft, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun EmptyConversation() {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Forum, null, tint = Lime, modifier = Modifier.size(45.dp))
        Spacer(Modifier.height(18.dp))
        Text("Say something.", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = White)
        Spacer(Modifier.height(8.dp))
        Text("Two phones. No internet. Just Yap.", color = Soft, fontSize = 14.sp)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start
    ) {
        Box(
            Modifier.widthIn(max = 285.dp).background(
                if (message.mine) Lime else Surface2,
                RoundedCornerShape(
                    topStart = 19.dp, topEnd = 19.dp,
                    bottomEnd = if (message.mine) 5.dp else 19.dp,
                    bottomStart = if (message.mine) 19.dp else 5.dp
                )
            ).padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(message.body, color = if (message.mine) Ink else White, fontSize = 16.sp, lineHeight = 22.sp)
        }
        if (message.state.isNotEmpty()) {
            Text(message.state, style = MaterialTheme.typography.labelMedium, color = Soft, modifier = Modifier.padding(top = 5.dp, end = 3.dp))
        }
    }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, pending: Boolean, send: () -> Unit) {
    val bytes = draft.toByteArray(Charsets.UTF_8).size
    val canSend = bytes in 1..20 && !pending
    Column(Modifier.fillMaxWidth().background(Ink).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(YapSpacing.gap)) {
            OutlinedTextField(
                value = draft, onValueChange = onDraft,
                label = { Text("Message") },
                placeholder = { Text("Write a message…") },
                singleLine = true, isError = bytes > 20,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
                shape = RoundedCornerShape(26.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Lime, unfocusedBorderColor = Soft,
                    focusedContainerColor = Surface, unfocusedContainerColor = Surface,
                    errorContainerColor = Surface, cursorColor = Lime
                ),
                modifier = Modifier.weight(1f)
            )
            FilledIconButton(onClick = send, enabled = canSend, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", modifier = Modifier.size(23.dp))
            }
        }
        Text(
            when {
                pending -> "Sending… Keep Yap open."
                bytes > 20 -> "$bytes / 20 UTF-8 bytes · Shorten your message"
                else -> "$bytes / 20 UTF-8 bytes"
            },
            color = if (bytes > 20) Coral else Soft, fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, top = 6.dp)
        )
    }
}

@Composable
private fun BottomBar(screen: String, navigate: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Surface, RoundedCornerShape(23.dp)).padding(6.dp)
    ) {
        listOf(
            Triple("chats", "Chats", Icons.Default.ChatBubbleOutline),
            Triple("network", "Network", Icons.Default.Hub),
            Triple("settings", "Settings", Icons.Default.Tune)
        ).forEach { (key, title, icon) ->
            val selected = key == screen
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Lime else Color.Transparent)
                    .heightIn(min = YapSpacing.touchTarget)
                    .selectable(selected = selected, role = Role.Tab, onClick = { navigate(key) }).padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, null, tint = if (selected) Ink else Soft, modifier = Modifier.size(21.dp))
                Spacer(Modifier.height(3.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) Ink else Soft)
            }
        }
    }
}

@Composable
private fun NetworkScreen(status: String, discover: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = YapSpacing.gutter)) {
        Spacer(Modifier.height(25.dp))
        SectionTitle("THE LIVING NETWORK")
        Spacer(Modifier.height(9.dp))
        Text("Your network.", style = MaterialTheme.typography.headlineLarge, color = White)
        Text("No towers. No gatekeepers.", fontSize = 14.sp, color = Soft)
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.fillMaxWidth().height(310.dp).background(Surface, RoundedCornerShape(26.dp))
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width * .5f, size.height * .52f)
                drawCircle(Blue.copy(alpha = .35f), radius = 100.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
                drawCircle(Lime.copy(alpha = .12f), radius = 62.dp.toPx(), center = center)
                drawCircle(Lime.copy(alpha = .22f), radius = 40.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
                drawCircle(Lime, radius = 13.dp.toPx(), center = center)
            }
            Text("THIS PHONE · NO LIVE PEER MAP", fontSize = 12.sp, color = Soft,
                letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(22.dp))
        SectionTitle("BLUETOOTH STATUS")
        Spacer(Modifier.height(9.dp))
        Text(status, color = White, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Button(onClick = discover, modifier = Modifier.fillMaxWidth().heightIn(min = YapSpacing.touchTarget), shape = RoundedCornerShape(18.dp)) {
            Text("Make this phone discoverable", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Text("Yap looks for a phone when you send. A live peer map and relay routes are not available yet.",
            fontSize = 13.sp, lineHeight = 19.sp, color = Soft)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(YapSpacing.gutter)) {
        SectionTitle("YOUR YAP")
        Spacer(Modifier.height(12.dp))
        Text("Settings.", style = MaterialTheme.typography.headlineLarge, color = White)
        Spacer(Modifier.height(28.dp))
        SettingsTile("Privacy first", "Encryption and verified identities are not available in this prototype.", Icons.Default.Shield)
        Spacer(Modifier.height(12.dp))
        SettingsTile("Your messages", "Messages are kept only while the app is open.", Icons.Default.ChatBubbleOutline)
        Spacer(Modifier.height(12.dp))
        SettingsTile("About Yap", "Open source · Built for communication without gatekeepers.", Icons.Default.Info)
        Spacer(Modifier.height(30.dp))
        Text("Nobody gets to silence you.", color = Lime, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsTile(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(17.dp),
        verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Lime)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = Soft, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun ControlsScreen(status: String, discover: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(YapSpacing.gutter)) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Lime) }
        Spacer(Modifier.height(20.dp))
        SectionTitle("BLUETOOTH TEST")
        Spacer(Modifier.height(10.dp))
        Text("Connection controls.", style = MaterialTheme.typography.headlineLarge, color = White)
        Spacer(Modifier.height(20.dp))
        Text(status, fontSize = 15.sp, color = Soft)
        Spacer(Modifier.height(24.dp))
        Button(onClick = discover, modifier = Modifier.fillMaxWidth().heightIn(min = YapSpacing.touchTarget), shape = RoundedCornerShape(18.dp)) {
            Text("Make this phone discoverable", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(22.dp))
        Text("Keep Yap open on both phones. Messages are limited to 20 bytes and are not encrypted or saved.",
            color = Soft, fontSize = 14.sp, lineHeight = 21.sp)
    }
}
