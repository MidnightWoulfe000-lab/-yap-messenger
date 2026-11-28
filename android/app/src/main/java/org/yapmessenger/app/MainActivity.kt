package org.yapmessenger.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val secure by lazy { SecureModel.get(application) }
    private val profile by lazy { ProfileStore(applicationContext) }
    private val scan = registerForActivityResult(ScanContract()) { result -> result.contents?.let { secure.scannedQr = it } }
    private var permissionAction: (() -> Unit)? = null
    private var batteryPrompt by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(13, 16, 24)),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(13, 16, 24))
        )
        setContent {
            YapTheme {
                YapApp(
                    secure = secure,
                    // Wi-Fi works without Bluetooth permission, so nearby messaging turns on either way.
                    discoverMesh = { withBluetoothPermissions(optional = true) { secure.enableMesh(); if (BatteryPrompt.shouldAsk(this)) batteryPrompt = true } },
                    scan = { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                        // Yap codes are light on dark, so accept inverted codes as well as normal ones.
                        .addExtra(com.google.zxing.client.android.Intents.Scan.SCAN_TYPE, com.google.zxing.client.android.Intents.Scan.MIXED_SCAN)
                        .setCaptureActivity(PortraitCaptureActivity::class.java).setOrientationLocked(true).setPrompt("Scan the other phone's Yap code. Tap the screen to focus.")) },
                    share = { text -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share public pairing package")) },
                    profile = profile)
                if (batteryPrompt) BatteryPromptDialog(
                    dismiss = { batteryPrompt = false; BatteryPrompt.markAsked(this) },
                    allow = { batteryPrompt = false; BatteryPrompt.request(this) })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        secure.setForeground(true)
    }

    private var permissionOptional = false
    private fun withBluetoothPermissions(optional: Boolean = false, action: () -> Unit) {
        val bluetooth = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        // Nearby messaging also asks to show notifications (Android 13+) so new messages
        // can be announced while Yap runs in the background. Optional, like Bluetooth.
        // Nearby Wi-Fi (Aware and Direct) needs its own permission on Android 13+, and
        // location on Android 12 and older. Optional: Bluetooth still works without it.
        val wifi = if (!optional) emptyArray<String>() else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val permissions = (bluetooth + wifi).distinct().toTypedArray()
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            permissionAction = action; permissionOptional = optional
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val action = permissionAction
            permissionAction = null
            val bluetoothGranted = permissions.indices.all { permissions[it] == Manifest.permission.POST_NOTIFICATIONS || grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED }
            if (grantResults.isNotEmpty() && bluetoothGranted) action?.invoke()
            else if (permissionOptional) action?.invoke() // continue with whatever was granted
        }
    }

    override fun onStop() {
        permissionAction = null
        secure.setForeground(false)
        super.onStop()
    }
}
