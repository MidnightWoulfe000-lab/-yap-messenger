package org.yapmessenger.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class EndpointIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun args(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
    private fun view(repo: EndpointRepository) = repo.call("view").getJSONObject("view")
    private fun packets(repo: EndpointRepository): List<JSONObject> {
        val packets = repo.call("pending", args("force" to true)).getJSONObject("data").getJSONArray("packets")
        return (0 until packets.length()).map(packets::getJSONObject).filter { it.getString("kind") == "envelope" }
    }
    private fun transfer(a: EndpointRepository, b: EndpointRepository) {
        packets(a).forEach {
            val result = b.call("receive", args("bytes" to it.getString("bytes"))).getJSONObject("data")
            a.call("ack", args("key" to result.getString("custody")))
        }
    }
    private fun transferThroughRestartedNativeRelay(a: EndpointRepository, b: EndpointRepository) {
        val path = File(context.noBackupFilesDir, "messaging/test-relay-${UUID.randomUUID()}.redb")
        fun relay(op: String, values: JSONObject = JSONObject()) = a.relay(op, values.put("path", path.absolutePath))
        try {
            packets(a).forEach { packet ->
                relay("relay_admit", args("peer" to encodeToken(ByteArray(16) { 1 }), "bytes" to packet.getString("bytes")))
            }
            relay("relay_close")
            val forwarded = relay("relay_pending", args("peer" to encodeToken(ByteArray(16) { 2 }))).getJSONArray("packets")
            assertTrue(forwarded.length() > 0)
            for (i in 0 until forwarded.length()) {
                val packet = forwarded.getJSONObject(i)
                val result = b.call("receive", args("bytes" to packet.getString("bytes"))).getJSONObject("data")
                relay("relay_ack", args("key" to result.getString("custody")))
                a.call("ack", args("key" to result.getString("custody")))
            }
            assertEquals(0, relay("relay_count").getInt("queued"))
        } finally { relay("relay_close"); path.delete() }
    }
    @Test fun nativePairingEncryptedCommitRestartAndAuthenticatedDelivery() {
        val names = listOf("test-a-${UUID.randomUUID()}", "test-b-${UUID.randomUUID()}")
        try {
            var a = EndpointRepository(EncryptedEndpointStore(context, names[0])).also { it.open() }
            var b = EndpointRepository(EncryptedEndpointStore(context, names[1])).also { it.open() }
            val offerA = view(a).getJSONObject("offer"); val offerB = view(b).getJSONObject("offer")
            val proofA = a.call("pair", args("qr" to offerB.getString("qr"), "bundle" to offerB.getString("bundle"), "name" to "Bob")).getJSONObject("data")
            val proofB = b.call("pair", args("qr" to offerA.getString("qr"), "bundle" to offerA.getString("bundle"), "name" to "Alice")).getJSONObject("data")
            a.call("confirm", args("bytes" to proofB.getString("confirmation")))
            b.call("confirm", args("bytes" to proofA.getString("confirmation")))
            repeat(3) { transfer(a, b); transfer(b, a) }
            assertTrue(view(a).getJSONArray("contacts").getJSONObject(0).getBoolean("ready"))
            val contact = view(a).getJSONArray("contacts").getJSONObject(0).getString("id")
            val text = "Private restart test 🌍 ${UUID.randomUUID()}"
            a.call("send", args("contact" to contact, "text" to text))
            val saved = File(context.noBackupFilesDir, "messaging/${names[0]}.bin").readBytes()
            assertEquals("YKS1", saved.copyOf(4).toString(Charsets.US_ASCII))
            assertFalse(saved.toString(Charsets.ISO_8859_1).contains(text))
            a = EndpointRepository(EncryptedEndpointStore(context, names[0])).also { it.open() }
            b = EndpointRepository(EncryptedEndpointStore(context, names[1])).also { it.open() }
            transferThroughRestartedNativeRelay(a, b); transfer(b, a)
            assertEquals("Delivered", view(a).getJSONArray("messages").getJSONObject(0).getString("status"))
            assertEquals(text, view(b).getJSONArray("messages").getJSONObject(0).getString("body"))
            assertEquals(0, view(a).getInt("queued"))
        } finally { names.forEach(::clean) }
    }
    @Test fun corruptCiphertextAndMissingKeystoreKeyFailClosed() {
        val name = "test-corrupt-${UUID.randomUUID()}"
        try {
            EndpointRepository(EncryptedEndpointStore(context, name)).open()
            val file = File(context.noBackupFilesDir, "messaging/$name.bin")
            val original = file.readBytes(); val bad = original.clone(); bad[bad.lastIndex] = (bad.last().toInt() xor 1).toByte(); file.writeBytes(bad)
            val corrupted = EndpointRepository(EncryptedEndpointStore(context, name))
            assertTrue(runCatching { corrupted.open() }.isFailure); assertTrue(corrupted.locked)
            file.writeBytes(original)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("org.yapmessenger.$name") }
            val lost = EndpointRepository(EncryptedEndpointStore(context, name))
            assertTrue(runCatching { lost.open() }.isFailure); assertTrue(lost.locked)
            assertArrayEquals(original, file.readBytes())
        } finally { clean(name) }
    }
    @Test fun uncertainCommitLocksUntilReloadAndPublishesNoResult() {
        class FaultStore : EndpointSnapshotStore {
            var saved: String? = null; var fail = false
            override fun read() = saved
            override fun write(value: String) { saved = value; if (fail) error("Injected after-write failure") }
        }
        val store = FaultStore(); val repo = EndpointRepository(store); repo.open()
        store.fail = true
        assertTrue(runCatching { repo.call("offer") }.isFailure); assertTrue(repo.locked)
        assertTrue(runCatching { repo.call("pending") }.isFailure)
        store.fail = false; repo.open(); assertFalse(repo.locked)
    }
    private fun clean(name: String) {
        listOf("", ".bak", ".new").forEach { File(context.noBackupFilesDir, "messaging/$name.bin$it").delete() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("org.yapmessenger.$name") }
    }
}
