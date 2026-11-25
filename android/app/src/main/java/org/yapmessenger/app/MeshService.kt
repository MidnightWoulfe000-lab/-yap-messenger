package org.yapmessenger.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps nearby messaging (Bluetooth and Wi-Fi) running when no Yap screen is open,
 * so a phone in a pocket still receives and carries messages.
 *
 * Android only allows this as a "foreground service" with a visible notification.
 * Both notifications are deliberately generic and hidden on the lock screen: they
 * never show a contact name or message text, because phones get seen and seized.
 *
 * A partial wake lock keeps the CPU awake so links, retries and the outbox keep
 * moving while the screen is off. This costs battery; "Pause" in the notification
 * (or in Connections) turns nearby messaging off and releases it.
 */
class MeshService : Service() {
    companion object {
        private const val RUNNING_CHANNEL = "nearby"
        private const val MESSAGE_CHANNEL = "messages"
        private const val RUNNING_ID = 1
        private const val MESSAGE_ID = 2
        private const val ACTION_PAUSE = "org.yapmessenger.app.PAUSE_NEARBY"
        private const val WAKE_RENEW_MS = 10 * 60_000L

        /** Call only while a Yap screen is visible: Android 12+ refuses background starts. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MeshService::class.java)) }
        }
        fun stop(context: Context) { runCatching { context.stopService(Intent(context, MeshService::class.java)) } }

        fun notifyNewMessage(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            channels(manager)
            val notification = Notification.Builder(context, MESSAGE_CHANNEL)
                .setSmallIcon(R.drawable.ic_yap_foreground)
                .setContentTitle("New message")
                .setContentText("Open Yap to read it")
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
            runCatching { manager.notify(MESSAGE_ID, notification) } // denied notification permission: skip quietly
        }
        fun clearNewMessage(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(MESSAGE_ID)
        }

        private fun channels(manager: NotificationManager) {
            manager.createNotificationChannel(NotificationChannel(RUNNING_CHANNEL, "Nearby messaging", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Yap passes messages to nearby phones"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            })
            manager.createNotificationChannel(NotificationChannel(MESSAGE_CHANNEL, "New messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A generic alert; never shows who sent it or what it says"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
        }
        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val renew = object : Runnable {
        override fun run() { wakeLock?.acquire(WAKE_RENEW_MS + 60_000L); main.postDelayed(this, WAKE_RENEW_MS) }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        channels(manager)
        val pause = PendingIntent.getService(this, 1, Intent(this, MeshService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, RUNNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_yap_foreground)
            .setContentTitle("Nearby messaging is on")
            .setContentText("Passing encrypted messages to nearby phones")
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .setContentIntent(openApp(this))
            .addAction(Notification.Action.Builder(null, "Pause", pause).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(RUNNING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(RUNNING_ID, notification)
        } catch (_: Exception) {
            // Android refused (for example a background start). Stay off rather than crash.
            stopSelf(); return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Yap:nearby").apply { setReferenceCounted(false) }
        renew.run()
        SecureModel.get(application as Application).setBackground(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            SecureModel.get(application as Application).disableMesh() // also stops this service
            return START_NOT_STICKY
        }
        // If Android kills the process, restart it so nearby messaging resumes.
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(renew)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        SecureModel.get(application as Application).setBackground(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
