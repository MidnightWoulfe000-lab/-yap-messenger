package org.yapmessenger.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF171923)
private val Surface = Color(0xFF22283A)
private val Surface2 = Color(0xFF2A3248)
private val Lime = Color(0xFFC7FF3C)
private val Blue = Color(0xFF4965FF)
private val Coral = Color(0xFFFF7958)
private val Soft = Color(0xFFA8B1C8)
private val White = Color(0xFFF7F8FF)

private data class ChatMessage(val mine: Boolean, val body: String, val state: String = "")

class MainActivity : ComponentActivity() {
    private lateinit var bluetooth: YapBluetooth
    private var status by mutableStateOf("Ready to connect")
    private var screen by mutableStateOf("chats")
    private var draft by mutableStateOf("")
    private var pendingText: String? = null
    private val messages = mutableStateListOf<ChatMessage>()
    private var permissionAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(23, 25, 35)
        window.navigationBarColor = android.graphics.Color.rgb(23, 25, 35)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        bluetooth = YapBluetooth(this) { update ->
            runOnUiThread {
                status = update
                when {
                    update.startsWith("Message received: ") ->
                        messages.add(ChatMessage(false, update.removePrefix("Message received: ")))
                    update == "Message delivered to the other phone." -> {
                        val sent = pendingText
                        if (sent != null) {
                            messages.add(ChatMessage(true, sent, "Sent to nearby phone"))
                            if (draft == sent) draft = ""
                        }
                        pendingText = null
                    }
                    update.contains("failed", true) || update.contains("timed out", true) ||
                        update.startsWith("No nearby") || update.startsWith("Disconnected before") ->
                        pendingText = null
                }
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Lime, background = Ink, surface = Surface, onSurface = White
            )) {
                YapApp(
                    screen = screen,
                    navigate = { screen = it },
                    draft = draft,
                    onDraft = { draft = it },
                    messages = messages,
                    status = status,
                    pending = pendingText != null,
                    send = { sendMessage() },
                    discover = { withBluetoothPermissions { bluetooth.startAdvertising() } }
                )
            }
        }
    }

    private fun sendMessage() {
        val message = draft
        if (message.toByteArray(Charsets.UTF_8).size !in 1..20) {
            status = "Messages must be between 1 and 20 UTF-8 bytes"
            return
        }
        if (pendingText != null) {
            status = "Still sending. Please wait."
            return
        }
        withBluetoothPermissions {
            pendingText = message
            status = "Looking for a nearby phone..."
            bluetooth.send(message)
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
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val action = permissionAction
            permissionAction = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                action?.invoke()
            else status = "Bluetooth permission is needed"
        }
    }

    override fun onStop() {
        bluetooth.stop()
        super.onStop()
    }
}

@Composable
private fun YapApp(
    screen: String, navigate: (String) -> Unit, draft: String, onDraft: (String) -> Unit,
    messages: List<ChatMessage>, status: String, pending: Boolean,
    send: () -> Unit, discover: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        when (screen) {
            "conversation" -> {
                ConversationHeader(onBack = { navigate("chats") }, onControls = { navigate("controls") })
                HorizontalDivider(color = Surface2, thickness = 1.dp)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    item { SafetyNote() }
                    if (messages.isEmpty()) item { EmptyConversation() }
                    itemsIndexed(messages) { _, message -> MessageBubble(message) }
                    item { Text(status, color = Soft, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
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
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Lime, letterSpacing = 2.sp)
}

@Composable
private fun Avatar(size: Int = 54) {
    Box(
        Modifier.size(size.dp).background(Blue, RoundedCornerShape(19.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("Y", fontSize = (size / 2).sp, fontWeight = FontWeight.Black, color = White)
        Box(Modifier.size(10.dp).align(Alignment.BottomEnd).background(Lime, CircleShape).border(2.dp, Ink, CircleShape))
    }
}

@Composable
private fun ChatsScreen(messages: List<ChatMessage>, open: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
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
        Text("Messages", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = White)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FilterChip("All", true)
            FilterChip("Groups", false)
            FilterChip("Unread", false)
        }
        Spacer(Modifier.height(29.dp))
        SectionTitle("RECENT CHATS")
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = open)
                .background(Surface).padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Nearby Yap phone", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = White)
                Spacer(Modifier.height(5.dp))
                Text(messages.lastOrNull()?.body ?: "Start a conversation", fontSize = 14.sp,
                    color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(7.dp))
                Text("●  Unverified Bluetooth peer", fontSize = 11.sp, color = Lime)
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
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Lime else Surface)
            .clickable {
                if (!selected) android.widget.Toast.makeText(context, "Coming soon", android.widget.Toast.LENGTH_SHORT).show()
            }.padding(horizontal = 19.dp, vertical = 11.dp)
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) Ink else Soft,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
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
            Text("Nearby Yap phone", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
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
            color = Soft, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun EmptyConversation() {
    Column(Modifier.fillMaxWidth().padding(top = 110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
            Text(message.state, fontSize = 10.sp, color = Soft, modifier = Modifier.padding(top = 5.dp, end = 3.dp))
        }
    }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, pending: Boolean, send: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp).background(Surface, RoundedCornerShape(27.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (draft.isEmpty()) Text("Write a message...", color = Soft, fontSize = 15.sp)
                BasicTextField(
                    value = draft, onValueChange = onDraft, textStyle = androidx.compose.ui.text.TextStyle(
                        color = White, fontSize = 16.sp
                    ), cursorBrush = androidx.compose.ui.graphics.SolidColor(Lime),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = send),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(52.dp).background(if (pending) Soft else Lime, CircleShape)
                .clickable(enabled = !pending, onClick = send),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Ink, modifier = Modifier.size(23.dp))
        }
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
                    .clickable { navigate(key) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, title, tint = if (selected) Ink else Soft, modifier = Modifier.size(21.dp))
                Spacer(Modifier.height(3.dp))
                Text(title, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) Ink else Soft)
            }
        }
    }
}

@Composable
private fun NetworkScreen(status: String, discover: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(25.dp))
        SectionTitle("THE LIVING NETWORK")
        Spacer(Modifier.height(9.dp))
        Text("Your network.", fontSize = 33.sp, fontWeight = FontWeight.Bold, color = White)
        Text("No towers. No gatekeepers.", fontSize = 14.sp, color = Soft)
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.fillMaxWidth().height(310.dp).background(Surface, RoundedCornerShape(26.dp))
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width * .5f, size.height * .52f)
                val points = listOf(
                    Offset(size.width * .20f, size.height * .24f),
                    Offset(size.width * .78f, size.height * .22f),
                    Offset(size.width * .84f, size.height * .72f),
                    Offset(size.width * .19f, size.height * .78f)
                )
                points.forEach { point ->
                    drawLine(Blue.copy(alpha = .30f), center, point, strokeWidth = 2.dp.toPx())
                    drawCircle(Blue.copy(alpha = .25f), radius = 15.dp.toPx(), center = point)
                    drawCircle(Blue, radius = 5.dp.toPx(), center = point)
                }
                drawCircle(Lime.copy(alpha = .12f), radius = 62.dp.toPx(), center = center)
                drawCircle(Lime.copy(alpha = .22f), radius = 40.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
                drawCircle(Lime, radius = 13.dp.toPx(), center = center)
            }
            Text("ILLUSTRATION · NOT LIVE PEERS", fontSize = 9.sp, color = Soft,
                letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(22.dp))
        SectionTitle("BLUETOOTH STATUS")
        Spacer(Modifier.height(9.dp))
        Text(status, color = White, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.fillMaxWidth().background(Lime, RoundedCornerShape(18.dp))
                .clickable(onClick = discover).padding(17.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("MAKE THIS PHONE DISCOVERABLE", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text("The network map is a design preview. Real peer discovery and relays will appear here as we build them.",
            fontSize = 13.sp, lineHeight = 19.sp, color = Soft)
    }
}

@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        SectionTitle("YOUR YAP")
        Spacer(Modifier.height(12.dp))
        Text("Settings.", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = White)
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
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Lime) }
        Spacer(Modifier.height(20.dp))
        SectionTitle("BLUETOOTH TEST")
        Spacer(Modifier.height(10.dp))
        Text("Connection controls.", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = White)
        Spacer(Modifier.height(20.dp))
        Text(status, fontSize = 15.sp, color = Soft)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().background(Lime, RoundedCornerShape(18.dp))
            .clickable(onClick = discover).padding(18.dp), contentAlignment = Alignment.Center) {
            Text("MAKE THIS PHONE DISCOVERABLE", color = Ink, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(22.dp))
        Text("Keep Yap open on both phones. Messages are limited to 20 bytes and are not encrypted or saved.",
            color = Soft, fontSize = 14.sp, lineHeight = 21.sp)
    }
}
