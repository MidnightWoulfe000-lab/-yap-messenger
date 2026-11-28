package org.yapmessenger.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The one-time request to be left out of battery optimisation. Asked once, never repeated. */
internal object BatteryPrompt {
    private const val PREFS = "yap_battery"
    private const val ASKED = "asked"

    /** True only the first time, and only if the app is not already exempt. Records the answer either way. */
    fun shouldAsk(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) return false
        if (context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true) {
            prefs.edit().putBoolean(ASKED, true).apply()
            return false
        }
        return true
    }

    /** Remember that we asked, whatever the person answers. */
    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
    }

    fun request(context: Context) {
        markAsked(context)
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
internal fun BatteryPromptDialog(dismiss: () -> Unit, allow: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Keep nearby messaging running?") },
        text = { Text("Phones often stop apps in the background to save battery. If Yap is stopped, it cannot pass messages to nearby phones. Android will now ask whether Yap may run without battery limits. You can say no and still use Yap, and this is the only time Yap will ask.") },
        confirmButton = { TextButton(onClick = allow) { Text("Continue") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Not now") } })
}

/** Plain, general advice. Phone makers name these settings differently, so it does not pretend to be exact. */
@Composable
internal fun KeepsStoppingHelp() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp)) { Text("If nearby messaging keeps stopping") }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Some phone makers close apps in the background more aggressively than Android does. Settings are named differently on each phone, so look for something close to these:")
        Text("• In the phone's battery or app settings, set Yap to \"Unrestricted\", \"No restrictions\" or \"Don't optimise\".")
        Text("• Turn off any \"Put unused apps to sleep\", \"Adaptive battery\" or similar option for Yap.")
        Text("• Allow Yap to run or start in the background, and to start automatically, if your phone has that option.")
        Text("• Leave Yap's notification visible. It is what lets Android keep nearby messaging running.")
        Text("• Don't swipe Yap away from recent apps on phones that treat that as a force stop.")
        Text("If your phone has a battery saver or \"ultra power saving\" mode, turn it off while you need nearby messaging.")
    }
}
