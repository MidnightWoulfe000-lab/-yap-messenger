package org.yapmessenger.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class SecureContact(val id: String, val name: String, val ready: Boolean, val phase: String, val fingerprint: String, val route: String, val pairingPackage: String, val unread: Int = 0)
internal data class PairOffer(val qr: String, val bundle: String, val pairingPackage: String, val expires: Long, val reserved: Boolean)
internal data class SecureHistory(val contact: String, val message: ChatMessage)
internal data class SecureSnapshot(val identity: String = "", val route: String = "", val offer: PairOffer? = null,
    val contacts: List<SecureContact> = emptyList(), val history: List<SecureHistory> = emptyList(), val queued: Int = 0)

/** Admission happens BEFORE enqueueing potentially expensive JNI work. */
internal class AdmissionGate(private val maximum: Int, private val maximumBytes: Int, private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    private var start = elapsed(); private var count = 0; private var bytes = 0
    @Synchronized fun allow(size: Int): Boolean {
        val now = elapsed()
        if (now - start >= 60_000) { start = now; count = 0; bytes = 0 }
        if (size < 0 || count >= maximum || size > maximumBytes - bytes) return false
        count++; bytes += size; return true
    }
}
private object EndpointAdmission {
    val crypto = AdmissionGate(120, 2 * 1024 * 1024)
    val pairing = AdmissionGate(8, 32 * 1024)
    private val sources = LinkedHashMap<String, Pair<Long, AdmissionGate>>()
    @Synchronized fun packet(source: String, size: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        sources.entries.removeAll { now - it.value.first >= 60_000 }
        if (!sources.containsKey(source) && sources.size >= 16) return false
        val entry = sources.getOrPut(source) { now to AdmissionGate(60, 256 * 1024) }
        return entry.second.allow(size) && crypto.allow(size)
    }
}

internal class SecureModel(application: Application) : AndroidViewModel(application) {
    var snapshot by mutableStateOf(SecureSnapshot()); private set
    var status by mutableStateOf("Opening protected identity…"); private set
    var locked by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var meshEnabled by mutableStateOf(false); private set
    var localPeers by mutableStateOf(0); private set
    var meshDiscoverable by mutableStateOf(false); private set
    var internetConnected by mutableStateOf(false); private set
    var relayUrl by mutableStateOf(""); private set
    var relayConfigured by mutableStateOf(false); private set
    var relayQueued by mutableIntStateOf(0); private set
    var draft by mutableStateOf("")
    var scannedQr by mutableStateOf("")
    @Volatile private var publicState = SecureSnapshot()
    @Volatile private var foreground = false
    private val lifecycleLock = Any()
    @Volatile private var initialized = false
    private val closed = AtomicBoolean(false)
    private val pumping = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128))
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val repository = EndpointRepository(EncryptedEndpointStore(application))
    private val relayFile = File(application.noBackupFilesDir, "messaging/relay-v1.redb").absolutePath
    private val bundleCache = LinkedHashMap<String, String>()
    private var waitingQr: Pair<String, String>? = null
    private var nextRelayAttempt = 0L
    private var relayFailures = 0
    private val radio = MeshBluetooth(application,
        route = { publicState.route.takeIf { it.isNotEmpty() }?.let(::decodeToken) ?: ByteArray(16) },
        bundle = { publicState.offer?.bundle?.let(::decodeToken) },
        report = { say(it) }, changed = { count -> main.post { localPeers = count } },
        visibility = { visible -> main.post { meshDiscoverable = visible } }, record = ::localRecord)
    private val internet = InternetRelay(
        status = { connected, message -> main.post { internetConnected = connected; status = message } },
        incoming = { bytes, key, acknowledge -> receive("internet-relay", bytes, key, acknowledge) },
        custody = { key -> acknowledged(key) })

    init {
        submit { openIdentity() }
        timer.scheduleAtFixedRate({
            if (foreground && initialized && !closed.get() && pumping.compareAndSet(false, true)) {
                if (!submit(silent = true) { try { pump() } finally { pumping.set(false) } }) pumping.set(false)
            }
        }, 2, 3, TimeUnit.SECONDS)
    }
    private fun say(message: String) { main.post { if (!closed.get()) status = message.take(240) } }
    private fun submit(silent: Boolean = false, action: () -> Unit): Boolean = try {
        worker.execute {
            if (!closed.get()) try { action() } catch (error: Exception) {
                if (repository.locked) {
                    initialized = false; radio.stop(); internet.stop()
                    main.post { locked = true; status = "Protected identity could not be committed/opened. Retry opening it; no send was published." }
                } else if (!silent) say(error.message ?: "Operation could not be completed")
            } catch (_: LinkageError) {
                initialized = false; main.post { locked = true; status = "Native messaging could not load on this device" }
            }
        }; true
    } catch (_: java.util.concurrent.RejectedExecutionException) { if (!silent) say("Messaging is busy; retry shortly"); false }
    private fun publish(result: JSONObject) {
        val view = result.getJSONObject("view")
        val contacts = view.getJSONArray("contacts").let { items -> (0 until items.length()).map { i -> items.getJSONObject(i).let {
            SecureContact(it.getString("id"), it.getString("name"), it.getBoolean("ready"), it.getString("phase"), it.getString("fingerprint"), it.getString("route"), it.getString("package"), it.optInt("unread"))
        } } }
        val history = view.getJSONArray("messages").let { items -> (0 until items.length()).map { i -> items.getJSONObject(i).let {
            val receipt = if (it.isNull("receipt_at")) null else it.getLong("receipt_at")
            val ack = if (it.isNull("acknowledged_at")) null else it.getLong("acknowledged_at")
            val state = it.getString("status")
            SecureHistory(it.getString("contact"), ChatMessage(it.getBoolean("mine"), it.getString("body"), state,
                it.getLong("ui_id"), it.getLong("created"), ack,
                failureReason = state.takeIf { s -> s.startsWith("Expired") || s.startsWith("Session retired") }, recipientReceiptAt = receipt, secure = true))
        } } }
        val offer = view.optJSONObject("offer")?.let { PairOffer(it.getString("qr"), it.getString("bundle"), it.getString("package"), it.getLong("expires"), it.getBoolean("reserved")) }
        val next = SecureSnapshot(view.getString("identity"), view.getString("route"), offer, contacts, history, view.getInt("queued"))
        publicState = next
        main.post { if (!closed.get()) { snapshot = next; locked = false } }
    }
    private fun call(operation: String, args: JSONObject = JSONObject()): JSONObject {
        check(initialized || operation == "init" || operation == "view") { "Wait for protected identity to open" }
        val result = repository.call(operation, args); publish(result); return result.optJSONObject("data") ?: JSONObject()
    }
    private fun openIdentity() {
        val result = repository.open(); initialized = true; publish(result)
        val settings = repository.settings()
        main.post { meshEnabled = settings.optBoolean("mesh"); relayUrl = settings.optString("relay_url"); relayConfigured = settings.optString("relay_url").isNotEmpty(); locked = false }
        say("Identity ready · experimental debug build")
        if (foreground) resumeConfigured()
    }
    fun reopen() { submit { openIdentity() } }
    fun setForeground(value: Boolean) {
        synchronized(lifecycleLock) {
            foreground = value
            if (!value) { radio.stop(); internet.stop(); main.post { localPeers = 0; internetConnected = false }; return }
        }
        submit(silent = true) { if (initialized) resumeConfigured() }
    }
    private fun resumeConfigured() = synchronized(lifecycleLock) {
        if (!foreground) return@synchronized
        if (repository.settings().optBoolean("mesh") && permissions()) radio.start()
        nextRelayAttempt = 0
    }
    private fun permissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return required.all { getApplication<Application>().checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    fun enableMesh() { submit {
        check(Build.VERSION.SDK_INT >= 29) { "Local mesh needs Android 10 or later. Legacy Bluetooth and internet remain available." }
        val settings = repository.settings().put("mesh", true); repository.saveSettings(settings)
        main.post { meshEnabled = true }; synchronized(lifecycleLock) { if (foreground) { radio.stop(); radio.start() } }
    } }
    fun disableMesh() { radio.stop(); submit { repository.saveSettings(repository.settings().put("mesh", false)); main.post { meshEnabled = false } } }
    fun makeOffer() { submit { call("offer"); radio.broadcastBundle(); say("Fresh pairing code created. Both people must scan and confirm.") } }
    fun ensureOffer() { submit { if (initialized && publicState.offer == null) { call("offer"); radio.broadcastBundle() } } }
    fun importPairing(text: String, name: String) {
        if (text.length > 8192) { say("Pairing package exceeds its limit"); return }
        submit {
            val packet = if (text.trim().startsWith("{")) JSONObject(text) else JSONObject().put("qr", text.trim())
            val qr = packet.getString("qr")
            val full = packet.optString("bundle").takeIf { it.isNotEmpty() } ?: cachedBundle(qr)
            if (full == null) { waitingQr = qr to name; say("Waiting for this phone's public bundle. Enable local mesh on both phones, or exchange pairing packages."); return@submit }
            pair(qr, full, name, packet.optString("confirmation").takeIf { it.isNotEmpty() })
        }
    }
    private fun cachedBundle(qr: String): String? {
        require(qr.length <= 256 && qr.startsWith("yap:v1:")) { "Not a Yap pairing code" }
        val bytes = decodeToken(qr.removePrefix("yap:v1:")); require(bytes.size == 101)
        return bundleCache[encodeToken(bytes.copyOfRange(53, 85))]
    }
    private fun pair(qr: String, full: String, name: String, confirmation: String?) {
        check(EndpointAdmission.pairing.allow(1883)) { "Pairing verification rate limit; retry in a minute" }
        val data = call("pair", JSONObject().put("qr", qr).put("bundle", full).put("name", name))
        if (confirmation != null) { check(EndpointAdmission.pairing.allow(101)) { "Pairing verification rate limit; retry in a minute" }; call("confirm", JSONObject().put("bytes", confirmation)) }
        data.optString("confirmation").takeIf { it.isNotEmpty() }?.let { proof ->
            val contact = publicState.contacts.firstOrNull { it.id == data.optString("contact") }
            radio.connections().filter { it.token == contact?.route }.forEach { it.send(MeshBluetooth.CONFIRM, decodeToken(proof)) }
        }
        waitingQr = null; say("Contact saved. Exchange pairing replies or keep both phones connected until verification completes.")
    }
    fun send(contact: String) {
        if (busy) return
        val body = draft; busy = true
        if (!submit {
            try { call("send", JSONObject().put("contact", contact).put("text", body)); main.post { if (draft == body) draft = "" }; say("Encrypted message saved to the outgoing queue") }
            finally { main.post { busy = false } }
        }) busy = false
    }
    fun retry() { submit { if (foreground) pump(force = true); else say("Open Yap to retry saved messages") } }
    fun retire(contact: String) { submit { call("retire", JSONObject().put("contact", contact)); say("Old session retired. Existing relay copies cannot be recalled.") } }
    fun clearHistory() { submit { call("clear_history"); say("Visible encrypted history cleared. Queued ciphertext and replay protection remain.") } }
    fun markRead(contact: String) { submit(silent = true) { call("mark_read", JSONObject().put("contact", contact)) } }
    fun saveRelay(url: String, credential: String, ca: String = "") { submit {
        val settings = repository.settings()
        val secret = credential.ifBlank { if (settings.optString("relay_url") == url.trim()) settings.optString("relay_credential") else "" }
        val certificate = ca.ifBlank { if (settings.optString("relay_url") == url.trim()) settings.optString("relay_ca") else "" }
        InternetRelay.validate(url.trim(), secret, certificate)
        settings.put("relay_url", url.trim()).put("relay_credential", secret).put("relay_ca", certificate); repository.saveSettings(settings)
        internet.stop(); nextRelayAttempt = 0; relayFailures = 0
        main.post { relayUrl = url.trim(); relayConfigured = true }; say("Relay configuration protected and saved")
    } }
    fun removeRelay() { internet.stop(); submit {
        val settings = repository.settings(); settings.remove("relay_url"); settings.remove("relay_credential"); settings.remove("relay_ca"); repository.saveSettings(settings)
        main.post { relayUrl = ""; relayConfigured = false; internetConnected = false }; say("Internet relay disabled")
    } }
    private fun relay(operation: String, args: JSONObject = JSONObject()): JSONObject = repository.relay(operation, args.put("path", relayFile))
    private fun acknowledged(key: String) { submit(silent = true) {
        call("ack", JSONObject().put("key", key)); relay("relay_ack", JSONObject().put("key", key))
    } }
    private fun receive(source: String, bytes: ByteArray, expectedKey: String?, ack: () -> Unit) {
        if (!foreground || !initialized || bytes.size !in 74..65_536 || !EndpointAdmission.packet(source, bytes.size)) return
        submit(silent = true) {
            if (!foreground || !initialized) return@submit
            val local = decodeToken(publicState.route)
            val data = if (bytes.copyOfRange(20, 36).contentEquals(local)) call("receive", JSONObject().put("bytes", encodeToken(bytes)))
                else relay("relay_admit", JSONObject().put("bytes", encodeToken(bytes)).put("peer", sourceToken(source)))
            val key = data.optString("custody")
            if (key.isNotEmpty() && (expectedKey == null || key == expectedKey)) ack()
        }
    }
    private fun sourceToken(source: String): String = if (source == "internet-relay") encodeToken(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).copyOf(16)) else source
    private fun localRecord(peer: MeshBluetooth.Peer, type: Int, bytes: ByteArray) {
        if (!foreground || !initialized) return
        when (type) {
            MeshBluetooth.BUNDLE -> {
                if (!EndpointAdmission.packet(peer.token, bytes.size)) return
                submit(silent = true) {
                    val hash = MessageDigest.getInstance("SHA-256").digest("Yap public bundle v1\u0000".toByteArray() + bytes)
                    while (bundleCache.size >= 32) bundleCache.remove(bundleCache.keys.first())
                    bundleCache[encodeToken(hash)] = encodeToken(bytes)
                    waitingQr?.let { (qr, name) -> cachedBundle(qr)?.let { pair(qr, it, name, null) } }
                }
            }
            MeshBluetooth.CONFIRM -> {
                if (!EndpointAdmission.pairing.allow(bytes.size)) return
                submit(silent = true) {
                    val data = call("confirm", JSONObject().put("bytes", encodeToken(bytes)))
                    data.optString("confirmation").takeIf { it.isNotEmpty() && it != "null" }?.let { peer.send(MeshBluetooth.CONFIRM, decodeToken(it)) }
                }
            }
            MeshBluetooth.ENVELOPE -> receive(peer.token, bytes, null) {
                // Only after the endpoint snapshot or relay DB commit succeeds.
                val key = envelopeKey(bytes); peer.send(MeshBluetooth.ACK, key)
            }
            MeshBluetooth.ACK -> { val key = encodeToken(bytes); if (peer.consumeAck(key)) acknowledged(key) }
        }
    }
    private fun envelopeKey(raw: ByteArray): ByteArray {
        require(raw.size >= 74)
        val normalized = raw.clone(); normalized[68] = 0
        val hash = MessageDigest.getInstance("SHA-256").digest("Yap relay envelope v1\u0000".toByteArray() + normalized)
        return raw.copyOfRange(4, 20) + hash
    }
    private fun pump(force: Boolean = false) {
        if (!foreground || !initialized || repository.locked) return
        val settings = repository.settings()
        val url = settings.optString("relay_url")
        if (url.isNotEmpty() && !internet.connected && SystemClock.elapsedRealtime() >= nextRelayAttempt) {
            nextRelayAttempt = SystemClock.elapsedRealtime() + (5_000L shl relayFailures.coerceAtMost(4)).coerceAtMost(60_000)
            relayFailures++; synchronized(lifecycleLock) { if (foreground) internet.connect(url, settings.getString("relay_credential"), settings.optString("relay_ca")) }
        }
        if (internet.connected) { relayFailures = 0; internet.poll() }
        val local = radio.connections()
        if (local.isEmpty() && !internet.connected) return
        if (publicState.queued > 0 || publicState.contacts.any { !it.ready && !it.phase.startsWith("Pairing expired") }) {
            val packets = call("pending", JSONObject().put("force", force)).optJSONArray("packets")
            if (packets != null) for (i in 0 until packets.length()) {
                val packet = packets.getJSONObject(i); val raw = decodeToken(packet.getString("bytes"))
                if (packet.getString("kind") == "confirmation") local.filter { it.token == packet.getString("destination") }.forEach { it.send(MeshBluetooth.CONFIRM, raw) }
                else {
                    val key = packet.getString("key")
                    local.forEach { it.send(MeshBluetooth.ENVELOPE, raw, key) }
                    if (internet.connected) internet.offer(raw, key)
                }
            }
        }
        local.forEach { peer ->
            val pending = relay("relay_pending", JSONObject().put("peer", peer.token)).getJSONArray("packets")
            for (i in 0 until pending.length()) pending.getJSONObject(i).let { peer.send(MeshBluetooth.ENVELOPE, decodeToken(it.getString("bytes")), it.getString("key")) }
        }
        if (internet.connected) {
            val pending = relay("relay_pending", JSONObject().put("peer", sourceToken("internet-relay"))).getJSONArray("packets")
            for (i in 0 until pending.length()) pending.getJSONObject(i).let { internet.offer(decodeToken(it.getString("bytes")), it.getString("key")) }
        }
        val count = relay("relay_count").optInt("queued"); main.post { relayQueued = count }
    }
    override fun onCleared() {
        closed.set(true); foreground = false; radio.close(); internet.close(); timer.shutdownNow(); worker.shutdown()
        super.onCleared()
    }
}
