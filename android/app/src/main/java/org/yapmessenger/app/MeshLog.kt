package org.yapmessenger.app

import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Diagnostic trace for nearby messaging. Written to logcat (`adb logcat -s YapMesh`)
 * and kept in memory (most recent [LIMIT] lines, never on disk) so a tester without
 * a USB cable can copy it from Connections > Advanced.
 * Events only: never log link IDs, addresses, keys or message content, so a log
 * shared for debugging cannot identify or track anyone.
 */
internal object MeshLog {
    private const val TAG = "YapMesh"
    private const val LIMIT = 300
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.UK)

    fun i(message: String) {
        runCatching { Log.i(TAG, message) }
        synchronized(lines) {
            lines.addLast("${time.format(Date())} $message")
            while (lines.size > LIMIT) lines.removeFirst()
        }
    }

    /** The phone model and Android version, then the recent events, oldest first. */
    fun text(): String {
        val body = synchronized(lines) { lines.joinToString("\n") }
        return "Yap nearby log · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            body.ifEmpty { "(no nearby events yet: turn on nearby messaging first)" }
    }
}
