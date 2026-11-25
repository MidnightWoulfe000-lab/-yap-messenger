package org.yapmessenger.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wi-Fi transport for the local mesh: phones on the same Wi-Fi network, or one
 * phone's hotspot with others joined to it. No internet is needed or used.
 *
 * Discovery uses Android's built-in network service discovery (mDNS) with a service
 * name made from the hub's rotating random link ID. Many hotspots and some routers
 * block multicast, so clients also try the network's gateway (the hotspot phone)
 * directly on [PORT]. Frames, link IDs and duplicate handling live in [MeshHub].
 *
 * Plain TCP is fine here only because every envelope is already end-to-end
 * encrypted. The link itself is not authenticated, exactly like the Bluetooth link.
 */
internal class MeshWifi(
    private val context: Context,
    private val hub: MeshHub,
    private val report: (String) -> Unit
) {
    companion object {
        const val PORT = 47_651
        private const val SERVICE_TYPE = "_yapmesh._tcp."
        private const val NAME_PREFIX = "yap-"
        /** Parse the remote link ID from a discovered service name, or null. */
        internal fun linkIdFromName(name: String): ByteArray? {
            if (!name.startsWith(NAME_PREFIX) || name.length != NAME_PREFIX.length + MeshFrames.ID_BYTES * 2) return null
            val hex = name.substring(NAME_PREFIX.length)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return ByteArray(MeshFrames.ID_BYTES) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        internal fun nameFor(id: ByteArray) = NAME_PREFIX + id.joinToString("") { "%02x".format(it) }
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val io = Executors.newCachedThreadPool { task -> Thread(task, "yap-wifi").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "yap-wifi-timer").apply { isDaemon = true } }
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolving = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolveBusy = false
    private val dialling = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init { hub.onRotate { if (running) { unregister(); register() } } }

    /** Returns true when Wi-Fi nearby messaging is listening. */
    @Synchronized fun start(): Boolean {
        if (running) return true
        running = true
        try {
            server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT), 16) }
        } catch (_: Exception) {
            // Port taken (another app, or a stale socket). Fall back to any port; mDNS still works.
            server = runCatching { ServerSocket(0, 16) }.getOrNull()
        }
        val listening = server ?: run { running = false; report("Wi-Fi nearby messaging could not start"); return false }
        io.execute {
            while (running) {
                val socket = try { listening.accept() } catch (_: Exception) { break }
                configure(socket)
                MeshLog.i("wifi: accepted a connection")
                hub.attach(MeshStream(socket.getInputStream(), socket.getOutputStream(), socket, socket.inetAddress?.hostAddress), MeshTransport.WIFI, outbound = false)
            }
        }
        register(); discover()
        if (!gatewayScheduled) { gatewayScheduled = true; timer.scheduleWithFixedDelay({ runCatching { dialGateway() } }, 2, 20, TimeUnit.SECONDS) }
        return true
    }
    private var gatewayScheduled = false

    @Synchronized fun stop() {
        if (!running) return
        running = false
        unregister()
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
        runCatching { server?.close() }; server = null
        synchronized(resolving) { resolving.clear(); resolveBusy = false }
    }

    fun close() { stop(); timer.shutdownNow(); io.shutdownNow() }

    private fun configure(socket: Socket) { socket.tcpNoDelay = true; socket.keepAlive = true }

    @Synchronized private fun register() {
        val port = server?.localPort ?: return
        val info = NsdServiceInfo().apply { serviceName = nameFor(hub.identity.current()); serviceType = SERVICE_TYPE; setPort(port) }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { report("Wi-Fi discovery could not advertise ($error); hotspot fallback remains") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    @Synchronized private fun unregister() {
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
    }

    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { report("Wi-Fi discovery failed ($error); hotspot fallback remains") }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                val remote = linkIdFromName(info.serviceName) ?: return
                if (!running || hub.identity.isOwn(remote) || hub.isLinked(remote)) return
                // Same rule as Bluetooth: only the lower link ID dials.
                if (compareBytes(hub.identity.current(), remote) >= 0) return
                enqueueResolve(info)
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { report("Wi-Fi discovery is unavailable; hotspot fallback remains") }
    }

    // Older Android versions allow only one resolve at a time.
    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolving) {
            if (resolving.size >= 16 || resolving.any { it.serviceName == info.serviceName }) return
            resolving.add(info)
        }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(resolving) {
            if (resolveBusy || !running) return
            resolving.poll()?.also { resolveBusy = true } ?: return
        }
        @Suppress("DEPRECATION")
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) { done() }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val remote = linkIdFromName(info.serviceName)
                    @Suppress("DEPRECATION") val host = info.host
                    if (remote != null && host != null) dial(host, info.port, remote)
                    done()
                }
                private fun done() { synchronized(resolving) { resolveBusy = false }; resolveNext() }
            })
        }.onFailure { synchronized(resolving) { resolveBusy = false } }
    }

    /**
     * Hotspot fallback: if this phone is a client of a hotspot that runs Yap, the
     * hotspot phone is the gateway. Its link ID is unknown until HELLO, so there is
     * no expected ID; duplicates are resolved by the hub.
     */
    private fun dialGateway() {
        if (!running) return
        val network = connectivity.allNetworks.firstOrNull {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return
        val gateway = connectivity.getLinkProperties(network)?.routes
            ?.firstOrNull { it.destination.prefixLength == 0 && it.gateway is Inet4Address }?.gateway ?: return // default route; isDefaultRoute needs API 29
        if (gateway.isAnyLocalAddress || !gateway.isSiteLocalAddress) return // never dial public addresses
        // Already linked to the gateway (via mDNS or an earlier dial)? Redialling would
        // create a duplicate link that the hub then has to discard.
        if (hub.connections().any { it.transport == MeshTransport.WIFI && it.remoteAddress == gateway.hostAddress }) return
        dial(gateway, PORT, null)
    }

    /** The network whose local subnet or routes cover [host], or null if none is known. */
    private fun networkFor(host: InetAddress): Network? = runCatching {
        connectivity.allNetworks.firstOrNull { network ->
            connectivity.getLinkProperties(network)?.routes?.any { it.destination.prefixLength > 0 && it.matches(host) } == true
        }
    }.getOrNull()

    internal fun dial(host: InetAddress, port: Int = PORT, expected: ByteArray? = null) {
        if (!running || port !in 1..65_535) return
        if (!(host.isSiteLocalAddress || host.isLinkLocalAddress)) return // local networks only
        // Never dial ourselves (for example our own stale mDNS advert): that makes a phantom link.
        if (runCatching { java.net.NetworkInterface.getByInetAddress(host) != null }.getOrDefault(false)) { MeshLog.i("wifi: skipped dialling this phone's own address"); return }
        val key = "${host.hostAddress}:$port"
        if (!dialling.add(key)) return
        io.execute {
            val socket = Socket()
            try {
                // A hotspot or router with no internet is not Android's default network, so an
                // unbound socket to a local address leaves over mobile data and never arrives.
                // Bind to the network that actually owns this address. Bluetooth is unaffected,
                // which is why only Wi-Fi failed.
                networkFor(host)?.let { runCatching { it.bindSocket(socket) } }
                socket.connect(InetSocketAddress(host, port), 5_000)
                configure(socket)
                hub.attach(MeshStream(socket.getInputStream(), socket.getOutputStream(), socket, host.hostAddress), MeshTransport.WIFI, outbound = true, expected = expected)
                MeshLog.i("wifi: dial connected")
            } catch (e: Exception) { MeshLog.i("wifi: dial failed ${e.javaClass.simpleName}"); runCatching { socket.close() } }
            finally { dialling.remove(key) }
        }
    }
}
