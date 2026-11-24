package org.yapmessenger.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var bluetooth: YapBluetooth
    private val session by lazy { ViewModelProvider(this)[ChatSession::class.java] }
    private var permissionAction: (() -> Unit)? = null
    private var acceptsBluetoothUpdates = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(13, 16, 24)),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(13, 16, 24))
        )
        bluetooth = YapBluetooth(this) { update ->
            runOnUiThread { if (acceptsBluetoothUpdates) session.onBluetoothUpdate(update) }
        }
        setContent {
            YapTheme {
                YapApp(session,
                    send = { withBluetoothPermissions { session.beginSend()?.let(bluetooth::send) } },
                    discover = { withBluetoothPermissions { bluetooth.startAdvertising() } })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        acceptsBluetoothUpdates = true
    }

    private fun withBluetoothPermissions(action: () -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            permissionAction = action
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val action = permissionAction
            permissionAction = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action?.invoke()
            else session.status = "Bluetooth permission is needed. Allow the requested permissions and try again."
        }
    }

    override fun onStop() {
        acceptsBluetoothUpdates = false
        permissionAction = null
        bluetooth.stop()
        session.onStopped()
        super.onStop()
    }
}
