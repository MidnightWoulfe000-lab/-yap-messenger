package org.yapmessenger.app

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Early BLE discovery experiment. No messages or encryption are transmitted. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val bluetooth by lazy { getSystemService(BluetoothManager::class.java).adapter }
    private var scanning = false
    private val devices = mutableSetOf<String>()
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Count only. Never display or persist nearby device addresses.
            devices.add(result.device.address)
            status.text = "Nearby Bluetooth devices detected: ${devices.size}\nThese are not necessarily Yap users."
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status.text = "Bluetooth scan failed (code $errorCode)."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 32)
        }
        layout.addView(TextView(this).apply { text = "yap."; textSize = 36f })
        layout.addView(TextView(this).apply { text = "Nobody gets to silence you.\n\nEarly development build. Not secure for messaging."; textSize = 17f })
        status = TextView(this).apply { text = "Ready to test Bluetooth discovery."; textSize = 16f }
        layout.addView(status)
        layout.addView(Button(this).apply { text = "Scan nearby devices"; setOnClickListener { requestScan() } })
        setContentView(layout)
    }

    private fun requestScan() {
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(permission), 100)
            return
        }
        startScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
        else if (requestCode == 100) status.text = "Bluetooth permission is needed to scan."
    }

    private fun startScan() {
        if (scanning) return
        val adapter: BluetoothAdapter = bluetooth ?: run { status.text = "Bluetooth isn't available on this phone."; return }
        if (!adapter.isEnabled) { status.text = "Turn on Bluetooth, then try again."; return }
        val scanner = adapter.bluetoothLeScanner ?: run { status.text = "Bluetooth scanning isn't available."; return }
        devices.clear()
        try {
            scanner.startScan(callback)
            scanning = true
            status.text = "Scanning for 10 seconds..."
            handler.postDelayed({ stopScan() }, 10_000)
        } catch (_: SecurityException) { status.text = "Bluetooth permission wasn't granted." }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { bluetooth?.bluetoothLeScanner?.stopScan(callback) } catch (_: SecurityException) { /* permission revoked */ }
        status.append("\nScan finished.")
    }

    override fun onStop() {
        stopScan()
        super.onStop()
    }
}
