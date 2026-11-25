package org.yapmessenger.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run with the public localhost fixture server and adb reverse tcp:8443 tcp:8443. */
class InternetIntegrationTest {
    @Test fun authenticatedTlsCustodyMailboxAndRelease() {
        org.junit.Assume.assumeTrue("Enable with -Pandroid.testInstrumentationRunnerArguments.internet_test=true and the fixture server", InstrumentationRegistry.getArguments().getString("internet_test") == "true")
        val pem = InstrumentationRegistry.getInstrumentation().context.assets.open("localhost-ca.pem").bufferedReader().use { it.readText() }
        val opened = CountDownLatch(2); val stored = CountDownLatch(1); val delivered = CountDownLatch(1)
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val now = System.currentTimeMillis()
        val envelope = ByteBuffer.allocate(74).put(byteArrayOf(89, 65, 80, 1)).put(id).put(ByteArray(16) { 1 }).put(ByteArray(16) { 1 })
            .putLong(now).putLong(now + 60_000).put(8).putInt(1).put(42).array()
        val normalized = envelope.clone().also { it[68] = 0 }
        val key = encodeToken(id + MessageDigest.getInstance("SHA-256").digest("Yap relay envelope v1\u0000".toByteArray() + normalized))
        var failure = ""
        val relay = InternetRelay(status = { up, message -> failure = message; if (up) opened.countDown() },
            incoming = { _, _, _ -> fail("Sender cannot poll another mailbox") },
            custody = { assertEquals(key, it); stored.countDown() })
        val recipient = InternetRelay(status = { up, message -> failure = message; if (up) opened.countDown() },
            incoming = { bytes, receivedKey, ack -> assertArrayEquals(envelope.clone().also { it[68] = 7 }, bytes); assertEquals(key, receivedKey); ack(); delivered.countDown() }, custody = {})
        try {
            relay.connect("wss://localhost:8443/yap/v1", "yap_android_public_test_credential_00000001", pem)
            recipient.connect("wss://localhost:8443/yap/v1", "yap_android_public_test_credential_00000002", pem)
            assertTrue("TLS connection: $failure", opened.await(15, TimeUnit.SECONDS))
            relay.offer(envelope, key)
            val acknowledged = stored.await(10, TimeUnit.SECONDS); assertTrue("Custody acknowledgement: $failure", acknowledged)
            recipient.poll(); assertTrue("Mailbox response: $failure", delivered.await(10, TimeUnit.SECONDS))
        } finally { relay.close(); recipient.close() }
    }
}
