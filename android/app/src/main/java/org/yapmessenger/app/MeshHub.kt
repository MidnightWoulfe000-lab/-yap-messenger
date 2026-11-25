package org.yapmessenger.app

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local mesh link layer (v2), shared by every nearby transport (Bluetooth, Wi-Fi).
 *
 * Privacy rule: nothing sent before or during link setup may be permanent.
 * A link is identified only by a random [LinkIdentity] that changes on every start
 * and every 10–20 minutes. The messaging route token (the long-lived mailbox ID)
 * and identity keys are never used to discover or name a link.
 * See docs/proposals/local-mesh-v2.md.
 *
 * This class has no Android dependencies so it can be unit-tested on the JVM.
 */
internal object MeshFrames {
    const val HELLO = 1; const val BUNDLE = 2; const val CONFIRM = 3
    const val ENVELOPE = 4; const val ACK = 5; const val KEEPALIVE = 6
    /** Ask nearby phones for the public pairing bundle matching a scanned QR commitment. */
    const val BUNDLE_REQUEST = 7
    /** "I am the recipient and have it": the carrier can drop its copy entirely. */
    const val DELIVERED = 8
    const val MAX_FRAME = 65_537
    /** A bundle may be followed by the sender's display name (UTF-8, at most 30 characters). Display only. */
    const val MAX_NAME = 120
    const val ID_BYTES = 16
    const val NONCE_BYTES = 8
    const val HELLO_BYTES = ID_BYTES + NONCE_BYTES
}

internal enum class MeshTransport { BLUETOOTH, WIFI }

internal fun meshToken(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** Unsigned lexicographic comparison of equal-length byte arrays. */
internal fun compareBytes(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val d = (a[i].toInt() and 255) - (b[i].toInt() and 255)
        if (d != 0) return d
    }
    return a.size - b.size
}

/**
 * Random, short-lived link ID. Rotates on [start] and after a jittered period.
 * The previous ID is remembered briefly so our own lingering adverts are not
 * mistaken for another phone.
 */
internal class LinkIdentity(
    private val elapsed: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    private val minimumMs: Long = 10 * 60_000L,
    private val jitterMs: Long = 10 * 60_000L
) {
    @Volatile private var id = fresh()
    @Volatile private var previous: ByteArray? = null
    @Volatile private var rotateAt = 0L
    private fun fresh() = ByteArray(MeshFrames.ID_BYTES).also(random::nextBytes)
    private fun schedule() { rotateAt = elapsed() + minimumMs + (random.nextDouble() * jitterMs).toLong() }
    init { schedule() }
    fun current(): ByteArray = id.clone()
    @Synchronized fun rotate() { previous = id; id = fresh(); schedule() }
    /** Returns true when the ID changed. */
    @Synchronized fun rotateIfDue(): Boolean { if (elapsed() < rotateAt) return false; rotate(); return true }
    fun isOwn(candidate: ByteArray): Boolean = candidate.contentEquals(id) || previous?.contentEquals(candidate) == true
}

/** A connected byte stream from any transport. Closing it must unblock reads. */
internal class MeshStream(val input: InputStream, val output: OutputStream, private val closer: Closeable, val remoteAddress: String? = null) : Closeable {
    override fun close() = closer.close()
}

internal class MeshHub(
    private val report: (String) -> Unit,
    private val changed: (Int) -> Unit,
    private val record: (Peer, Int, ByteArray) -> Unit,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    val identity: LinkIdentity = LinkIdentity(elapsed),
    private val maximumLinks: Int = 12,
    private val helloTimeoutMs: Long = 12_000,
    private val idleTimeoutMs: Long = 25_000,
    private val keepaliveMs: Long = 12_000,
    private val frameLimit: () -> AdmissionGate = { AdmissionGate(240, 1024 * 1024, elapsed) }
) {
    private val random = SecureRandom()
    private val readers = Executors.newCachedThreadPool { task -> Thread(task, "yap-mesh-reader").apply { isDaemon = true } }
    private val writes = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128)) { task -> Thread(task, "yap-mesh-writer").apply { isDaemon = true } }
    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "yap-mesh-timer").apply { isDaemon = true } }
    private val peers = HashMap<String, Peer>()
    private var slots = 0
    @Volatile private var running = false
    @Volatile private var closedForever = false
    private val rotationListeners = mutableListOf<() -> Unit>()

    init { timers.scheduleWithFixedDelay({ runCatching { watch() } }, 5, 5, TimeUnit.SECONDS) }

    /** Transports call this so they can refresh adverts (for example the Wi-Fi service name). */
    fun onRotate(listener: () -> Unit) { synchronized(rotationListeners) { rotationListeners += listener } }

    fun start() { if (closedForever) return; identity.rotate(); running = true }
    fun stop() { running = false; peersSnapshot().forEach { it.close() }; changed(0) }
    fun close() { closedForever = true; stop(); timers.shutdownNow(); readers.shutdownNow(); writes.shutdownNow() }
    val isRunning get() = running

    fun connections(): List<Peer> = peersSnapshot()
    fun isLinked(id: ByteArray): Boolean = synchronized(peers) { peers.containsKey(meshToken(id)) }
    /** True only for a Wi-Fi link. A Bluetooth link should not stop Wi-Fi from forming: it has more range and speed, and [prefer] swaps to it. */
    fun isLinkedOverWifi(id: ByteArray): Boolean = synchronized(peers) { peers[meshToken(id)]?.transport == MeshTransport.WIFI }
    private fun peersSnapshot() = synchronized(peers) { peers.values.toList() }

    /**
     * Adopt a connected stream. [expected] is the remote link ID learnt during discovery
     * (Bluetooth reads it from GATT); the remote HELLO must then match it.
     */
    fun attach(stream: MeshStream, transport: MeshTransport, outbound: Boolean, expected: ByteArray? = null): Peer? {
        val accepted = synchronized(peers) {
            if (!running || slots >= maximumLinks) false else { slots++; true }
        }
        if (!accepted) { runCatching { stream.close() }; return null }
        return try {
            val peer = Peer(stream, transport, outbound, expected)
            readers.execute { peer.readLoop() }; peer
        } catch (_: Exception) {
            synchronized(peers) { slots-- }; runCatching { stream.close() }; null
        }
    }

    private fun watch() {
        if (!running) return
        if (identity.rotateIfDue()) {
            // Links made with the old ID would otherwise keep it alive. Drop them;
            // transports reconnect under the new ID and queued ciphertext is retried.
            peersSnapshot().forEach { it.close() }
            synchronized(rotationListeners) { rotationListeners.toList() }.forEach { runCatching { it() } }
            return
        }
        val now = elapsed()
        peersSnapshot().forEach { peer ->
            if (now - peer.lastRead > idleTimeoutMs || (peer.writeStarted > 0 && now - peer.writeStarted > 20_000)) peer.close()
            else if (now - peer.lastWrite > keepaliveMs) peer.send(MeshFrames.KEEPALIVE, ByteArray(0))
        }
    }

    /**
     * Two phones can end up with two links to each other (both dialled, or Bluetooth
     * and Wi-Fi at once). Both sides must keep the SAME one, so the choice uses only
     * values both sides know: prefer Wi-Fi, then the link whose initiator sent the
     * lower (ID, nonce) pair.
     */
    private fun prefer(candidate: Peer, existing: Peer): Boolean {
        if (candidate.transport != existing.transport) return candidate.transport == MeshTransport.WIFI
        return compareBytes(candidate.initiatorKey, existing.initiatorKey) < 0
    }

    inner class Peer internal constructor(
        private val stream: MeshStream,
        val transport: MeshTransport,
        val outbound: Boolean,
        private val expected: ByteArray?
    ) {
        /** Remote link ID (base64url). Empty until HELLO is accepted. */
        @Volatile var token = ""; private set
        @Volatile var lastRead = elapsed(); private set
        @Volatile var lastWrite = lastRead; private set
        @Volatile var writeStarted = 0L; private set
        internal var initiatorKey = ByteArray(0); private set
        private val closed = AtomicBoolean(false)
        private val input = DataInputStream(stream.input)
        private val output = DataOutputStream(stream.output)
        private val inflight = LinkedHashMap<String, Long>()
        private val frameBudget = frameLimit()
        private val localId = identity.current()
        private val localNonce = ByteArray(MeshFrames.NONCE_BYTES).also(random::nextBytes)
        val isClosed get() = closed.get()
        val remoteAddress get() = stream.remoteAddress

        private fun write(type: Int, bytes: ByteArray) = synchronized(output) {
            check(!closed.get() && bytes.size + 1 <= MeshFrames.MAX_FRAME)
            writeStarted = elapsed()
            output.writeInt(bytes.size + 1); output.writeByte(type); output.write(bytes); output.flush()
            lastWrite = elapsed(); writeStarted = 0
        }

        fun send(type: Int, bytes: ByteArray, key: String? = null) {
            if (closed.get()) return
            try {
                writes.execute {
                    try {
                        if (key != null) synchronized(inflight) {
                            val now = elapsed()
                            if (inflight[key]?.let { now - it < 10_000 } == true) return@execute
                            while (inflight.size >= 1024) inflight.remove(inflight.keys.first())
                            inflight[key] = now
                        }
                        write(type, bytes)
                    } catch (_: Exception) { close() }
                }
            } catch (_: RejectedExecutionException) { /* Outbox keeps ciphertext for retry. */ }
        }

        fun consumeAck(key: String): Boolean = synchronized(inflight) { inflight.remove(key) != null }

        internal fun readLoop() {
            val deadline = timers.schedule({ if (token.isEmpty()) close() }, helloTimeoutMs, TimeUnit.MILLISECONDS)
            try {
                write(MeshFrames.HELLO, localId + localNonce)
                var first = true
                while (!closed.get() && running) {
                    val size = input.readInt(); check(size in 1..MeshFrames.MAX_FRAME)
                    check(frameBudget.allow(size))
                    val type = input.readUnsignedByte(); val bytes = ByteArray(size - 1); input.readFully(bytes)
                    lastRead = elapsed()
                    if (first) {
                        check(type == MeshFrames.HELLO && bytes.size == MeshFrames.HELLO_BYTES)
                        val remoteId = bytes.copyOfRange(0, MeshFrames.ID_BYTES)
                        val remoteNonce = bytes.copyOfRange(MeshFrames.ID_BYTES, MeshFrames.HELLO_BYTES)
                        check(!identity.isOwn(remoteId)) // our own advert, or a reflected HELLO
                        check(expected == null || expected.contentEquals(remoteId))
                        initiatorKey = if (outbound) localId + localNonce else remoteId + remoteNonce
                        val name = meshToken(remoteId)
                        val keep = synchronized(peers) {
                            val existing = peers[name]
                            when {
                                existing == null -> { peers[name] = this; null }
                                prefer(this, existing) -> { peers[name] = this; existing }
                                else -> this
                            }
                        }
                        if (keep === this) { close(); return }
                        token = name
                        keep?.close()
                        first = false; changed(connections().size)
                    } else if (type == MeshFrames.KEEPALIVE) check(bytes.isEmpty())
                    else {
                        check(type in MeshFrames.BUNDLE..MeshFrames.ACK || type == MeshFrames.BUNDLE_REQUEST || type == MeshFrames.DELIVERED)
                        when (type) {
                            MeshFrames.BUNDLE -> check(bytes.size in 1883..1883 + MeshFrames.MAX_NAME)
                            MeshFrames.CONFIRM -> check(bytes.size == 101)
                            MeshFrames.ACK, MeshFrames.DELIVERED -> check(bytes.size == 48)
                            MeshFrames.ENVELOPE -> check(bytes.size >= 1 + EffortStamp.BYTES + 74)
                            MeshFrames.BUNDLE_REQUEST -> check(bytes.size == 32)
                        }
                        record(this, type, bytes)
                    }
                }
            } catch (_: Exception) { /* Reconnect retries durable ciphertext; no inferred receipt. */ }
            finally { deadline.cancel(false); close(); synchronized(peers) { slots-- } }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            val removed = synchronized(peers) { if (token.isNotEmpty() && peers[token] === this) { peers.remove(token); true } else false }
            runCatching { stream.close() }
            if (removed) changed(connections().size)
        }
    }
}
