package org.yapmessenger.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Early development build. Foreground-only Bluetooth discovery and test greeting. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var bluetooth: YapBluetooth
    private var pendingAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetooth = YapBluetooth(this) { message -> runOnUiThread { status.text = message } }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 32)
        }
        layout.addView(TextView(this).apply { text = "yap."; textSize = 36f })
        layout.addView(TextView(this).apply {
            text = "Nobody gets to silence you.\n\nExperimental Bluetooth test. No secure messaging yet."
            textSize = 17f
        })
        status = TextView(this).apply { text = "Ready to find another Yap phone."; textSize = 16f }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = "Make this phone discoverable"
            setOnClickListener { withBluetoothPermissions { bluetooth.startAdvertising() } }
        })
        layout.addView(Button(this).apply {
            text = "Find another Yap phone"
            setOnClickListener { withBluetoothPermissions { bluetooth.scan() } }
        })
        setContentView(layout)
    }

    private fun withBluetoothPermissions(action: () -> Unit) {
        val required = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 100) return
        val action = pendingAction
        pendingAction = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            action?.invoke()
        } else status.text = "Bluetooth permissions are needed for this test."
    }

    override fun onStop() {
        bluetooth.stop()
        super.onStop()
    }
}
