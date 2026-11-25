package org.yapmessenger.app

import android.content.Context

/**
 * Nearby messaging over every local transport at once. Users see one switch
 * ("nearby messaging"); which radio carries a message is an internal detail.
 * Proximity Wi-Fi (Aware, or Direct on phones without Aware) finds phones with no
 * router or hotspot; it needs the "nearby Wi-Fi" permission. The shared-network/hotspot
 * path needs no permission. Bluetooth runs only when permitted and on.
 */
internal class LocalMesh(
    private val context: Context,
    report: (String) -> Unit,
    changed: (Int) -> Unit,
    visibility: (Boolean) -> Unit,
    record: (MeshHub.Peer, Int, ByteArray) -> Unit
) {
    private val hub: MeshHub = MeshHub(report, { count -> MeshLog.i("links now $count: ${linkSummary()}"); changed(count) }, record, trace = { MeshLog.i(it) })
    private fun linkSummary(): String = runCatching { hub.connections().groupingBy { it.transport }.eachCount().toString() }.getOrDefault("?")
    @Volatile private var bluetoothVisible = false
    @Volatile private var wifiOn = false
    private val show = visibility
    private fun publish() = show(bluetoothVisible || wifiOn)
    private val bluetooth = MeshBluetooth(context, hub, report) { bluetoothVisible = it; publish() }
    private val wifi = MeshWifi(context, hub, report)
    private val aware = MeshWifiAware(context, hub, report) { fallBackToDirect() }
    @Volatile private var proximityWifi = false
    private val direct = MeshWifiDirect(context, hub, wifi, report)

    /** Starting always issues a fresh link ID, so restarts are never linkable. */
    @Synchronized fun start(bluetoothAllowed: Boolean, proximityWifiAllowed: Boolean = false) {
        val fresh = !hub.isRunning
        hub.start() // no-op (and no new link ID) when already running
        wifiOn = wifi.start()
        // Aware is the proper drop-in/drop-out transport. Direct runs only where Aware is
        // unsupported: it is flakier and its device address is not rotated.
        var awareOn = false; var directOn = false
        proximityWifi = proximityWifiAllowed
        if (proximityWifiAllowed) {
            awareOn = aware.start()
            directOn = !awareOn && direct.start()
            wifiOn = wifiOn || awareOn || directOn
        }
        MeshLog.i((if (fresh) "start" else "resume, same link ID") + ": bluetooth permitted=$bluetoothAllowed, nearby Wi-Fi permitted=$proximityWifiAllowed, " +
            "aware supported=${MeshWifiAware.supported(context)} on=$awareOn, direct on=$directOn")
        publish()
        if (bluetoothAllowed) bluetooth.start()
    }
    /** Aware said it is supported but would not start (seen on a Pixel 9 Pro XL): use Wi-Fi Direct instead. */
    @Synchronized private fun fallBackToDirect() {
        if (!proximityWifi || !hub.isRunning) return
        aware.stop()
        val directOn = direct.start()
        MeshLog.i("aware failed; direct on=$directOn")
        if (directOn) { wifiOn = true; publish() }
    }
    @Synchronized fun stop() { proximityWifi = false; bluetooth.stop(); aware.stop(); direct.stop(); wifi.stop(); wifiOn = false; hub.stop(); publish() }
    fun close() { bluetooth.close(); aware.close(); direct.close(); wifi.close(); hub.close() }
    fun connections(): List<MeshHub.Peer> = hub.connections()
}
