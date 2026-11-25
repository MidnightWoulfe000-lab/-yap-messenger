package org.yapmessenger.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal fun encodeToken(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
internal fun decodeToken(text: String): ByteArray = android.util.Base64.decode(text, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

/**
 * Bluetooth discovery and L2CAP links for the local mesh. Framing, link IDs and
 * duplicate handling live in [MeshHub]; this class only finds phones and opens sockets.
 *
 * v2 privacy change: the GATT characteristic used to return the phone's permanent
 * messaging route token to anyone who connected. It now returns only the hub's
 * rotating random link ID. See docs/proposals/local-mesh-v2.md.
 */
@SuppressLint("MissingPermission", "NewApi") // MainActivity grants permissions; start rejects API <29.
internal class MeshBluetooth(
    private val context: Context,
    private val hub: MeshHub,
    private val report: (String) -> Unit,
    private val visibility: (Boolean) -> Unit = {}
) {
    companion object {
        // New UUIDs for v2 so v1 builds (which exposed the route token) never interoperate.
        val SERVICE: UUID = UUID.fromString("a6f0c9e1-5b2d-4f8e-9a17-3c6d0e2b4301")
        val METADATA: UUID = UUID.fromString("a6f0c9e1-5b2d-4f8e-9a17-3c6d0e2b4302")
        private const val VERSION: Byte = 2
        private const val METADATA_BYTES = 3 + MeshFrames.ID_BYTES
    }
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val gatts = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothGatt>())
    @Volatile private var running = false
    private var server: BluetoothGattServer? = null
    private var listener: BluetoothServerSocket? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var generation = 0
    private val advertising = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { visibility(true) }
        override fun onStartFailure(errorCode: Int) { visibility(false); report("Bluetooth advertising failed ($errorCode). Wi-Fi and saved messages remain available.") }
    }
    private val gattServer = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (!running || service.uuid != SERVICE) return
            if (status != BluetoothGatt.GATT_SUCCESS) { report("Could not register Bluetooth discovery"); return }
            val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setConnectable(true).build()
            // No device name: the advert carries only the shared Yap service UUID.
            try { advertiser?.startAdvertising(settings, AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(SERVICE)).build(), advertising) }
            catch (_: SecurityException) { report("Bluetooth permission changed. Re-enable nearby messaging.") }
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val psm = listener?.psm ?: return
            val bytes = byteArrayOf(VERSION, (psm shr 8).toByte(), psm.toByte()) + hub.identity.current()
            if (characteristic.uuid != METADATA || offset !in 0..bytes.size) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes.copyOfRange(offset, bytes.size))
        }
    }
    private val scanning = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running || seen.size >= 64 || !seen.add(result.device.address) || gatts.size >= 4) return
            val finished = AtomicBoolean(false)
            val callback = object : BluetoothGattCallback() {
                private fun finish(gatt: BluetoothGatt, bytes: ByteArray?) {
                    if (!finished.compareAndSet(false, true)) return
                    gatts.remove(gatt); try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
                    if (!running || bytes == null || bytes.size != METADATA_BYTES || bytes[0] != VERSION) return
                    val remote = bytes.copyOfRange(3, METADATA_BYTES)
                    if (hub.identity.isOwn(remote) || hub.isLinked(remote)) return
                    // One deterministic initiator per pair of phones (lower link ID dials).
                    if (compareBytes(hub.identity.current(), remote) >= 0) return
                    val psm = ((bytes[1].toInt() and 255) shl 8) or (bytes[2].toInt() and 255)
                    if (psm == 0) return
                    io.execute {
                        var socket: BluetoothSocket? = null
                        try {
                            if (!running) return@execute
                            socket = result.device.createInsecureL2capChannel(psm)
                            val pending = socket
                            main.postDelayed({ if (!pending.isConnected) try { pending.close() } catch (_: Exception) {} }, 12_000)
                            socket.connect()
                            hub.attach(MeshStream(socket.inputStream, socket.outputStream, socket), MeshTransport.BLUETOOTH, outbound = true, expected = remote)
                        } catch (_: Exception) { try { socket?.close() } catch (_: Exception) {} }
                    }
                }
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (running && status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        if (!gatt.discoverServices()) finish(gatt, null)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) finish(gatt, null)
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    val value = gatt.getService(SERVICE)?.getCharacteristic(METADATA)
                    if (status != BluetoothGatt.GATT_SUCCESS || value == null || !gatt.readCharacteristic(value)) finish(gatt, null)
                }
                @Deprecated("Android callback")
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    @Suppress("DEPRECATION") val value = characteristic.value
                    finish(gatt, value.takeIf { status == BluetoothGatt.GATT_SUCCESS })
                }
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    finish(gatt, value.takeIf { status == BluetoothGatt.GATT_SUCCESS })
                }
            }
            try {
                val gatt = result.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                gatts.add(gatt)
                main.postDelayed({ if (finished.compareAndSet(false, true)) { gatts.remove(gatt); gatt.disconnect(); gatt.close() } }, 10_000)
            } catch (_: Exception) { report("Bluetooth permission or discovery changed. Re-enable nearby messaging.") }
        }
        override fun onScanFailed(errorCode: Int) { report("Bluetooth discovery failed ($errorCode). Wi-Fi remains available.") }
    }
    init {
        // After a link-ID rotation, phones seen under the old ID must be re-read.
        hub.onRotate { seen.clear() }
    }
    fun start() {
        if (Build.VERSION.SDK_INT < 29) { report("Bluetooth mesh needs Android 10 or later. Wi-Fi nearby messaging still works."); return }
        if (running) return
        val radio = adapter ?: run { report("Bluetooth is not supported. Wi-Fi nearby messaging still works."); return }
        if (!radio.isEnabled) { report("Bluetooth is off. Wi-Fi nearby messaging still works."); return }
        running = true; val token = ++generation
        io.execute {
            try {
                val socket = radio.listenUsingInsecureL2capChannel()
                if (!running || token != generation) { socket.close(); return@execute }
                listener = socket
                main.post {
                    if (!running || token != generation) return@post
                    advertiser = radio.bluetoothLeAdvertiser
                    val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                    service.addCharacteristic(BluetoothGattCharacteristic(METADATA, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
                    server = manager.openGattServer(context, gattServer)
                    if (server?.addService(service) != true) report("Bluetooth discovery is unavailable")
                    scanCycle(token)
                }
                while (running && token == generation) {
                    val accepted = socket.accept()
                    hub.attach(MeshStream(accepted.inputStream, accepted.outputStream, accepted), MeshTransport.BLUETOOTH, outbound = false)
                }
            } catch (_: Exception) { if (running && token == generation) report("Bluetooth mesh stopped unexpectedly. Wi-Fi remains available.") }
        }
    }
    private fun scanCycle(token: Int) {
        if (!running || token != generation) return
        seen.clear()
        try {
            scanner = adapter?.bluetoothLeScanner
            scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scanning)
        } catch (_: Exception) { report("Bluetooth discovery is unavailable") }
        main.postDelayed({
            if (running && token == generation) {
                try { scanner?.stopScan(scanning) } catch (_: Exception) {}
                main.postDelayed({ scanCycle(token) }, 15_000)
            }
        }, 45_000)
    }
    fun stop() {
        running = false; generation++
        visibility(false)
        try { scanner?.stopScan(scanning) } catch (_: Exception) {}
        try { advertiser?.stopAdvertising(advertising) } catch (_: Exception) {}
        try { listener?.close() } catch (_: Exception) {}; listener = null
        try { server?.close() } catch (_: Exception) {}; server = null
        synchronized(gatts) { gatts.forEach { try { it.disconnect(); it.close() } catch (_: Exception) {} }; gatts.clear() }
    }
    fun close() { stop(); io.shutdownNow() }
}
