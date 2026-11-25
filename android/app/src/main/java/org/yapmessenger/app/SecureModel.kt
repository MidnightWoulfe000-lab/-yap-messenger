package org.yapmessenger.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
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

/**
 * Messaging runtime. One instance per process ([get]), not tied to an Activity, so
 * nearby messaging can keep running in the background under [MeshService].
 */
internal class SecureModel(private val application: Application) {
    companion object {
        @Volatile private var instance: SecureModel? = null
        fun get(application: Application): SecureModel =
            instance ?: synchronized(this) { instance ?: SecureModel(application).also { instance = it } }
    }
    var snapshot by mutableStateOf(SecureSnapshot()); private set
    var status by mutableStateOf("Opening protected identity…"); private set
    var locked by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var meshEnabled by mutableStateOf(false); private set
    var localPeers by mutableStateOf(0); private set
    /** What carries each nearby link, so screens can say Bluetooth, Wi-Fi Aware or Wi-Fi Direct. */
    var localLinks by mutableStateOf(emptyList<MeshLink>()); private set
    var meshDiscoverable by mutableStateOf(false); private set
    var internetConnected by mutableStateOf(false); private set
    var relayUrl by mutableStateOf(""); private set
    var relayConfigured by mutableStateOf(false); private set
    var relayQueued by mutableIntStateOf(0); private set
    var draft by mutableStateOf("")
    var scannedQr by mutableStateOf("")
    /** True while we are asking nearby phones for the code we just scanned. */
    var searching by mutableStateOf(false); private set
    @Volatile private var publicState = SecureSnapshot()
    @Volatile private var foreground = false
    /** True while [MeshService] keeps nearby messaging running with no screen open. */
    @Volatile private var background = false
    private val active get() = foreground || background
    private var lastUnread = -1
    private val lifecycleLock = Any()
    @Volatile private var initialized = false
    private val closed = AtomicBoolean(false)
    private val pumping = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128))
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val repository = EndpointRepository(EncryptedEndpointStore(application))
    private val relayFile = File(application.noBackupFilesDir, "messaging/relay-v1.redb").absolutePath
    /** A scanned QR whose public bundle we are asking nearby phones for. */
    private data class WaitingPair(val qr: String, val name: String, val commitment: ByteArray, val until: Long)
    @Volatile private var waitingPair: WaitingPair? = null
    private var lastBundleRequest = 0L
    /**
     * Which nearby link belongs to which contact, learnt only from that contact's own
     * pairing traffic (they answered our bundle request, or sent a valid confirmation).
     * Keyed by contact route; the route is never sent over the link to find them.
     */
    private val pairingLinks = HashMap<String, MeshHub.Peer>()
    /**
     * Relay keys of envelopes this phone sent itself (worker thread only). Link IDs
     * rotate, so a relay can no longer tell that we were the source and may hand our
     * own message back. We then take it off their hands without storing a relay copy:
     * our outbox already keeps retrying it. Without this, messages could bounce back
     * to the sender and waste hops.
     */
    private val ownKeys = LinkedHashSet<String>()
    /** Per-link and phone-wide flood slow mode for nearby envelopes. See FloodGuard. */
    private val floodGuard = FloodGuard({ SystemClock.elapsedRealtime() })
    // Spray and wait (worker thread only). Copies left for our own envelopes and
    // their effort stamps, kept in memory: after a restart own envelopes start
    // again at 12 copies and are re-stamped. Offers record what we gave each link,
    // so an ACK subtracts the right number of copies.
    private val ownCopies = HashMap<String, Int>()
    private val ownStamps = HashMap<String, ByteArray>()
    private val offers = HashMap<String, Pair<Int, Long>>() // "link|key" -> (given, when)
    private var nextRelayAttempt = 0L
    private var relayFailures = 0
    private val radio = LocalMesh(application,
        report = { say(it) }, changed = { count -> main.post { localPeers = count } },
        visibility = { visible -> main.post { meshDiscoverable = visible } }, record = ::localRecord,
        links = { links -> main.post { localLinks = links } })
    private val internet = InternetRelay(
        status = { connected, message -> main.post { internetConnected = connected; status = message } },
        incoming = { bytes, key, acknowledge -> receive("internet-relay", bytes, key) { acknowledge() } },
        custody = { key -> acknowledged(key) })

    init {
        submit { openIdentity() }
        timer.scheduleAtFixedRate({
            if (active && initialized && !closed.get() && pumping.compareAndSet(false, true)) {
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
        // A new incoming message while no Yap screen is open: show a generic alert.
        // Never the sender or the text: phones can be seen by others.
        val unread = contacts.sumOf { it.unread }
        if (lastUnread >= 0 && unread > lastUnread && !foreground) MeshService.notifyNewMessage(application)
        lastUnread = unread
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
        if (settings.optBoolean("mesh") && foreground) MeshService.start(application)
        if (active) resumeConfigured()
    }
    fun reopen() { submit { openIdentity() } }
    fun setForeground(value: Boolean) {
        synchronized(lifecycleLock) {
            foreground = value
            if (value) MeshService.clearNewMessage(application)
            // With nearby messaging on, MeshService keeps everything running.
            if (!value && !background) { stopTransports(); return }
        }
        submit(silent = true) { if (initialized) resumeConfigured() }
    }
    /** Called by [MeshService] when it starts and stops. */
    fun setBackground(value: Boolean) {
        synchronized(lifecycleLock) {
            background = value
            if (!value && !foreground) { stopTransports(); return }
        }
        if (value) submit(silent = true) { if (initialized) resumeConfigured() }
    }
    private fun stopTransports() { radio.stop(); internet.stop(); main.post { localPeers = 0; localLinks = emptyList(); internetConnected = false } }
    private fun resumeConfigured() = synchronized(lifecycleLock) {
        if (!active) return@synchronized
        if (repository.settings().optBoolean("mesh")) radio.start(bluetoothAllowed = permissions(), proximityWifiAllowed = proximityWifiPermission())
        nextRelayAttempt = 0
    }
    private fun permissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return required.all { application.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    private fun proximityWifiPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return application.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
    fun enableMesh() { submit {
        val settings = repository.settings().put("mesh", true); repository.saveSettings(settings)
        main.post { meshEnabled = true }; synchronized(lifecycleLock) { if (active) { radio.stop(); radio.start(bluetoothAllowed = permissions(), proximityWifiAllowed = proximityWifiPermission()) } }
        if (foreground) MeshService.start(application)
    } }
    fun disableMesh() {
        radio.stop(); MeshService.stop(application)
        submit { repository.saveSettings(repository.settings().put("mesh", false)); main.post { meshEnabled = false } }
    }
    // Offers are no longer broadcast to every nearby phone: a bundle contains the
    // permanent identity key. It is only sent to a phone that proves it scanned our QR.
    fun makeOffer() { submit { call("offer"); say("Fresh pairing code created. Both people must scan and confirm.") } }
    /** Swap the on-screen code for a fresh one. Never replaces a code a friend has already scanned. */
    fun rotateOffer() { submit(silent = true) { if (initialized && publicState.offer?.reserved != true) call("offer") } }
    fun ensureOffer() { submit { if (initialized && publicState.offer == null) call("offer") } }
    fun importPairing(text: String, name: String) {
        if (text.length > 8192) { say("Pairing package exceeds its limit"); return }
        submit {
            val packet = if (text.trim().startsWith("{")) JSONObject(text) else JSONObject().put("qr", text.trim())
            val qr = packet.getString("qr")
            val full = packet.optString("bundle").takeIf { it.isNotEmpty() }
            if (full == null) {
                waitingPair = WaitingPair(qr, name, qrCommitment(qr), SystemClock.elapsedRealtime() + 10 * 60_000L)
                main.post { searching = true }
                lastBundleRequest = 0; requestBundle()
                say("Looking for that phone nearby. Keep both phones close with nearby messaging on.")
                return@submit
            }
            pair(qr, full, name, packet.optString("confirmation").takeIf { it.isNotEmpty() })
        }
    }
    private fun myProfileName(): String =
        application.getSharedPreferences("yap_profile", android.content.Context.MODE_PRIVATE).getString("name", "").orEmpty().trim()
    private fun qrCommitment(qr: String): ByteArray {
        require(qr.length <= 256 && qr.startsWith("yap:v1:")) { "Not a Yap pairing code" }
        val bytes = decodeToken(qr.removePrefix("yap:v1:")); require(bytes.size == 101)
        return bytes.copyOfRange(53, 85)
    }
    private fun bundleCommitment(bundle: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("Yap public bundle v1\u0000".toByteArray() + bundle)
    /** Ask every nearby link for the bundle behind the scanned QR. Only its owner can answer. */
    private fun requestBundle() {
        val waiting = waitingPair ?: return
        if (SystemClock.elapsedRealtime() > waiting.until) { waitingPair = null; main.post { searching = false }; say("Could not find that phone nearby. Create a fresh code and try again."); return }
        if (SystemClock.elapsedRealtime() - lastBundleRequest < 5_000) return
        lastBundleRequest = SystemClock.elapsedRealtime()
        radio.connections().forEach { it.send(MeshFrames.BUNDLE_REQUEST, waiting.commitment, "bundle-request:" + encodeToken(waiting.commitment)) }
    }
    /** Bundle layout: "YPB1"(4) + identity(33) + nonce(16) + route(16) ... */
    private fun bundleRoute(bundle: ByteArray): String = encodeToken(bundle.copyOfRange(53, 69))
    private fun linkFor(route: String?): MeshHub.Peer? = synchronized(pairingLinks) {
        val peer = route?.let { pairingLinks[it] } ?: return null
        if (peer.isClosed) { pairingLinks.remove(route); null } else peer
    }
    private fun rememberLink(route: String, peer: MeshHub.Peer) = synchronized(pairingLinks) {
        pairingLinks.entries.removeAll { it.value.isClosed }
        if (pairingLinks.size < 64) pairingLinks[route] = peer
    }
    private fun pair(qr: String, full: String, name: String, confirmation: String?) {
        check(EndpointAdmission.pairing.allow(1883)) { "Pairing verification rate limit; retry in a minute" }
        val data = call("pair", JSONObject().put("qr", qr).put("bundle", full).put("name", name))
        if (confirmation != null) { check(EndpointAdmission.pairing.allow(101)) { "Pairing verification rate limit; retry in a minute" }; call("confirm", JSONObject().put("bytes", confirmation)) }
        data.optString("confirmation").takeIf { it.isNotEmpty() }?.let { proof ->
            val contact = publicState.contacts.firstOrNull { it.id == data.optString("contact") }
            linkFor(contact?.route)?.send(MeshFrames.CONFIRM, decodeToken(proof))
        }
        waitingPair = null; main.post { searching = false }; say("Contact saved. Exchange pairing replies or keep both phones connected until verification completes.")
    }
    fun send(contact: String) {
        if (busy) return
        val body = draft; busy = true
        if (!submit {
            try { call("send", JSONObject().put("contact", contact).put("text", body)); main.post { if (draft == body) draft = "" }; say("Encrypted message saved to the outgoing queue") }
            finally { main.post { busy = false } }
        }) busy = false
    }
    fun retry() { submit { if (active) pump(force = true); else say("Open Yap to retry saved messages") } }
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
    /**
     * [ack] gets true when this phone is the recipient (send DELIVERED) and false
     * when it took the envelope as a carrier (send ACK). With [copies] == 1 a phone
     * that is not the recipient declines, which is the "wait" half of spray and wait.
     */
    private fun receive(source: String, bytes: ByteArray, expectedKey: String?, copies: Int = 1, stamp: ByteArray? = null, ack: (Boolean) -> Unit) {
        if (!active || !initialized || bytes.size !in 74..65_536 || !EndpointAdmission.packet(source, bytes.size)) return
        submit(silent = true) {
            if (!active || !initialized) return@submit
            val local = decodeToken(publicState.route)
            val mine = bytes.copyOfRange(20, 36).contentEquals(local)
            if (!mine && source != "internet-relay" && encodeToken(envelopeKey(bytes)) in ownKeys) {
                ack(false); return@submit // our own envelope came back: take it off their hands
            }
            if (!mine && source != "internet-relay" && copies <= 1) return@submit
            val data = if (mine) call("receive", JSONObject().put("bytes", encodeToken(bytes)))
                else relay("relay_admit", JSONObject().put("bytes", encodeToken(bytes)).put("peer", sourceToken(source))
                    .put("copies", copies).apply { stamp?.let { put("stamp", encodeToken(it)) } })
            val key = data.optString("custody")
            if (key.isNotEmpty() && (expectedKey == null || key == expectedKey)) ack(mine)
        }
    }
    /** Offer an envelope on a link with half our copies (or 1 = recipient only). */
    private fun offer(peer: MeshHub.Peer, raw: ByteArray, key: String, copies: Int, stamp: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        val slot = peer.token + "|" + key
        if (offers[slot]?.let { now - it.second < 60_000 } == true) return // offered recently on this link
        if (offers.size > 8192) offers.entries.removeAll { now - it.value.second >= 60_000 }
        val give = Spray.give(copies)
        offers[slot] = give to now
        peer.send(MeshFrames.ENVELOPE, Spray.encode(give, stamp, raw), key)
    }
    private fun handedOff(peer: MeshHub.Peer, key: String, delivered: Boolean) { submit(silent = true) {
        val given = offers.remove(peer.token + "|" + key)?.first ?: 1
        if (key in ownKeys) {
            call("ack", JSONObject().put("key", key)) // status only; the outbox keeps it until a receipt
            if (delivered) ownCopies[key] = 1 else ownCopies[key] = ((ownCopies[key] ?: Spray.MAX_COPIES) - given).coerceAtLeast(1)
        } else if (delivered) relay("relay_ack", JSONObject().put("key", key))
        else relay("relay_handoff", JSONObject().put("key", key).put("given", given))
    } }
    private fun sourceToken(source: String): String = if (source == "internet-relay") encodeToken(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).copyOf(16)) else source
    private fun localRecord(peer: MeshHub.Peer, type: Int, bytes: ByteArray) {
        if (!active || !initialized) return
        when (type) {
            MeshFrames.BUNDLE_REQUEST -> {
                if (!EndpointAdmission.pairing.allow(bytes.size)) return
                submit(silent = true) {
                    // Answer only if the commitment matches OUR current QR. Strangers who
                    // never saw the QR cannot obtain the bundle (and its identity key).
                    val offer = publicState.offer ?: return@submit
                    if (offer.expires <= System.currentTimeMillis()) return@submit
                    val bundle = decodeToken(offer.bundle)
                    // The reply carries our profile name after the bundle, so the friend sees who we are.
                    if (MessageDigest.isEqual(bundleCommitment(bundle), bytes)) peer.send(MeshFrames.BUNDLE, bundle + myProfileName().toByteArray(Charsets.UTF_8).take(MeshFrames.MAX_NAME).toByteArray())
                }
            }
            MeshFrames.BUNDLE -> {
                if (!EndpointAdmission.packet(peer.token, bytes.size)) return
                submit(silent = true) {
                    // Unsolicited bundles are ignored; only the one we asked for is used.
                    val waiting = waitingPair ?: return@submit
                    val bundle = bytes.copyOf(1883)
                    if (!MessageDigest.isEqual(bundleCommitment(bundle), waiting.commitment)) return@submit
                    // Anything after the bundle is the sender's display name. It is not signed,
                    // so it is only a label; the fingerprint is what both people verify.
                    val theirName = String(bytes, 1883, bytes.size - 1883, Charsets.UTF_8).filter { !it.isISOControl() }.trim().take(30)
                    rememberLink(bundleRoute(bundle), peer)
                    pair(waiting.qr, encodeToken(bundle), waiting.name.ifBlank { theirName }, null)
                }
            }
            MeshFrames.CONFIRM -> {
                if (!EndpointAdmission.pairing.allow(bytes.size)) return
                submit(silent = true) {
                    val data = call("confirm", JSONObject().put("bytes", encodeToken(bytes)))
                    // A valid confirmation is signed by the contact's identity key, so this
                    // link now provably belongs to them for the rest of pairing.
                    val id = MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(4, 37)).joinToString("") { "%02x".format(it) }
                    publicState.contacts.firstOrNull { it.id == id }?.let { rememberLink(it.route, peer) }
                    data.optString("confirmation").takeIf { it.isNotEmpty() && it != "null" }?.let { peer.send(MeshFrames.CONFIRM, decodeToken(it)) }
                }
            }
            MeshFrames.ENVELOPE -> {
                val frame = Spray.decode(bytes) ?: return
                // One hash: junk without a valid effort stamp is dropped before anything else.
                val key = envelopeKey(frame.envelope)
                if (!EffortStamp.valid(key, frame.stamp)) return
                if (!floodGuard.allow(peer.token)) return
                receive(peer.token, frame.envelope, null, frame.copies, frame.stamp) { delivered ->
                    // Only after the endpoint snapshot or relay DB commit succeeds.
                    peer.send(if (delivered) MeshFrames.DELIVERED else MeshFrames.ACK, key)
                }
            }
            MeshFrames.ACK, MeshFrames.DELIVERED -> {
                val key = encodeToken(bytes)
                if (peer.consumeAck(key)) handedOff(peer, key, type == MeshFrames.DELIVERED)
            }
        }
    }
    private fun envelopeKey(raw: ByteArray): ByteArray {
        require(raw.size >= 74)
        val normalized = raw.clone(); normalized[68] = 0
        val hash = MessageDigest.getInstance("SHA-256").digest("Yap relay envelope v1\u0000".toByteArray() + normalized)
        return raw.copyOfRange(4, 20) + hash
    }
    private fun pump(force: Boolean = false) {
        if (!active || !initialized || repository.locked) return
        val settings = repository.settings()
        val url = settings.optString("relay_url")
        if (url.isNotEmpty() && !internet.connected && SystemClock.elapsedRealtime() >= nextRelayAttempt) {
            nextRelayAttempt = SystemClock.elapsedRealtime() + (5_000L shl relayFailures.coerceAtMost(4)).coerceAtMost(60_000)
            relayFailures++; synchronized(lifecycleLock) { if (active) internet.connect(url, settings.getString("relay_credential"), settings.optString("relay_ca")) }
        }
        if (internet.connected) { relayFailures = 0; internet.poll() }
        val local = radio.connections()
        if (local.isNotEmpty()) requestBundle()
        if (local.isEmpty() && !internet.connected) return
        if (publicState.queued > 0 || publicState.contacts.any { !it.ready && !it.phase.startsWith("Pairing expired") }) {
            val packets = call("pending", JSONObject().put("force", force)).optJSONArray("packets")
            if (packets != null) for (i in 0 until packets.length()) {
                val packet = packets.getJSONObject(i); val raw = decodeToken(packet.getString("bytes"))
                if (packet.getString("kind") == "confirmation") linkFor(packet.getString("destination"))?.send(MeshFrames.CONFIRM, raw)
                else {
                    val key = packet.getString("key")
                    ownKeys.remove(key); ownKeys.add(key)
                    while (ownKeys.size > 2048) ownKeys.first().let { ownKeys.remove(it); ownCopies.remove(it); ownStamps.remove(it) }
                    if (local.isNotEmpty()) {
                        val stamp = ownStamps.getOrPut(key) { EffortStamp.make(decodeToken(key)) } // ~0.3–1 s, once per envelope
                        val copies = ownCopies.getOrPut(key) { Spray.MAX_COPIES }
                        local.forEach { offer(it, raw, key, copies, stamp) }
                    }
                    if (internet.connected) internet.offer(raw, key)
                }
            }
        }
        local.forEach { peer ->
            // Ask for more than we send so envelopes waiting for their recipient
            // (one copy left) cannot block the rest of the queue on this link.
            val pending = relay("relay_pending", JSONObject().put("peer", peer.token).put("limit", 128)).getJSONArray("packets")
            var sent = 0
            for (i in 0 until pending.length()) {
                if (sent >= 16) break
                val item = pending.getJSONObject(i)
                val stamp = item.optString("stamp").takeIf { it.isNotEmpty() && it != "null" }?.let(::decodeToken) ?: continue
                val key = item.getString("key")
                if (offers[peer.token + "|" + key]?.let { SystemClock.elapsedRealtime() - it.second < 60_000 } == true) continue
                offer(peer, decodeToken(item.getString("bytes")), key, item.optInt("copies", 1), stamp); sent++
            }
        }
        if (internet.connected) {
            val pending = relay("relay_pending", JSONObject().put("peer", sourceToken("internet-relay"))).getJSONArray("packets")
            for (i in 0 until pending.length()) pending.getJSONObject(i).let { internet.offer(decodeToken(it.getString("bytes")), it.getString("key")) }
        }
        val count = relay("relay_count").optInt("queued"); main.post { relayQueued = count }
    }
    /** Only for tests that create their own instance; the process-wide one lives until the process ends. */
    fun close() {
        closed.set(true); foreground = false; background = false; radio.close(); internet.close(); timer.shutdownNow(); worker.shutdown()
    }
}
