package org.yapmessenger.app

import android.os.SystemClock
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.hypot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val CODE_LIFETIME_MS = 60_000L

@Composable
internal fun PairingScreen(model: SecureModel, scan: () -> Unit, share: (String) -> Unit, startNearby: () -> Unit, myName: String, back: () -> Unit) {
    val offer = model.snapshot.offer
    var more by remember { mutableStateOf(false) }
    var packageText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    // Friends added during this visit, so the screen can show them progressing to "added".
    val knownIds = remember { model.snapshot.contacts.map { it.id }.toSet() }
    val added = model.snapshot.contacts.filter { it.id !in knownIds }
    LaunchedEffect(Unit) { model.ensureOffer() }
    // Scanning is the consent step: add straight away, no form. Both phones still confirm each other.
    LaunchedEffect(model.scannedQr) {
        val code = model.scannedQr
        if (code.isNotEmpty()) {
            model.scannedQr = ""
            if (!model.meshEnabled) startNearby()
            model.importPairing(code, "")
        }
    }
    // The code changes every minute. A code a friend has already scanned (reserved) is left alone.
    val reserved = offer?.reserved == true
    val born = remember(offer?.qr) { SystemClock.elapsedRealtime() }
    var remaining by remember(offer?.qr) { mutableLongStateOf(CODE_LIFETIME_MS) }
    LaunchedEffect(offer?.qr, reserved) {
        var asked = false
        while (offer != null && !reserved) {
            remaining = (CODE_LIFETIME_MS - (SystemClock.elapsedRealtime() - born)).coerceAtLeast(0)
            if (remaining == 0L && !asked) { asked = true; model.rotateOffer() }
            delay(250)
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Add a friend", back)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (myName.isNotBlank()) "Hi $myName, let your friend scan this" else "Let your friend scan this", color = White,
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
            if (offer != null) {
                PairingQr(offer.qr)
                if (reserved) {
                    Text("Your code is linked to your new friend. Now they need to scan it too.", color = Soft, style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(progress = { remaining / CODE_LIFETIME_MS.toFloat() }, color = Lime, trackColor = Surface2,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp).height(4.dp).clip(RoundedCornerShape(2.dp)))
                    Text("New code in ${(remaining + 999) / 1000}s", color = Soft, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Box(Modifier.size(264.dp), contentAlignment = Alignment.Center) {
                    if (model.locked) Text("Protected identity is locked", color = Soft) else CircularProgressIndicator(color = Lime)
                }
            }
            Button(onClick = scan, enabled = !model.locked && offer != null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Scan their code")
            }
            Text("Then both phones show matching fingerprints. Confirm they match and you are connected.", color = Soft,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            if (model.searching) Row(Modifier.fillMaxWidth().yapCard(14.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Lime, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Looking for their phone", color = White, style = MaterialTheme.typography.titleMedium)
                    Text("Keep both phones close with Yap open on both.", color = Soft, style = MaterialTheme.typography.bodySmall)
                }
            }
            added.forEach { contact ->
                Row(Modifier.fillMaxWidth().yapCard(14.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (contact.ready) Icon(Icons.Default.CheckCircle, null, tint = Lime) else CircularProgressIndicator(Modifier.size(24.dp), color = Lime, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (contact.ready) "${contact.name} added" else contact.name, color = White, style = MaterialTheme.typography.titleMedium)
                        Text(if (contact.ready) "You can message them now." else contact.phase, color = Soft, style = MaterialTheme.typography.bodySmall)
                    }
                    if (contact.ready) TextButton(onClick = back) { Text("Done") }
                }
            }
            if (added.isEmpty()) InfoNote(model.status, accent = if (model.locked) Coral else Blue)
            TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (more) "Hide other ways" else "Other ways to add a friend") }
            if (more) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (offer != null) OutlinedButton(onClick = { share(offer.pairingPackage) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Send my code another way") }
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(packageText, { packageText = it.take(8192) }, label = { Text("Their code or pairing package") }, maxLines = 5, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { model.importPairing(packageText, name); packageText = "" }, enabled = packageText.isNotBlank() && !model.locked,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add friend") }
                    Text("Only add a code from someone whose fingerprint you have checked in person. A shared code alone does not prove who sent it.", color = Soft, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = Surface2)
                    Text("Your fingerprint", color = White, style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(model.snapshot.identity.chunked(8).joinToString(" "), color = Soft, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Lime-on-black squiggle QR: soft modules, rounded finder eyes and a ring in the middle. Still a standard, scannable QR. */
@Composable
private fun PairingQr(value: String) {
    val grid = remember(value) {
        val m = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 1, 1,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
        Array(m.height) { y -> BooleanArray(m.width) { x -> m[x, y] } }
    }
    val ink = Lime
    Crossfade(grid, animationSpec = tween(250), label = "code") { g ->
        Canvas(Modifier.fillMaxWidth().widthIn(max = 340.dp).aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(Ink)
            .semantics { contentDescription = "Your Yap pairing code. It changes every minute." }) {
            val n = g.size
            val cell = size.width / (n + 4)
            val o = cell * 2
            val centre = n / 2f
            val hole = 4.3f
            fun eye(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
            fun dark(x: Int, y: Int) = x in 0 until n && y in 0 until n && g[y][x] && !eye(x, y) &&
                hypot(x + .5f - centre, y + .5f - centre) > hole
            // Squiggles: neighbouring modules join into rounded strokes; a lone module is a dot.
            val pen = cell * .86f
            fun at(x: Int, y: Int) = Offset(o + (x + .5f) * cell, o + (y + .5f) * cell)
            for (y in 0 until n) for (x in 0 until n) if (dark(x, y)) {
                drawCircle(ink, pen / 2, at(x, y))
                if (dark(x + 1, y)) drawLine(ink, at(x, y), at(x + 1, y), pen, StrokeCap.Round)
                if (dark(x, y + 1)) drawLine(ink, at(x, y), at(x, y + 1), pen, StrokeCap.Round)
            }
            fun eyes(ex: Int, ey: Int) {
                val ring = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRoundRect(RoundRect(o + ex * cell, o + ey * cell, o + (ex + 7) * cell, o + (ey + 7) * cell, CornerRadius(cell * 2.2f)))
                    addRoundRect(RoundRect(o + (ex + 1) * cell, o + (ey + 1) * cell, o + (ex + 6) * cell, o + (ey + 6) * cell, CornerRadius(cell * 1.4f)))
                }
                drawPath(ring, ink)
                drawRoundRect(ink, Offset(o + (ex + 2) * cell, o + (ey + 2) * cell), Size(cell * 3, cell * 3), CornerRadius(cell * 1.1f))
            }
            eyes(0, 0); eyes(n - 7, 0); eyes(0, n - 7)
            drawCircle(ink, radius = cell * 3.1f, center = Offset(o + centre * cell, o + centre * cell), style = Stroke(cell * .9f))
        }
    }
}

@Composable
internal fun SecureConnectionsScreen(model: SecureModel, enableMesh: () -> Unit, legacy: () -> Unit, back: () -> Unit) {
    var url by remember(model.relayUrl) { mutableStateOf(model.relayUrl) }
    var credential by remember { mutableStateOf("") }
    var ca by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Connections", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val byRadio = model.localLinks.groupingBy { it.label }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
            DetailRow("Nearby messaging", "${model.localPeers} nearby phone(s) connected" + if (byRadio.isNotEmpty() && model.localLinks.size == model.localPeers) " · $byRadio" else "", Icons.Default.Hub)
            Text("Sends encrypted messages to phones in range over Bluetooth and Wi-Fi while Yap is open. No internet, router or hotspot needed. Queued messages survive a restart.", color = Soft)
            Button(onClick = enableMesh, enabled = !model.locked && model.snapshot.identity.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (model.meshEnabled) "Restart nearby messaging" else "Turn on nearby messaging")
            }
            if (model.meshEnabled) OutlinedButton(onClick = model::disableMesh, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Pause nearby messaging") }
            if (Build.VERSION.SDK_INT < 29) InfoNote("This phone can use Wi-Fi only. Bluetooth nearby messaging needs Android 10 or later.")
            InfoNote(model.status, accent = if (model.locked) Coral else Blue)
            HorizontalDivider(color = Surface2)
            TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (showAdvanced) "Hide advanced" else "Advanced: relay, test chat and nearby log")
            }
            if (showAdvanced) {
            Text("Nearby log", color = White, style = MaterialTheme.typography.titleMedium)
            Text("Recent Bluetooth and Wi-Fi steps, to help fix connection problems. It lists events only, never names, addresses or messages, and is cleared when Yap closes.", color = Soft)
            val context = LocalContext.current
            var copied by remember { mutableStateOf(false) }
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Yap nearby log", MeshLog.text()))
                copied = true
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (copied) "Copied. Paste it where you need it" else "Copy nearby log") }
            HorizontalDivider(color = Surface2)
            DetailRow("Internet relay", if (model.internetConnected) "Connected over TLS" else if (model.relayConfigured) "Configured · waiting for connection" else "Disabled", Icons.Default.Language)
            Text("Use a relay you control. Its operator must register this phone's mailbox token. No public relay is enabled by default.", color = Soft)
            OutlinedTextField(url, { url = it.take(2048) }, label = { Text("Relay URL") }, placeholder = { Text("wss://host:port/yap/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(credential, { credential = it.take(128) }, label = { Text("Relay credential") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("Leave the credential blank to keep it for this exact URL.", color = Soft, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Private relay CA certificate") }
            if (advanced) {
                OutlinedTextField(ca, { ca = it.take(8192) }, label = { Text("Public PEM CA certificate · optional") }, maxLines = 5, modifier = Modifier.fillMaxWidth())
                Text("Only add a CA you trust for your relay. Hostname checks remain enabled. Never paste a private key. Blank keeps the saved CA for this URL.", color = Soft, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { model.saveRelay(url, credential, ca); credential = ""; ca = "" }, enabled = url.isNotBlank() && !model.locked,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save relay configuration") }
            if (model.relayConfigured) OutlinedButton(onClick = model::removeRelay, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Disable internet relay") }
            Text("Mailbox token", color = White, style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(model.snapshot.route, color = Soft) }
            HorizontalDivider(color = Surface2)
            Text("Legacy Bluetooth test", color = White, style = MaterialTheme.typography.titleMedium)
            Text("The original 20-byte chat is unencrypted and separate from paired messaging.", color = Soft)
            OutlinedButton(onClick = legacy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open legacy Bluetooth controls") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun SecureIdentityScreen(model: SecureModel, pair: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("Privacy & identity", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Your identity", color = White, style = MaterialTheme.typography.titleLarge)
            SelectionContainer { Text(model.snapshot.identity.chunked(8).joinToString(" "), color = Soft) }
            Text("Paired text uses the pinned Signal protocol. Keys, sessions, history and queues on this phone are protected by Android Keystore-backed storage.", color = Soft)
            InfoNote("Experimental debug build · no independent implementation security audit", accent = Coral)
            Text("Pairing verifies a key you check, not a person's legal identity. Relay operators can observe traffic metadata. Lost-device recovery, multi-device revocation and group governance are not enabled.", color = Soft)
            Button(onClick = pair, enabled = !model.locked, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Pair a contact") }
            if (model.locked) OutlinedButton(onClick = model::reopen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Retry opening protected identity") }
            Text(model.status, color = Soft)
        }
    }
}

@Composable
internal fun SecureStorageScreen(model: SecureModel, back: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Data & storage", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DetailRow("Saved messages", "${model.snapshot.history.size} visible message(s)", Icons.Default.Storage)
            DetailRow("Outgoing ciphertext", "${model.snapshot.queued} queued record(s)", Icons.Default.Outbox)
            DetailRow("Relay custody", "${model.relayQueued} opaque envelope(s)", Icons.Default.Hub)
            Text("Paired history is encrypted at rest and excluded from cloud backup and device transfer. The legacy test chat remains in memory only.", color = Soft)
            Button(onClick = model::retry, enabled = !model.locked, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Retry queued messages") }
            OutlinedButton(onClick = { confirm = true }, enabled = !model.locked, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Clear visible encrypted history") }
            InfoNote("Clearing visible history keeps queued ciphertext and unexpired replay protection. It cannot delete another phone's messages.")
            Text(model.status, color = Soft)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Clear visible history?") },
        text = { Text("Remove displayed paired messages on this phone. Queued ciphertext will still retry; contacts and keys remain.") },
        confirmButton = { TextButton(onClick = { model.clearHistory(); confirm = false }) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
internal fun SecureContactScreen(contact: SecureContact?, model: SecureModel, share: (String) -> Unit, back: () -> Unit) {
    var retire by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Contact identity", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (contact != null) {
                Text(contact.name, color = White, style = MaterialTheme.typography.headlineMedium)
                Text(contact.phase, color = if (contact.ready) Lime else Soft)
                Text(if (contact.ready) "Paired key fingerprint" else "Key fingerprint · awaiting mutual proof", color = White, style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(contact.fingerprint.chunked(8).joinToString(" "), color = Soft) }
                if (!contact.ready) OutlinedButton(onClick = { share(contact.pairingPackage) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Share pairing reply") }
                OutlinedButton(onClick = { retire = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Retire this session") }
                InfoNote("Retiring cancels this phone's old outgoing ciphertext and disables the old session/hint. Already relayed copies cannot be recalled.")
            } else Text("Contact session has been retired", color = Soft)
        }
    }
    if (retire && contact != null) AlertDialog(onDismissRequest = { retire = false }, title = { Text("Retire this session?") },
        text = { Text("Cancel pending ciphertext for ${contact.name} and remove the current session. Pair in person again before sending.") },
        confirmButton = { TextButton(onClick = { model.retire(contact.id); retire = false; back() }) { Text("Retire session") } },
        dismissButton = { TextButton(onClick = { retire = false }) { Text("Cancel") } })
}

@Composable
internal fun AboutMessagingScreen(licences: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("About Yap", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Wordmark()
            Text("Nobody gets to silence you.", color = Lime, style = MaterialTheme.typography.titleLarge)
            Text("Communication is a right, not a privilege.", color = White)
            Text("Version 0.2.0-debug", color = Soft)
            InfoNote("Experimental paired messaging, foreground local relaying and optional internet relay. Not independently security-audited.", accent = Coral)
            Text("The native adapter includes libsignal under AGPLv3. Original Yap core sources remain GPLv3; dependency notices and source build instructions accompany the debug release.", color = Soft)
            OutlinedButton(onClick = licences, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Source & licences") }
            DebugUpdateSection()
        }
    }
}

@Composable
internal fun MessagingLicencesScreen(back: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("Loading licence notices…") }
    LaunchedEffect(Unit) { text = withContext(Dispatchers.IO) { context.assets.open("messaging-licences.txt").bufferedReader().use { it.readText() } } }
    Column(Modifier.fillMaxSize()) { PageHeader("Source & licences", back)
        SelectionContainer { Text(text, color = Soft, modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) }
    }
}
