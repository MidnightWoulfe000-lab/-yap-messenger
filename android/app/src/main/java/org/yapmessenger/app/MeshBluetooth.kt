package org.yapmessenger.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun encodeToken(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
internal fun decodeToken(text: String): ByteArray = android.util.Base64.decode(text, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

/** Separate v1 service. Legacy YapBluetooth is never interpreted as this protocol. */
@SuppressLint("MissingPermission", "NewApi") // MainActivity grants permissions; start rejects API <29.
internal class MeshBluetooth(
    private val context: Context,
    private val route: () -> ByteArray,
    private val bundle: () -> ByteArray?,
    private val report: (String) -> Unit,
    private val changed: (Int) -> Unit,
    private val visibility: (Boolean) -> Unit = {},
    private val record: (Peer, Int, ByteArray) -> Unit
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("a6f0c9e1-5b2d-4f8e-9a17-3c6d0e2b4201")
        val METADATA: UUID = UUID.fromString("a6f0c9e1-5b2d-4f8e-9a17-3c6d0e2b4202")
        const val HELLO = 1; const val BUNDLE = 2; const val CONFIRM = 3
        const val ENVELOPE = 4; const val ACK = 5; const val KEEPALIVE = 6
        const val MAX_FRAME = 65_537
    }
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val readers = Executors.newFixedThreadPool(8)
    private val writes = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64))
    private val slots = AtomicInteger(0)
    private val peers = ConcurrentHashMap<String, Peer>()
    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val gatts = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothGatt>())
    @Volatile private var running = false
    private var server: BluetoothGattServer? = null
    private var listener: BluetoothServerSocket? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var generation = 0
    private val advertising = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { visibility(true); report("Local mesh is discoverable") }
        override fun onStartFailure(errorCode: Int) { visibility(false); report("Mesh advertising failed ($errorCode). Internet and saved messages remain available.") }
    }
    private val gattServer = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (!running || service.uuid != SERVICE) return
            if (status != BluetoothGatt.GATT_SUCCESS) { report("Could not register local mesh service"); return }
            val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setConnectable(true).build()
            try { advertiser?.startAdvertising(settings, AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).build(), advertising) }
            catch (_: SecurityException) { report("Bluetooth permission changed. Re-enable local mesh.") }
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val psm = listener?.psm ?: return
            val token = route(); if (token.size != 16) return
            val bytes = byteArrayOf(1, (psm shr 8).toByte(), psm.toByte()) + token
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
                    if (!running || bytes == null || bytes.size != 19 || bytes[0] != 1.toByte()) return
                    val remote = bytes.copyOfRange(3, 19)
                    if (remote.contentEquals(route()) || peers.containsKey(encodeToken(remote))) return
                    // One deterministic initiator prevents both phones retaining
                    // opposite simultaneous sockets and closing each other's link.
                    val local = route()
                    val order = local.indices.firstOrNull { local[it] != remote[it] }?.let { (local[it].toInt() and 255) - (remote[it].toInt() and 255) } ?: 0
                    if (order >= 0) return
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
                            accept(socket, remote)
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
            } catch (_: Exception) { report("Bluetooth permission or discovery changed. Re-enable local mesh.") }
        }
        override fun onScanFailed(errorCode: Int) { report("Local discovery failed ($errorCode). Retry local mesh.") }
    }
    fun start() {
        if (Build.VERSION.SDK_INT < 29) { report("Local mesh needs Android 10 or later. Legacy Bluetooth and internet are available."); return }
        if (running) return
        val radio = adapter ?: run { report("Bluetooth is not supported"); return }
        if (!radio.isEnabled) { report("Turn on Bluetooth, then enable local mesh"); return }
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
                    if (server?.addService(service) != true) report("Mesh discovery service is unavailable")
                    scanCycle(token)
                    watch(token)
                }
                while (running && token == generation) { accept(socket.accept(), null) }
            } catch (_: Exception) { if (running && token == generation) report("Local mesh stopped unexpectedly. Retry Bluetooth.") }
        }
    }
    private fun scanCycle(token: Int) {
        if (!running || token != generation) return
        seen.clear()
        try {
            scanner = adapter?.bluetoothLeScanner
            scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scanning)
        } catch (_: Exception) { report("Local discovery is unavailable") }
        main.postDelayed({
            if (running && token == generation) {
                try { scanner?.stopScan(scanning) } catch (_: Exception) {}
                main.postDelayed({ scanCycle(token) }, 15_000)
            }
        }, 45_000)
    }
    private fun watch(token: Int) {
        if (!running || token != generation) return
        val now = SystemClock.elapsedRealtime()
        peers.values.forEach { peer ->
            if (now - peer.lastRead > 25_000 || (peer.writeStarted > 0 && now - peer.writeStarted > 20_000)) peer.close()
            else if (now - peer.lastWrite > 12_000) peer.send(KEEPALIVE, byteArrayOf())
        }
        main.postDelayed({ watch(token) }, 5_000)
    }
    private fun accept(socket: BluetoothSocket, expected: ByteArray?) {
        if (!running) { socket.close(); return }
        if (slots.incrementAndGet() > 8) { slots.decrementAndGet(); socket.close(); return }
        try { val peer = Peer(socket, expected); readers.execute { peer.readLoop() } }
        catch (_: Exception) { slots.decrementAndGet(); try { socket.close() } catch (_: Exception) {} }
    }
    fun connections(): List<Peer> = peers.values.toList()
    fun broadcastBundle() { bundle()?.let { bytes -> peers.values.forEach { it.send(BUNDLE, bytes) } } }
    fun stop() {
        running = false; generation++
        visibility(false)
        try { scanner?.stopScan(scanning) } catch (_: Exception) {}
        try { advertiser?.stopAdvertising(advertising) } catch (_: Exception) {}
        try { listener?.close() } catch (_: Exception) {}; listener = null
        try { server?.close() } catch (_: Exception) {}; server = null
        synchronized(gatts) { gatts.forEach { try { it.disconnect(); it.close() } catch (_: Exception) {} }; gatts.clear() }
        peers.values.toList().forEach { it.close() }; changed(0)
    }
    fun close() { stop(); io.shutdownNow(); readers.shutdownNow(); writes.shutdownNow() }

    inner class Peer(private val socket: BluetoothSocket, private val expected: ByteArray?) {
        var token = ""; private set
        @Volatile var lastRead = SystemClock.elapsedRealtime(); private set
        @Volatile var lastWrite = lastRead; private set
        @Volatile var writeStarted = 0L; private set
        private val closed = AtomicBoolean(false)
        private val input = DataInputStream(socket.inputStream)
        private val output = DataOutputStream(socket.outputStream)
        private val inflight = LinkedHashMap<String, Long>()
        private val frameBudget = AdmissionGate(240, 1024 * 1024)
        private fun write(type: Int, bytes: ByteArray) = synchronized(output) {
            check(!closed.get() && bytes.size + 1 <= MAX_FRAME)
            writeStarted = SystemClock.elapsedRealtime()
            output.writeInt(bytes.size + 1); output.writeByte(type); output.write(bytes); output.flush()
            lastWrite = SystemClock.elapsedRealtime(); writeStarted = 0
        }
        fun send(type: Int, bytes: ByteArray, key: String? = null) {
            if (closed.get()) return
            try {
                writes.execute {
                    try {
                        if (key != null) synchronized(inflight) {
                            val now = SystemClock.elapsedRealtime()
                            if (inflight[key]?.let { now - it < 10_000 } == true) return@execute
                            while (inflight.size >= 1024) inflight.remove(inflight.keys.first())
                            inflight[key] = now
                        }
                        write(type, bytes)
                    } catch (_: Exception) { close() }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { /* Outbox retains bytes for retry. */ }
        }
        fun consumeAck(key: String): Boolean = synchronized(inflight) { inflight.remove(key) != null }
        fun readLoop() {
            val deadline = Runnable { if (token.isEmpty()) close() }
            main.postDelayed(deadline, 12_000)
            try {
                write(HELLO, route())
                var first = true
                while (!closed.get() && running) {
                    val size = input.readInt(); check(size in 1..MAX_FRAME)
                    check(frameBudget.allow(size))
                    val type = input.readUnsignedByte(); val bytes = ByteArray(size - 1); input.readFully(bytes)
                    lastRead = SystemClock.elapsedRealtime()
                    if (first) {
                        check(type == HELLO && bytes.size == 16 && !bytes.contentEquals(route()))
                        check(expected == null || expected.contentEquals(bytes))
                        token = encodeToken(bytes)
                        check(peers.putIfAbsent(token, this) == null)
                        first = false; changed(peers.size)
                        bundle()?.let { send(BUNDLE, it) }
                    } else if (type == KEEPALIVE) check(bytes.isEmpty())
                    else {
                        check(type in BUNDLE..ACK)
                        if (type == BUNDLE) check(bytes.size == 1883)
                        if (type == CONFIRM) check(bytes.size == 101)
                        if (type == ACK) check(bytes.size == 48)
                        record(this, type, bytes)
                    }
                }
            } catch (_: Exception) { /* Reconnect retries durable ciphertext; no inferred receipt. */ }
            finally { main.removeCallbacks(deadline); close(); slots.decrementAndGet() }
        }
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            peers.remove(token, this)
            try { socket.close() } catch (_: Exception) {}
            changed(peers.size)
        }
    }
}
