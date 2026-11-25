package org.yapmessenger.app

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.withContext

@Composable
internal fun PairingScreen(model: SecureModel, scan: () -> Unit, share: (String) -> Unit, back: () -> Unit) {
    val offer = model.snapshot.offer
    var manual by remember { mutableStateOf(false) }
    var packageText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { model.ensureOffer() }
    LaunchedEffect(model.scannedQr) { if (model.scannedQr.isNotEmpty()) { packageText = model.scannedQr; manual = true } }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Pair a contact", back)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Scan each other's codes", color = White, style = MaterialTheme.typography.titleLarge)
            Text("Compare the fingerprint in person. Both people must confirm; a nearby phone is not automatically trusted.", color = Soft)
            if (offer != null) {
                PairingQr(offer.qr)
                if (offer.reserved) Text("This code is reserved for a pending contact. Create a new code for someone else.", color = Soft, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = scan, enabled = !model.locked, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Scan their code") }
                    OutlinedButton(onClick = { share(offer.pairingPackage) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Share package") }
                }
            } else Text(if (model.locked) "Protected identity is locked" else "Create a fresh code to pair another contact.", color = Soft)
            TextButton(onClick = model::makeOffer, enabled = !model.locked && model.snapshot.identity.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Create a fresh pairing code") }
            HorizontalDivider(color = Surface2)
            Text("Your fingerprint", color = White, style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(model.snapshot.identity.chunked(8).joinToString(" "), color = Soft, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = { manual = !manual }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (manual) "Hide manual pairing" else "Paste a code or pairing package") }
            if (manual) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Contact name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(packageText, { packageText = it.take(8192) }, label = { Text("Their code or pairing package") }, maxLines = 5, modifier = Modifier.fillMaxWidth())
                Text("Only confirm a code/package whose fingerprint you have checked with the other person. Sharing a package alone does not verify who sent it.", color = Soft, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { model.importPairing(packageText, name) }, enabled = packageText.isNotBlank() && !model.locked,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Confirm checked fingerprint") }
            }
            model.snapshot.contacts.filter { !it.ready }.forEach { contact ->
                HorizontalDivider(color = Surface2)
                Text(contact.name, color = White, style = MaterialTheme.typography.titleMedium)
                Text(contact.phase, color = Soft)
                OutlinedButton(onClick = { share(contact.pairingPackage) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Share pairing reply") }
            }
            InfoNote(model.status, accent = if (model.locked) Coral else Blue)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PairingQr(value: String) {
    val image = remember(value) {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q, EncodeHintType.MARGIN to 4))
        val pixels = IntArray(600 * 600) { index -> if (matrix[index % 600, index / 600]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        Bitmap.createBitmap(pixels, 600, 600, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(image, "Your Yap pairing code", Modifier.sizeIn(maxWidth = 248.dp, maxHeight = 248.dp).aspectRatio(1f).background(androidx.compose.ui.graphics.Color.White))
    }
}

@Composable
internal fun SecureConnectionsScreen(model: SecureModel, enableMesh: () -> Unit, legacy: () -> Unit, back: () -> Unit) {
    var url by remember(model.relayUrl) { mutableStateOf(model.relayUrl) }
    var credential by remember { mutableStateOf("") }
    var ca by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Connections", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DetailRow("Nearby messaging", "${model.localPeers} nearby phone(s) connected", Icons.Default.Hub)
            Text("Sends encrypted messages to nearby phones over Bluetooth and Wi-Fi while Yap is open. No internet, router or hotspot needed: phones in range find each other directly, and drop off when they move away. Phones on the same Wi-Fi or hotspot also link up. Queued messages survive restart.", color = Soft)
            Button(onClick = enableMesh, enabled = !model.locked && model.snapshot.identity.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (model.meshEnabled) "Restart nearby messaging" else "Turn on nearby messaging")
            }
            if (model.meshEnabled) OutlinedButton(onClick = model::disableMesh, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Pause nearby messaging") }
            if (Build.VERSION.SDK_INT < 29) InfoNote("This phone can use Wi-Fi only. Bluetooth nearby messaging needs Android 10 or later.")
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
            InfoNote(model.status, accent = if (model.locked) Coral else Blue)
            HorizontalDivider(color = Surface2)
            Text("Legacy Bluetooth test", color = White, style = MaterialTheme.typography.titleMedium)
            Text("The original 20-byte chat is unencrypted and separate from paired messaging.", color = Soft)
            OutlinedButton(onClick = legacy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open legacy Bluetooth controls") }
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
