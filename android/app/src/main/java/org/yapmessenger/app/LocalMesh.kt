package org.yapmessenger.app

import android.content.Context

/**
 * Nearby messaging over every local transport at once. Users see one switch
 * ("nearby messaging"); which radio carries a message is an internal detail.
 * Wi-Fi needs no runtime permission; Bluetooth runs only when permitted and on.
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

    /** Starting always issues a fresh link ID, so restarts are never linkable. */
    @Synchronized fun start(bluetoothAllowed: Boolean) {
        hub.start()
        wifiOn = wifi.start(); publish()
        if (bluetoothAllowed) bluetooth.start()
    }
    @Synchronized fun stop() { bluetooth.stop(); wifi.stop(); wifiOn = false; hub.stop(); publish() }
    fun close() { bluetooth.close(); wifi.close(); hub.close() }
    fun connections(): List<MeshHub.Peer> = hub.connections()
}
