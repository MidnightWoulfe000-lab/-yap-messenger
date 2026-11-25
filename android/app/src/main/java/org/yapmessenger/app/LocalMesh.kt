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
    context: Context,
    report: (String) -> Unit,
    changed: (Int) -> Unit,
    visibility: (Boolean) -> Unit,
    record: (MeshHub.Peer, Int, ByteArray) -> Unit
) {
    private val hub = MeshHub(report, changed, record)
    @Volatile private var bluetoothVisible = false
    @Volatile private var wifiOn = false
    private val show = visibility
    private fun publish() = show(bluetoothVisible || wifiOn)
    private val bluetooth = MeshBluetooth(context, hub, report) { bluetoothVisible = it; publish() }
    private val wifi = MeshWifi(context, hub, report)
    private val aware = MeshWifiAware(context, hub, report)
    private val direct = MeshWifiDirect(context, hub, wifi, report)

    /** Starting always issues a fresh link ID, so restarts are never linkable. */
    @Synchronized fun start(bluetoothAllowed: Boolean, proximityWifiAllowed: Boolean = false) {
        hub.start()
        wifiOn = wifi.start()
        // Aware is the proper drop-in/drop-out transport. Direct runs only where Aware is
        // unsupported: it is flakier and its device address is not rotated.
        if (proximityWifiAllowed) {
            val awareOn = aware.start()
            val directOn = !awareOn && direct.start()
            wifiOn = wifiOn || awareOn || directOn
        }
        publish()
        if (bluetoothAllowed) bluetooth.start()
    }
    @Synchronized fun stop() { bluetooth.stop(); aware.stop(); direct.stop(); wifi.stop(); wifiOn = false; hub.stop(); publish() }
    fun close() { bluetooth.close(); aware.close(); direct.close(); wifi.close(); hub.close() }
    fun connections(): List<MeshHub.Peer> = hub.connections()
}
