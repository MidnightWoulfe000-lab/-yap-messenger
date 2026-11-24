package org.yapmessenger.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Foreground-only BLE experiment. A device advertises a Yap service and serves
 * a fixed test greeting. Nearby Yap devices can discover it and read that greeting.
 * There is no authentication or encryption at the application layer yet.
 */
@SuppressLint("MissingPermission") // Permissions are checked by MainActivity before calling.
class YapBluetooth(
    context: Context,
    private val report: (String) -> Unit
) {
    companion object {
        val SERVICE_ID: UUID = UUID.fromString("a6f0c9e1-5b2d-4f8e-9a17-3c6d0e2b4101")
        val GREETING_ID: UUID = UUID.fromString("a6f0c9e1-5b2d-4f8e-9a17-3c6d0e2b4102")
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val main = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var client: BluetoothGatt? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var advertising = false
    private val seen = mutableSetOf<String>()

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val greeting = "Hello from Yap!".toByteArray(Charsets.UTF_8)
            if (characteristic.uuid != GREETING_ID || offset > greeting.size) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, greeting.copyOfRange(offset, greeting.size))
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            main.post { report("Yap is visible to nearby phones.") }
        }
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            main.post { report("Couldn't advertise Yap (error $errorCode).") }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { report("Connected to a nearby Yap device. Reading test greeting...") }
                if (!gatt.discoverServices()) {
                    main.post { report("Couldn't discover the Yap service.") }
                    gatt.close()
                }
            } else {
                main.post { report("Connection ended (status $status).") }
                gatt.close()
                if (client === gatt) client = null
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(SERVICE_ID)?.getCharacteristic(GREETING_ID)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || !gatt.readCharacteristic(characteristic)) {
                main.post { report("Couldn't read the Yap test greeting.") }
                gatt.disconnect()
            }
        }
        @Deprecated("Required for Android versions below 13")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (android.os.Build.VERSION.SDK_INT < 33) showGreeting(gatt, status, characteristic.value)
        }
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, status: Int
        ) {
            showGreeting(gatt, status, value)
        }
    }

    private fun showGreeting(gatt: BluetoothGatt, status: Int, value: ByteArray) {
        main.post {
            report(if (status == BluetoothGatt.GATT_SUCCESS) {
                "Received over Bluetooth: ${value.toString(Charsets.UTF_8)}"
            } else "Couldn't read greeting (status $status).")
        }
        gatt.disconnect()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            if (!seen.add(address)) return
            main.post { report("Found ${seen.size} Yap device(s). Connecting...") }
            if (client == null) {
                stopScan()
                client = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            main.post { report("Yap discovery failed (error $errorCode).") }
        }
    }

    fun startAdvertising() {
        val bluetooth = adapter ?: run { report("Bluetooth isn't supported."); return }
        if (!bluetooth.isEnabled) { report("Please turn on Bluetooth."); return }
        if (advertising) return
        val bleAdvertiser = bluetooth.bluetoothLeAdvertiser
            ?: run { report("This phone can't advertise over BLE."); return }
        val gattServer = manager.openGattServer(context, serverCallback)
            ?: run { report("Couldn't start the Yap Bluetooth service."); return }
        val service = BluetoothGattService(SERVICE_ID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(
            GREETING_ID, BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ))
        if (!gattServer.addService(service)) {
            gattServer.close()
            report("Couldn't register the Yap Bluetooth service.")
            return
        }
        server = gattServer
        advertiser = bleAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_ID))
            .setIncludeDeviceName(false).build()
        bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
        report("Starting Yap discovery service...")
    }

    fun scan() {
        val bluetooth = adapter ?: run { report("Bluetooth isn't supported."); return }
        if (!bluetooth.isEnabled) { report("Please turn on Bluetooth."); return }
        if (scanning) return
        val bleScanner = bluetooth.bluetoothLeScanner
            ?: run { report("BLE scanning isn't available."); return }
        scanner = bleScanner
        seen.clear()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_ID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
        report("Looking for another phone running Yap...")
        main.postDelayed({ stopScan() }, 15_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
    }

    fun stop() {
        stopScan()
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: SecurityException) { }
        advertising = false
        client?.disconnect()
        client?.close()
        client = null
        server?.close()
        server = null
    }
}
