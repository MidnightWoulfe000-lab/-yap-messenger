package org.yapmessenger.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Debug builds only. The About screen can fetch the newest APK from the repository's rolling
 * `debug-latest` GitHub release and hand it to Android's installer. Nothing is contacted until
 * you tap "Check for update". Release builds never show this section.
 */
internal data class UpdateOffer(val versionCode: Long, val url: String, val size: Long, val sha256: String?)

internal object UpdateFeed {
    private val assetName = Regex("""yap-debug-(\d+)\.apk""")
    const val MAX_BYTES = 300L * 1024 * 1024

    /** Reads GitHub's release JSON and returns the APK asset, or null if there is none we trust. */
    fun parse(json: String): UpdateOffer? {
        val assets = JSONObject(json).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val code = assetName.matchEntire(asset.optString("name"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val url = asset.optString("browser_download_url")
            if (!url.startsWith("https://github.com/")) continue
            val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                ?.takeIf { it.length == 64 }
            return UpdateOffer(code, url, asset.optLong("size"), digest?.lowercase())
        }
        return null
    }
}

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val code: Long) : UpdateState
    data class Available(val offer: UpdateOffer) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Failed(val message: String) : UpdateState
    data class Note(val message: String) : UpdateState
}

private val updateClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

@Composable
internal fun DebugUpdateSection() {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val installed = remember { installedVersionCode(context) }

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            state = try {
                val offer = withContext(Dispatchers.IO) { fetchLatest() }
                when {
                    offer == null -> UpdateState.Failed("No debug build has been published yet.")
                    offer.versionCode > installed -> UpdateState.Available(offer)
                    else -> UpdateState.UpToDate(installed)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { UpdateState.Failed("Could not check for updates: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun install(offer: UpdateOffer) {
        state = UpdateState.Downloading(0)
        scope.launch {
            state = try {
                // Network and file work must stay off the main thread, or Android throws NetworkOnMainThreadException.
                val apk = withContext(Dispatchers.IO) {
                    val file = download(context, offer) { state = UpdateState.Downloading(it) }
                    checkSignature(context, file)?.let { throw IllegalStateException(it) }
                    file
                }
                UpdateState.Note(launchInstaller(context, apk) ?: "Android's installer is open. Confirm the update there.")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { UpdateState.Failed(e.message ?: "The download failed (${e.javaClass.simpleName}).") }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Debug build updates", style = MaterialTheme.typography.titleMedium, color = White)
        Text("Build $installed. Fetches the latest debug APK from GitHub when you ask.", color = Soft)
        when (val s = state) {
            UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Failed, is UpdateState.Note, is UpdateState.Checking -> {
                OutlinedButton(onClick = ::check, enabled = s !is UpdateState.Checking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (s is UpdateState.Checking) "Checking…" else "Check for update")
                }
                when (s) {
                    is UpdateState.UpToDate -> Text("You have the latest build (${s.code}).", color = Lime)
                    is UpdateState.Failed -> Text(s.message, color = Coral)
                    is UpdateState.Note -> Text(s.message, color = Soft)
                    else -> Unit
                }
            }
            is UpdateState.Available -> {
                Text("Build ${s.offer.versionCode} is available.", color = Lime)
                Button(onClick = { install(s.offer) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Download and install")
                }
            }
            is UpdateState.Downloading -> {
                Text("Downloading… ${s.percent}%", color = Soft)
                LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun fetchLatest(): UpdateOffer? {
    val url = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/debug-latest"
    val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
    updateClient.newCall(request).execute().use { response ->
        if (response.code == 404) return null
        check(response.isSuccessful) { "GitHub answered ${response.code}" }
        return UpdateFeed.parse(response.body?.string() ?: return null)
    }
}

private fun download(context: Context, offer: UpdateOffer, progress: (Int) -> Unit): File {
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val target = File(dir, "yap-update.apk")
    val digest = MessageDigest.getInstance("SHA-256")
    updateClient.newCall(Request.Builder().url(offer.url).build()).execute().use { response ->
        check(response.isSuccessful) { "Download failed (GitHub answered ${response.code})." }
        val body = response.body ?: error("The download was empty.")
        val total = body.contentLength().takeIf { it > 0 } ?: offer.size
        check(total <= UpdateFeed.MAX_BYTES) { "The update is larger than expected, so it was not downloaded." }
        var read = 0L
        val buffer = ByteArray(64 * 1024)
        body.byteStream().use { input ->
            target.outputStream().use { output ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    read += n
                    check(read <= UpdateFeed.MAX_BYTES) { "The update is larger than expected, so it was stopped." }
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    if (total > 0) progress((read * 100 / total).toInt().coerceIn(0, 100))
                }
            }
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (offer.sha256 != null && offer.sha256 != actual) {
        target.delete()
        error("The download did not match GitHub's checksum, so it was discarded.")
    }
    return target
}

/** Returns an explanation if Android would refuse this APK, or null if it looks installable. */
private fun checkSignature(context: Context, apk: File): String? {
    val pm = context.packageManager
    if (Build.VERSION.SDK_INT < 28) return null
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return "Android could not read the downloaded APK."
    if (theirs.packageName != context.packageName) return "The download is a different app, so it was not installed."
    val theirKeys = theirs.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: return null
    val ourKeys = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: return null
    return if (theirKeys == ourKeys) null
    else "This update is signed with a different key, so Android will not install it over this build. Uninstall Yap once and install the new build by hand; later updates will work."
}

private fun launchInstaller(context: Context, apk: File): String? {
    if (!context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Allow Yap to install apps on the settings screen that just opened, come back, then tap Download and install again."
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    return null
}

@Suppress("DEPRECATION")
private fun installedVersionCode(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
