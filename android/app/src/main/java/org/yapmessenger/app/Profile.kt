package org.yapmessenger.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Accent colours people can pick for their avatar. Index 0 is the default. */
internal val AvatarColours = listOf(Lime, Blue, Coral, Color(0xFF7CE0D3), Color(0xFFB794FF), Color(0xFFFFD166))
private val AvatarColourNames = listOf("Lime", "Blue", "Coral", "Teal", "Violet", "Amber")
internal const val MaxProfileName = 30

internal fun avatarColour(index: Int): Color = AvatarColours[index.coerceIn(0, AvatarColours.lastIndex)]

/**
 * The user's own display name and avatar colour. The name is handed to a friend only when they
 * scan this phone's pairing code, so they see who you are. It is not signed and takes no part in
 * pairing or identity checks. The colour stays on this phone.
 */
internal class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("yap_profile", Context.MODE_PRIVATE)
    var name by mutableStateOf(prefs.getString("name", "").orEmpty()); private set
    var colour by mutableIntStateOf(prefs.getInt("colour", 0)); private set
    var onboarded by mutableStateOf(prefs.getBoolean("onboarded", false)); private set

    fun save(newName: String, newColour: Int) {
        name = newName.trim().take(MaxProfileName)
        colour = newColour.coerceIn(0, AvatarColours.lastIndex)
        prefs.edit().putString("name", name).putInt("colour", colour).apply()
    }

    fun finishOnboarding(newName: String, newColour: Int) {
        save(newName, newColour)
        onboarded = true
        prefs.edit().putBoolean("onboarded", true).apply()
    }
}

@Composable
private fun ColourPicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AvatarColours.forEachIndexed { index, colour ->
            val on = index == selected
            Box(Modifier.size(48.dp).selectable(on, role = Role.RadioButton, onClick = { onSelect(index) })
                .semantics { contentDescription = AvatarColourNames[index] }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(34.dp).background(colour, RoundedCornerShape(10.dp))
                    .border(if (on) 3.dp else 0.dp, White, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    if (on) Icon(Icons.Default.Check, null, tint = Ink, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
internal fun OnboardingScreen(store: ProfileStore, enableNearby: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(store.name) }
    var colour by rememberSaveable { mutableIntStateOf(store.colour) }
    BackHandler(enabled = step > 0) { step -= 1 }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth().semantics { contentDescription = "Step ${step + 1} of 3" }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { i -> Box(Modifier.height(4.dp).weight(1f).background(if (i <= step) Lime else Surface2, CircleShape)) }
        }
        Spacer(Modifier.height(32.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (step) {
                0 -> {
                    Wordmark()
                    Text("Nobody gets to silence you.", style = MaterialTheme.typography.headlineLarge, color = White)
                    Text("Message people near you with no internet, router or hotspot. Phones find each other directly.", style = MaterialTheme.typography.bodyLarge, color = Soft)
                    InfoNote("Yap is an early, experimental version. Expect rough edges while we build it.")
                }
                1 -> {
                    Text("What should people call you?", style = MaterialTheme.typography.headlineMedium, color = White)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { YapAvatar(name, size = 96, tint = avatarColour(colour)) }
                    OutlinedTextField(name, { name = it.take(MaxProfileName) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Pick a colour", style = MaterialTheme.typography.titleMedium, color = White)
                    ColourPicker(colour) { colour = it }
                    Text("This stays on this phone for now. Friends you add pick their own name for you.", style = MaterialTheme.typography.bodySmall, color = Soft)
                }
                else -> {
                    Text("Find people nearby", style = MaterialTheme.typography.headlineMedium, color = White)
                    Text("Yap needs a few permissions to find and talk to phones around you:", style = MaterialTheme.typography.bodyLarge, color = Soft)
                    DetailRow("Nearby devices", "Bluetooth and Wi-Fi, to find phones in range", Icons.Default.Bluetooth)
                    DetailRow("Notifications", "To tell you about new messages", Icons.Default.Notifications)
                    Text("You can change this any time in Settings → Network & connections.", style = MaterialTheme.typography.bodySmall, color = Soft)
                }
            }
        }
        when (step) {
            0 -> Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Get started") }
            1 -> {
                Button(onClick = { step = 2 }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Continue") }
                TextButton(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Skip for now") }
            }
            else -> {
                Button(onClick = { store.finishOnboarding(name, colour); enableNearby() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Turn on nearby messaging") }
                TextButton(onClick = { store.finishOnboarding(name, colour) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Not now") }
            }
        }
    }
}

@Composable
internal fun ProfileScreen(store: ProfileStore, fingerprint: String, addFriend: () -> Unit, back: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(store.name) }
    var colour by rememberSaveable { mutableIntStateOf(store.colour) }
    var details by rememberSaveable { mutableStateOf(false) }
    val changed = name.trim() != store.name || colour != store.colour
    Column(Modifier.fillMaxSize()) {
        PageHeader("Your profile", back)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { YapAvatar(name, size = 112, tint = avatarColour(colour)) }
            OutlinedTextField(name, { name = it.take(MaxProfileName) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Avatar colour", style = MaterialTheme.typography.titleMedium, color = White)
            ColourPicker(colour) { colour = it }
            Button(onClick = { store.save(name, colour) }, enabled = changed && name.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (changed) "Save changes" else "Saved")
            }
            Text("Your name is stored on this phone only. It isn't sent to anyone you add.", style = MaterialTheme.typography.bodySmall, color = Soft)
            HorizontalDivider(color = Surface2)
            LimeAction("Add a friend", "Show your code, scan theirs", Icons.Default.QrCode2, addFriend)
            TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (details) "Hide advanced details" else "Advanced details") }
            if (details) {
                Text("Your fingerprint", color = White, style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(fingerprint.ifEmpty { "Not available yet" }.chunked(8).joinToString(" "), color = Soft, style = MaterialTheme.typography.bodyMedium)
                }
                Text("A friend can compare this with what their phone shows to confirm it is really you.", style = MaterialTheme.typography.bodySmall, color = Soft)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Plain status line shown beside the Chats filters. [label] reflects only what the transport layer reports. */
@Composable
internal fun ConnectionStatus(label: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.heightIn(min = 48.dp).clickable(onClickLabel = "Connections", role = Role.Button, onClick = onClick), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (active) Lime else Soft, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (active) Lime else Soft, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
    }
}
