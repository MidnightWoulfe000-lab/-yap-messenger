package org.yapmessenger.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi-Fi Aware (NAN) transport: phones find each other directly over Wi-Fi hardware
 * and open a peer-to-peer link with no router, no hotspot and no shared network.
 * Phones in range link up; phones out of range drop off. Needs Android 10+ and a
 * Wi-Fi chip that supports Aware; [supported] says whether this phone does.
 *
 * Discovery: every phone publishes and subscribes to one service. The publisher's
 * service-specific info is only the hub's rotating random link ID. The phone with the
 * lower link ID is the initiator (same rule as Bluetooth and Wi-Fi): it sends HELLO, the
 * publisher opens a listening socket, requests its side of the data path and answers
 * READY, and only then does the initiator request its side and connect. Discovered
 * peers are retried every [RETRY_SECONDS] until a Wi-Fi link exists. Frames, link IDs and duplicate handling live in [MeshHub].
 *
 * The data path is tried open (no passphrase) first. In case some phones' Wi-Fi chips refuse
 * open Aware data paths, after a failure with a peer the publisher switches that peer to a
 * passphrase link using [LINK_PASSPHRASE], and alternates until one works. The mode
 * travels in READY so both sides always request the same kind of link.
 * That passphrase is NOT a security measure: it is public in this open-source code and in
 * every APK. It only satisfies phones that will not make open links. Every envelope is
 * already end-to-end encrypted, and the link stays unauthenticated, exactly like Bluetooth.
 * Agreed with the project owner on 2026-11-25.
 *
 * Tested 2026-11-25 on a Pixel 9 Pro XL and a Nothing Phone (A063): an open link formed
 * on the first attempt once READY was delayed. The passphrase path is not yet tested on
 * a real phone. See docs/proposals/local-mesh-v3-proximity-wifi.md.
 */
@SuppressLint("MissingPermission", "NewApi") // Callers check permission and API level.
internal class MeshWifiAware(
    private val context: Context,
    private val hub: MeshHub,
    private val report: (String) -> Unit,
    /** Called when Aware is supported on paper but will not start, so Wi-Fi Direct can take over. */
    private val failed: () -> Unit = {}
) {
    /** A parsed READY: the publisher's listening port and whether the link uses the passphrase. */
    internal data class Ready(val port: Int, val passphrase: Boolean)

    companion object {
        private const val SERVICE = "yapmesh"
        private val HELLO = "yap".toByteArray()
        /**
         * Publisher to initiator: "my side of the data path is requested, ask for yours now",
         * followed by the 2-byte TCP port to connect to and 1 mode byte (0 open, 1 passphrase).
         * Android only carries a port inside the data path for passphrase links (build() throws
         * "Port and transport protocol information can only be specified on a secure link"), so
         * the port always travels here instead. It is a random listening port, not an identifier.
         */
        private val READY = "yap-ready".toByteArray()

        /**
         * Public by design (see the class comment): it lets phones that refuse open links use a
         * passphrase link. It protects nothing; never treat it as a secret or rely on it.
         */
        private const val LINK_PASSPHRASE = "yap-nearby-link-not-a-secret"

        /** The port and link mode in a READY message, or null if it is not a well-formed READY. */
        internal fun parseReady(message: ByteArray): Ready? {
            if (message.size != READY.size + 3 || !message.copyOfRange(0, READY.size).contentEquals(READY)) return null
            val port = ((message[READY.size].toInt() and 255) shl 8) or (message[READY.size + 1].toInt() and 255)
            val mode = message[READY.size + 2].toInt()
            if (port !in 1..65_535 || mode !in 0..1) return null
            return Ready(port, mode == 1)
        }
        internal fun readyFor(port: Int, passphrase: Boolean) =
            READY + byteArrayOf((port shr 8).toByte(), port.toByte(), if (passphrase) 1 else 0)

        private fun mode(passphrase: Boolean) = if (passphrase) "password" else "open"
        private const val RETRY_SECONDS = 15L
        private const val READY_DELAY_MS = 1_500L
        private const val MAX_OUTGOING = 2
        private const val MAX_INCOMING = 2
        private const val SETUP_TIMEOUT_MS = 30_000L

        fun supported(context: Context) =
            Build.VERSION.SDK_INT >= 29 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)

        /** Service-specific info is exactly one link ID; anything else is not ours. */
        internal fun idFromInfo(info: ByteArray?): ByteArray? = info?.takeIf { it.size == MeshFrames.ID_BYTES }?.copyOf()
    }

    private val manager: WifiAwareManager? = context.getSystemService(WifiAwareManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool { task -> Thread(task, "yap-aware").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "yap-aware-timer").apply { isDaemon = true } }
    @Volatile private var running = false
    private var session: WifiAwareSession? = null
    private var publish: PublishDiscoverySession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private var attaching = false
    private val pending = ConcurrentHashMap<String, Long>() // remote link ID -> give-up time
    private val outgoing = AtomicInteger()
    private val incoming = AtomicInteger()
    private val messageIds = AtomicInteger(1)
    private val sent = ConcurrentHashMap<Int, String>() // message ID -> remote link ID
    // Android reports each publisher only once per subscribe session, so remember who we
    // saw and retry them; otherwise one failed setup means no Wi-Fi link until rotation.
    private val discovered = ConcurrentHashMap<String, Pair<PeerHandle, ByteArray>>()
    // Publisher side: peers whose last data path failed get the other link mode next time.
    private val passphraseFor = ConcurrentHashMap<PeerHandle, Boolean>()
    private var receiverRegistered = false
    private var timerScheduled = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!running) return
            if (manager?.isAvailable == true) attach() else teardown()
        }
    }

    init { hub.onRotate { if (running) main.post { restartDiscovery() } } }

    /** Returns true when this phone supports Aware and it has been switched on. */
    @Synchronized fun start(): Boolean {
        if (running) return true
        if (manager == null || !supported(context)) return false
        running = true
        val filter = IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(stateReceiver, filter)
        receiverRegistered = true
        if (!timerScheduled) {
            timerScheduled = true
            timer.scheduleWithFixedDelay({ val now = System.currentTimeMillis(); pending.entries.removeIf { it.value < now } }, 10, 10, TimeUnit.SECONDS)
            timer.scheduleWithFixedDelay({ if (running) main.post { retryDiscovered() } }, RETRY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS)
        }
        if (manager.isAvailable) attach()
        return true
    }

    @Synchronized fun stop() {
        if (!running) return
        running = false
        if (receiverRegistered) { runCatching { context.unregisterReceiver(stateReceiver) }; receiverRegistered = false }
        teardown()
    }

    fun close() { stop(); timer.shutdownNow(); io.shutdownNow() }

    private fun attach() {
        synchronized(this) { if (session != null || attaching || !running) return; attaching = true }
        runCatching {
            manager?.attach(object : AttachCallback() {
                override fun onAttached(attached: WifiAwareSession) {
                    synchronized(this@MeshWifiAware) { attaching = false; if (!running) { attached.close(); return }; session = attached }
                    MeshLog.i("aware: attached")
                    restartDiscovery()
                }
                override fun onAttachFailed() {
                    MeshLog.i("aware: attach failed")
                    synchronized(this@MeshWifiAware) { attaching = false }
                    report("Wi-Fi Aware could not start on this phone; trying Wi-Fi Direct instead")
                    failed()
                }
                override fun onAwareSessionTerminated() { teardown() }
            }, main)
        }.onFailure { MeshLog.i("aware: attach threw ${it.javaClass.simpleName}"); attaching = false; report("Wi-Fi Aware is unavailable; trying Wi-Fi Direct instead"); failed() }
    }

    @Synchronized private fun teardown() {
        runCatching { publish?.close() }; publish = null
        runCatching { subscribe?.close() }; subscribe = null
        runCatching { session?.close() }; session = null
        attaching = false
        pending.clear(); sent.clear(); discovered.clear(); passphraseFor.clear(); outgoing.set(0); incoming.set(0)
    }

    /** Start (or restart, after a link ID rotation) publishing and subscribing. */
    @Synchronized private fun restartDiscovery() {
        val current = session ?: return
        runCatching { publish?.close() }; publish = null
        runCatching { subscribe?.close() }; subscribe = null
        pending.clear(); discovered.clear(); passphraseFor.clear()
        val info = hub.identity.current()
        runCatching {
            current.publish(
                PublishConfig.Builder().setServiceName(SERVICE).setServiceSpecificInfo(info).setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED).build(),
                publisher, main
            )
            current.subscribe(
                SubscribeConfig.Builder().setServiceName(SERVICE).setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE).build(),
                subscriber, main
            )
        }.onFailure { report("Wi-Fi Aware discovery failed") }
    }

    private val publisher = object : DiscoverySessionCallback() {
        override fun onPublishStarted(started: PublishDiscoverySession) { MeshLog.i("aware: publishing"); synchronized(this@MeshWifiAware) { publish = started } }
        override fun onSessionTerminated() { synchronized(this@MeshWifiAware) { publish = null } }
        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            if (running && message.contentEquals(HELLO)) { MeshLog.i("aware: HELLO received, responding"); respond(peerHandle) }
        }
    }

    private val subscriber = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(started: SubscribeDiscoverySession) { MeshLog.i("aware: subscribing"); synchronized(this@MeshWifiAware) { subscribe = started } }
        override fun onSessionTerminated() { synchronized(this@MeshWifiAware) { subscribe = null } }
        override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
            val remote = idFromInfo(serviceSpecificInfo) ?: return
            if (!running || hub.identity.isOwn(remote)) return
            if (compareBytes(hub.identity.current(), remote) >= 0) return // only the lower link ID initiates
            discovered[meshToken(remote)] = peerHandle to remote
            MeshLog.i("aware: discovered a Yap phone")
            sayHello(peerHandle, remote)
        }
        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            if (!running) return
            val ready = parseReady(message) ?: return
            val key = discovered.entries.firstOrNull { it.value.first == peerHandle }?.key
                ?: run { MeshLog.i("aware: READY from a phone we did not discover; ignored"); return }
            MeshLog.i("aware: READY received, requesting ${mode(ready.passphrase)} data path")
            initiate(peerHandle, key, ready.port, ready.passphrase)
        }
        override fun onMessageSendSucceeded(messageId: Int) { sent.remove(messageId) } // now wait for READY
        override fun onMessageSendFailed(messageId: Int) { sent.remove(messageId)?.let { pending.remove(it) } }
    }

    /** Ask a discovered publisher to set up a data path. It answers READY when its side is requested. */
    private fun sayHello(peer: PeerHandle, remote: ByteArray) {
        // Any existing link (Bluetooth included) wins: a Wi-Fi attempt must never disturb a working link.
        if (hub.isLinked(remote)) return
        val key = meshToken(remote)
        if (outgoing.get() >= MAX_OUTGOING) return
        if (pending.putIfAbsent(key, System.currentTimeMillis() + SETUP_TIMEOUT_MS) != null) return
        val target = subscribe ?: run { pending.remove(key); return }
        val id = messageIds.getAndIncrement()
        sent[id] = key
        MeshLog.i("aware: sending HELLO")
        runCatching { target.sendMessage(peer, id, HELLO) }.onFailure { sent.remove(id); pending.remove(key) }
    }

    private fun retryDiscovered() {
        if (!running) return
        discovered.values.toList().forEach { (peer, remote) -> sayHello(peer, remote) }
    }

    private fun specFor(builder: WifiAwareNetworkSpecifier.Builder, passphrase: Boolean): WifiAwareNetworkSpecifier =
        (if (passphrase) builder.setPskPassphrase(LINK_PASSPHRASE) else builder).build()

    private fun networkRequest(spec: WifiAwareNetworkSpecifier) = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        .setNetworkSpecifier(spec).build()

    /** Initiator: ask for the data path, then connect to the port the publisher sent in READY. */
    private fun initiate(peer: PeerHandle, key: String, port: Int, passphrase: Boolean) {
        val session = subscribe ?: run { pending.remove(key); return }
        if (outgoing.incrementAndGet() > MAX_OUTGOING) { outgoing.decrementAndGet(); pending.remove(key); return }
        val spec = runCatching { specFor(WifiAwareNetworkSpecifier.Builder(session, peer), passphrase) }
            .onFailure { MeshLog.i("aware: data path request invalid ${it.javaClass.simpleName}") }.getOrNull()
            ?: run { outgoing.decrementAndGet(); pending.remove(key); return }
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var callback: ConnectivityManager.NetworkCallback
        val release = {
            if (released.compareAndSet(false, true)) {
                outgoing.decrementAndGet(); pending.remove(key)
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val info = capabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                val address = info.peerIpv6Addr ?: run { MeshLog.i("aware: data path up but Android gave no peer address"); return }
                if (!connected.compareAndSet(false, true)) return
                MeshLog.i("aware: initiator data path up, connecting")
                io.execute {
                    try {
                        val socket = network.socketFactory.createSocket(address, port)
                        socket.tcpNoDelay = true; socket.keepAlive = true
                        val closer = Closeable { runCatching { socket.close() }; release() }
                        hub.attach(MeshStream(socket.getInputStream(), socket.getOutputStream(), closer, address.hostAddress), MeshTransport.WIFI, outbound = true)
                        MeshLog.i("aware: initiator socket connected")
                    } catch (e: Exception) { MeshLog.i("aware: initiator socket failed ${e.javaClass.simpleName}"); release() }
                }
            }
            override fun onLost(network: Network) { MeshLog.i("aware: initiator data path lost"); release() }
            override fun onUnavailable() { MeshLog.i("aware: initiator data path unavailable"); release() }
        }
        runCatching { connectivity.requestNetwork(networkRequest(spec), callback, SETUP_TIMEOUT_MS.toInt()) }
            .onFailure { MeshLog.i("aware: initiator network request failed ${it.javaClass.simpleName}"); release() }
    }

    /** Publisher: open a listening socket, request the data path, send READY with the port, accept one link. */
    private fun respond(peer: PeerHandle) {
        val session = publish ?: run { MeshLog.i("aware: no publish session; cannot respond"); return }
        if (incoming.incrementAndGet() > MAX_INCOMING) { incoming.decrementAndGet(); MeshLog.i("aware: busy with other setups; HELLO ignored"); return }
        val server = runCatching { ServerSocket(0) }.getOrNull() ?: run { incoming.decrementAndGet(); MeshLog.i("aware: could not open a listening socket"); return }
        val passphrase = passphraseFor[peer] == true
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val linked = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var callback: ConnectivityManager.NetworkCallback
        val release = {
            if (released.compareAndSet(false, true)) {
                // A setup that never produced a link: try the other mode with this peer next time.
                if (!linked.get()) passphraseFor[peer] = !passphrase
                incoming.decrementAndGet()
                runCatching { server.close() }
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { MeshLog.i("aware: responder ${mode(passphrase)} data path up") }
            override fun onLost(network: Network) { MeshLog.i("aware: responder data path lost"); release() }
            override fun onUnavailable() { MeshLog.i("aware: responder ${mode(passphrase)} data path unavailable"); release() }
        }
        // No setPort(): the port goes in READY so open and passphrase links work the same way.
        val spec = runCatching { specFor(WifiAwareNetworkSpecifier.Builder(session, peer), passphrase) }
            .onFailure { MeshLog.i("aware: data path request invalid ${it.javaClass.simpleName}") }.getOrNull()
            ?: run { release(); return }
        runCatching { connectivity.requestNetwork(networkRequest(spec), callback, SETUP_TIMEOUT_MS.toInt()) }
            .onFailure { MeshLog.i("aware: responder network request failed ${it.javaClass.simpleName}"); release(); return }
        // Only now may the initiator ask for its side; asking first is refused by many phones.
        // requestNetwork() is asynchronous: give Android a moment to register our side before
        // READY goes out, or the initiator's request can arrive first and be rejected (seen as
        // "initiator data path unavailable" within 1-2 s on a Pixel 9 Pro XL).
        main.postDelayed({
            if (!running || released.get()) return@postDelayed
            runCatching { session.sendMessage(peer, messageIds.getAndIncrement(), readyFor(server.localPort, passphrase)) }
                .onSuccess { MeshLog.i("aware: READY sent (${mode(passphrase)} link)") }
                .onFailure { MeshLog.i("aware: could not send READY"); release() }
        }, READY_DELAY_MS)
        io.execute {
            try {
                server.soTimeout = SETUP_TIMEOUT_MS.toInt()
                val socket = server.accept()
                linked.set(true)
                runCatching { server.close() }
                socket.tcpNoDelay = true; socket.keepAlive = true
                val closer = Closeable { runCatching { socket.close() }; release() }
                hub.attach(MeshStream(socket.getInputStream(), socket.getOutputStream(), closer, socket.inetAddress?.hostAddress), MeshTransport.WIFI, outbound = false)
                MeshLog.i("aware: responder accepted socket")
            } catch (e: Exception) { MeshLog.i("aware: responder accept failed ${e.javaClass.simpleName}"); release() }
        }
    }
}
