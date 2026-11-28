package org.yapmessenger.app

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager

/**
 * Restarts nearby messaging after the phone restarts.
 *
 * It starts [MeshService] only if all of these hold:
 *  1. the user's saved "nearby messaging" setting is on,
 *  2. credential-encrypted storage is unlocked (the setting is kept in it), and
 *  3. a nearby radio permission is still granted (the same test the app uses before it
 *     starts Bluetooth or Wi-Fi).
 *
 * The service is not changed. It shows its usual notification, which has the usual
 * "Pause" action to stop it.
 *
 * Foreground-service type: [MeshService] declares `connectedDevice`. Android 15 forbids
 * starting only these types from a BOOT_COMPLETED receiver: dataSync, camera, mediaPlayback,
 * phoneCall, mediaProjection and microphone. `connectedDevice` is not on that list, and
 * Android 12 to 14 exempt a BOOT_COMPLETED broadcast from the background-start ban. Android 14
 * also needs one of the type's prerequisite permissions; the manifest declares
 * CHANGE_NETWORK_STATE, which counts. This is read from Android's published rules and has not
 * been run on a real phone. If Android refuses anyway, [MeshService] already catches that
 * and stops itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as Application
        if (!canStartNearby(app)) return
        // The saved setting sits inside Yap's encrypted store, which opens on a background
        // thread. Wait briefly for it, and give up quietly if it stays closed.
        val pending = goAsync()
        val secure = SecureModel.get(app)
        val handler = Handler(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        val check = object : Runnable {
            override fun run() {
                when {
                    secure.meshEnabled && !secure.locked -> { MeshService.start(app); pending.finish() }
                    System.currentTimeMillis() >= deadline -> pending.finish()
                    else -> handler.postDelayed(this, POLL_MS)
                }
            }
        }
        handler.post(check)
    }

    companion object {
        private const val OPEN_TIMEOUT_MS = 20_000L
        private const val POLL_MS = 250L

        internal fun canStartNearby(context: Context): Boolean {
            val users = context.getSystemService(UserManager::class.java)
            if (users == null || !users.isUserUnlocked) return false
            return bluetoothPermitted(context) || proximityWifiPermitted(context)
        }

        private fun granted(context: Context, permission: String) =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        private fun bluetoothPermitted(context: Context) =
            if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE).all { granted(context, it) }
            else granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

        private fun proximityWifiPermitted(context: Context) =
            granted(context, if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
