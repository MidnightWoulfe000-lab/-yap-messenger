package org.yapmessenger.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wi-Fi Direct transport: the fallback for phones without Wi-Fi Aware. Phones in range
 * find each other with Wi-Fi Direct service discovery, form a small group with no
 * router or hotspot, and then talk over the same TCP port as [MeshWifi], so framing,
 * link IDs and duplicate handling are shared with every other transport.
 *
 * The phone with the lower link ID asks to connect (same rule as every transport).
 * Once a group exists, the group client dials the group owner on [MeshWifi.PORT]; the
 * group owner just accepts, because [MeshWifi] already listens on every interface.
 *
 * Known limits, stated plainly:
 * - Wi-Fi Direct is less reliable than Aware. Many phones can be in only one group at
 *   a time, and some drop their normal Wi-Fi connection while a group is active.
 * - Android's connect step may show the other phone a system prompt on some versions.
 * - The Wi-Fi Direct device address is visible to nearby phones during discovery and
 *   Android does not let apps rotate it. Our link ID rotates; this address may not.
 *   That is why this transport is a fallback and runs only when Aware is unsupported.
 *
 * Not tested on real phones. See docs/proposals/local-mesh-v3-proximity-wifi.md.
 */
@SuppressLint("MissingPermission", "NewApi") // Callers check permission.
internal class MeshWifiDirect(
    private val context: Context,
    private val hub: MeshHub,
    private val wifi: MeshWifi,
    private val report: (String) -> Unit
) {
    companion object {
        private const val INSTANCE = "yap"
        private const val SERVICE_TYPE = "_yapmesh._tcp"
        private const val KEY = "n"
        private const val CONNECT_COOLDOWN_MS = 20_000L
        private const val GROUP_IDLE_MS = 60_000L

        fun supported(context: Context) = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)

        /** The TXT record carries only the link ID, in the same text form as the Wi-Fi service name. */
        internal fun idFromRecord(record: Map<String, String>?): ByteArray? = record?.get(KEY)?.let(MeshWifi::linkIdFromName)
    }

    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "yap-direct-timer").apply { isDaemon = true } }
    @Volatile private var running = false
    @Volatile private var lastConnect = 0L
    @Volatile private var groupSince = 0L
    @Volatile private var inGroup = false
    @Volatile private var isOwner = false
    private var service: WifiP2pDnsSdServiceInfo? = null
    private var receiverRegistered = false
    private var timerScheduled = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!running) return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    MeshLog.i("direct: p2p enabled=$enabled")
                    if (enabled) beginDiscovery()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val channel = channel ?: return
                    manager?.requestConnectionInfo(channel) { info -> groupChanged(info) }
                }
            }
        }
    }

    init { hub.onRotate { if (running) { removeGroup(); advertise() } } }

    /** Returns true when this phone supports Wi-Fi Direct and it has been switched on. */
    @Synchronized fun start(): Boolean {
        if (running) return true
        if (manager == null || !supported(context)) return false
        channel = manager.initialize(context, Looper.getMainLooper(), null) ?: return false
        running = true
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        receiverRegistered = true
        beginDiscovery()
        if (!timerScheduled) {
            timerScheduled = true
            timer.scheduleWithFixedDelay({ runCatching { tick() } }, 15, 25, TimeUnit.SECONDS)
        }
        return true
    }

    @Synchronized fun stop() {
        if (!running) return
        running = false
        if (receiverRegistered) { runCatching { context.unregisterReceiver(receiver) }; receiverRegistered = false }
        val channel = channel ?: return
        runCatching { manager?.clearServiceRequests(channel, null) }
        runCatching { manager?.clearLocalServices(channel, null) }
        service = null
        removeGroup()
        runCatching { channel.close() }
        this.channel = null
    }

    fun close() { stop(); timer.shutdownNow() }

    private fun beginDiscovery() {
        val channel = channel ?: return
        val p2p = manager ?: return
        p2p.setDnsSdResponseListeners(channel, { _, _, _ -> }, { _, record, device -> found(record, device) })
        advertise()
        search()
    }

    /** (Re)publish our link ID. Called at start and after every link ID rotation. */
    @Synchronized private fun advertise() {
        val channel = channel ?: return
        val p2p = manager ?: return
        p2p.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = add()
            override fun onFailure(reason: Int) = add()
            private fun add() {
                if (!running) return
                val info = WifiP2pDnsSdServiceInfo.newInstance(INSTANCE, SERVICE_TYPE, mapOf(KEY to MeshWifi.nameFor(hub.identity.current())))
                service = info
                p2p.addLocalService(channel, info, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { MeshLog.i("direct: advertising") }
                    override fun onFailure(reason: Int) { MeshLog.i("direct: advertise failed $reason"); report("Wi-Fi Direct could not advertise ($reason)") }
                })
            }
        })
    }

    private fun search() {
        val channel = channel ?: return
        val p2p = manager ?: return
        p2p.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = request()
            override fun onFailure(reason: Int) = request()
            private fun request() {
                if (!running) return
                p2p.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(), object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        p2p.discoverServices(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { MeshLog.i("direct: searching") }
                            override fun onFailure(reason: Int) { MeshLog.i("direct: search failed $reason"); if (reason != WifiP2pManager.BUSY) report("Wi-Fi Direct search failed ($reason)") }
                        })
                    }
                    override fun onFailure(reason: Int) { MeshLog.i("direct: service request failed $reason") }
                })
            }
        })
    }

    private fun found(record: Map<String, String>?, device: WifiP2pDevice) {
        val remote = idFromRecord(record) ?: return
        // Any existing link (Bluetooth included) wins: forming a group disturbs the shared radio.
        if (!running || hub.identity.isOwn(remote) || hub.isLinked(remote)) return
        MeshLog.i("direct: found a Yap phone")
        if (compareBytes(hub.identity.current(), remote) >= 0) return // only the lower link ID connects
        val now = System.currentTimeMillis()
        // One connection attempt at a time, and never leave a group we are a client in.
        if (inGroup && !isOwner) return
        if (now - lastConnect < CONNECT_COOLDOWN_MS) return
        lastConnect = now
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress; wps.setup = WpsInfo.PBC }
        val channel = channel ?: return
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { MeshLog.i("direct: connect requested") }
            override fun onFailure(reason: Int) { MeshLog.i("direct: connect failed $reason"); lastConnect = 0 }
        })
    }

    private fun groupChanged(info: WifiP2pInfo?) {
        MeshLog.i("direct: group formed=${info?.groupFormed} owner=${info?.isGroupOwner}")
        if (info == null || !info.groupFormed) { inGroup = false; isOwner = false; groupSince = 0; return }
        if (!inGroup) groupSince = System.currentTimeMillis()
        inGroup = true; isOwner = info.isGroupOwner
        if (info.isGroupOwner) return // MeshWifi's server already accepts on this interface
        val owner = info.groupOwnerAddress ?: return
        // The group owner may need a moment to be ready; the hub drops duplicates.
        timer.schedule(Runnable { runCatching { if (running && inGroup) wifi.dial(owner) } }, 1, TimeUnit.SECONDS)
        timer.schedule(Runnable { runCatching { if (running && inGroup) dialIfUnlinked(owner) } }, 6, TimeUnit.SECONDS)
    }

    private fun dialIfUnlinked(owner: java.net.InetAddress) {
        if (hub.connections().none { it.remoteAddress == owner.hostAddress }) wifi.dial(owner)
    }

    private fun tick() {
        if (!running) return
        search()
        // Free the radio when nobody is using the group.
        if (inGroup && System.currentTimeMillis() - groupSince > GROUP_IDLE_MS &&
            hub.connections().none { it.transport == MeshTransport.WIFI && it.remoteAddress?.startsWith("192.168.49.") == true }) removeGroup()
    }

    private fun removeGroup() {
        val channel = channel ?: return
        inGroup = false; isOwner = false; groupSince = 0
        runCatching { manager?.removeGroup(channel, null) }
    }
}
