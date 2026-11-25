package org.yapmessenger.app

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface EndpointSnapshotStore {
    fun read(): String?
    fun write(value: String)
}

/** No plaintext state/history files. Missing keys for existing data fail closed. */
internal class EncryptedEndpointStore(context: Context, private val name: String = "endpoint-v1") : EndpointSnapshotStore {
    private val directory = File(context.noBackupFilesDir, "messaging").apply { check(isDirectory || mkdirs()) }
    private val file = AtomicFile(File(directory, "$name.bin"))
    private val alias = "org.yapmessenger.$name"
    private val aad = "Yap endpoint snapshot v1".toByteArray(Charsets.UTF_8)
    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "The saved identity key is missing. Restore is not supported; do not reset automatically." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override fun read(): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.openRead().use { input ->
            val result = input.readBytesLimited(8 * 1024 * 1024 + 64)
            check(result.size >= 32 && result.copyOfRange(0, 4).contentEquals("YKS1".toByteArray())) { "Unknown identity file format" }
            result
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
        cipher.updateAAD(aad)
        return cipher.doFinal(bytes.copyOfRange(16, bytes.size)).toString(Charsets.UTF_8)
    }
    override fun write(value: String) {
        val plain = value.toByteArray(Charsets.UTF_8)
        check(plain.size <= 8 * 1024 * 1024) { "Encrypted identity storage is full" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // An existing file may never silently acquire a replacement key.
        cipher.init(Cipher.ENCRYPT_MODE, key(!file.baseFile.exists()))
        cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        val bytes = "YKS1".toByteArray() + cipher.iv + cipher.doFinal(plain)
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
            output = null
            // AtomicFile logs some filesystem failures rather than throwing.
            // Verify publication; any uncertainty locks the repository until reload.
            check(file.baseFile.inputStream().use { it.readBytesLimited(bytes.size + 1) }.contentEquals(bytes)) { "Identity commit could not be verified" }
            val synced = JSONObject(NativeBridge.execute(JSONObject().put("op", "storage_sync")
                .put("args", JSONObject().put("path", directory.absolutePath)).toString(), System.currentTimeMillis()))
            check(synced.optBoolean("ok")) { "Identity directory sync failed" }
        } catch (error: Exception) {
            output?.let(file::failWrite)
            throw error
        }
    }
}

internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(out.size() + count <= limit) { "Input exceeds storage limit" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

/** Caller owns one serial worker. No result escapes before encrypted commit. */
internal class EndpointRepository(private val store: EndpointSnapshotStore, private val wall: () -> Long = System::currentTimeMillis) {
    private val startedWall = wall()
    private val startedElapsed = SystemClock.elapsedRealtime()
    private var effective = startedWall
    private var lastElapsed = startedElapsed
    private var document: JSONObject? = null
    var locked = false
        private set
    private fun now(): Long {
        val elapsed = SystemClock.elapsedRealtime()
        effective = maxOf(effective + (elapsed - lastElapsed).coerceAtLeast(0), wall(), startedWall + elapsed - startedElapsed)
        lastElapsed = elapsed
        return effective
    }
    fun open(): JSONObject {
        locked = true
        val saved = store.read()
        document = saved?.let(::JSONObject)
        document?.let { check(it.getInt("format") == 1) { "Unsupported saved identity version" } }
        locked = false
        return try { call(if (saved == null) "init" else "view") } catch (error: Exception) { locked = true; throw error }
    }
    fun call(operation: String, arguments: JSONObject = JSONObject()): JSONObject {
        check(!locked) { "Identity storage is locked. Retry opening it before sending." }
        val request = JSONObject().put("op", operation).put("args", arguments)
        document?.let { request.put("state", it.getJSONObject("endpoint")) }
        val result = JSONObject(NativeBridge.execute(request.toString(), now()))
        check(result.optBoolean("ok")) { result.optString("error", "Native operation rejected") }
        val next = JSONObject().put("format", 1).put("endpoint", result.getJSONObject("state"))
            .put("settings", document?.optJSONObject("settings") ?: JSONObject())
        try { store.write(next.toString()) } catch (error: Exception) { locked = true; throw error }
        document = next
        return result
    }
    fun settings(): JSONObject = JSONObject(document?.optJSONObject("settings")?.toString() ?: "{}")
    fun saveSettings(value: JSONObject) {
        check(!locked)
        val next = JSONObject(document?.toString() ?: error("Open identity first")).put("settings", value)
        try { store.write(next.toString()) } catch (error: Exception) { locked = true; throw error }
        document = next
    }
    fun relay(operation: String, arguments: JSONObject): JSONObject {
        check(!locked)
        val result = JSONObject(NativeBridge.execute(JSONObject().put("op", operation).put("args", arguments).toString(), now()))
        check(result.optBoolean("ok")) { result.optString("error", "Relay operation rejected") }
        return result.optJSONObject("data") ?: JSONObject()
    }
}
