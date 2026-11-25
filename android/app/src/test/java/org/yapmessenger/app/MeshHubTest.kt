package org.yapmessenger.app

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class MeshHubTest {
    private val hubs = mutableListOf<MeshHub>()
    private val sockets = mutableListOf<Socket>()
    private val received = CopyOnWriteArrayList<Pair<Int, ByteArray>>()

    private fun hub(clock: () -> Long = { System.nanoTime() / 1_000_000 }, identity: LinkIdentity? = null) =
        MeshHub(report = {}, changed = {}, record = { _, type, bytes -> received += type to bytes }, elapsed = clock,
            identity = identity ?: LinkIdentity(clock), helloTimeoutMs = 2_000).also { it.start(); hubs += it }

    /** Connect two hubs with a real loopback TCP socket, like the Wi-Fi transport. */
    private fun link(a: MeshHub, b: MeshHub, transport: MeshTransport = MeshTransport.WIFI) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            val accepted = server.accept()
            sockets += client; sockets += accepted
            a.attach(MeshStream(client.getInputStream(), client.getOutputStream(), client), transport, outbound = true)
            b.attach(MeshStream(accepted.getInputStream(), accepted.getOutputStream(), accepted), transport, outbound = false)
        }
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < until) { "condition not met" }; Thread.sleep(20) }
    }

    @After fun tearDown() { hubs.forEach { it.close() }; sockets.forEach { runCatching { it.close() } } }

    @Test fun linksAndDeliversFrames() {
        val a = hub(); val b = hub()
        link(a, b)
        waitFor { a.connections().size == 1 && b.connections().size == 1 }
        assertEquals(meshToken(b.identity.current()), a.connections().single().token)
        a.connections().single().send(MeshFrames.ENVELOPE, ByteArray(100) { 7 })
        waitFor { received.any { it.first == MeshFrames.ENVELOPE } }
        assertArrayEquals(ByteArray(100) { 7 }, received.first { it.first == MeshFrames.ENVELOPE }.second)
    }

    @Test fun helloNeverContainsAnythingButTheRandomLinkId() {
        val a = hub()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = Socket(InetAddress.getLoopbackAddress(), server.localPort); sockets += client
            val accepted = server.accept(); sockets += accepted
            a.attach(MeshStream(accepted.getInputStream(), accepted.getOutputStream(), accepted), MeshTransport.WIFI, outbound = false)
            val input = DataInputStream(client.getInputStream())
            val size = input.readInt(); val type = input.readUnsignedByte()
            val body = ByteArray(size - 1).also { input.readFully(it) }
            assertEquals(MeshFrames.HELLO, type)
            assertEquals(MeshFrames.HELLO_BYTES, body.size)
            assertArrayEquals(a.identity.current(), body.copyOfRange(0, MeshFrames.ID_BYTES))
        }
    }

    @Test fun eachStartUsesAFreshLinkId() {
        val a = hub()
        val first = a.identity.current()
        a.stop(); a.start()
        assertFalse(first.contentEquals(a.identity.current()))
        assertTrue("previous ID still recognised as our own", a.identity.isOwn(first))
    }

    @Test fun idRotatesOnSchedule() {
        val now = AtomicLong(0)
        val identity = LinkIdentity({ now.get() }, minimumMs = 1_000, jitterMs = 0)
        val before = identity.current()
        assertFalse(identity.rotateIfDue())
        now.set(1_001)
        assertTrue(identity.rotateIfDue())
        assertFalse(before.contentEquals(identity.current()))
    }

    @Test fun rejectsOwnReflectedHello() {
        val a = hub()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = Socket(InetAddress.getLoopbackAddress(), server.localPort); sockets += client
            val accepted = server.accept(); sockets += accepted
            a.attach(MeshStream(accepted.getInputStream(), accepted.getOutputStream(), accepted), MeshTransport.WIFI, outbound = false)
            DataOutputStream(client.getOutputStream()).apply { writeInt(1 + MeshFrames.HELLO_BYTES); writeByte(MeshFrames.HELLO); write(a.identity.current() + ByteArray(8)); flush() }
            Thread.sleep(300)
            assertTrue(a.connections().isEmpty())
        }
    }

    @Test fun rejectsOversizedAndUnknownFrames() {
        val a = hub()
        for (frame in listOf(MeshFrames.MAX_FRAME + 1 to MeshFrames.ENVELOPE, 2 to 99)) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val client = Socket(InetAddress.getLoopbackAddress(), server.localPort); sockets += client
                val accepted = server.accept(); sockets += accepted
                a.attach(MeshStream(accepted.getInputStream(), accepted.getOutputStream(), accepted), MeshTransport.WIFI, outbound = false)
                val out = DataOutputStream(client.getOutputStream())
                out.writeInt(1 + MeshFrames.HELLO_BYTES); out.writeByte(MeshFrames.HELLO); out.write(ByteArray(24) { 9 }); out.flush()
                waitFor { a.connections().size == 1 }
                out.writeInt(frame.first); out.writeByte(frame.second); out.write(ByteArray(1)); out.flush()
                waitFor { a.connections().isEmpty() }
            }
        }
        assertTrue(received.isEmpty())
    }

    @Test fun bothSidesKeepTheSameLinkWhenTwoAreOpened() {
        val a = hub(); val b = hub()
        link(a, b); link(b, a)
        waitFor { a.connections().size == 1 && b.connections().size == 1 }
        Thread.sleep(300) // let the losing link close on both sides
        assertEquals(1, a.connections().size); assertEquals(1, b.connections().size)
        // The surviving link must work in both directions.
        a.connections().single().send(MeshFrames.ACK, ByteArray(48) { 1 })
        b.connections().single().send(MeshFrames.ACK, ByteArray(48) { 2 })
        waitFor { received.count { it.first == MeshFrames.ACK } == 2 }
    }

    @Test fun prefersWifiOverBluetoothForTheSamePhone() {
        val a = hub(); val b = hub()
        link(a, b, MeshTransport.BLUETOOTH)
        waitFor { a.connections().size == 1 }
        link(a, b, MeshTransport.WIFI)
        waitFor { a.connections().singleOrNull()?.transport == MeshTransport.WIFI && b.connections().singleOrNull()?.transport == MeshTransport.WIFI }
    }

    @Test fun bundleRequestFrameMustBeExactly32Bytes() {
        val a = hub(); val b = hub()
        link(a, b)
        waitFor { a.connections().size == 1 && b.connections().size == 1 }
        a.connections().single().send(MeshFrames.BUNDLE_REQUEST, ByteArray(32) { 3 })
        waitFor { received.any { it.first == MeshFrames.BUNDLE_REQUEST } }
        a.connections().single().send(MeshFrames.BUNDLE_REQUEST, ByteArray(33))
        waitFor { b.connections().isEmpty() }
        assertEquals(1, received.count { it.first == MeshFrames.BUNDLE_REQUEST })
    }

    @Test fun wifiServiceNameRoundTripsAndRejectsJunk() {
        val id = ByteArray(16) { (it * 17).toByte() }
        assertArrayEquals(id, MeshWifi.linkIdFromName(MeshWifi.nameFor(id)))
        assertNull(MeshWifi.linkIdFromName("yap-xyz"))
        assertNull(MeshWifi.linkIdFromName("printer"))
        assertNull(MeshWifi.linkIdFromName("yap-" + "G".repeat(32)))
    }

    @Test fun unsignedCompareOrdersHighBytesLast() {
        assertTrue(compareBytes(byteArrayOf(1), byteArrayOf(-1)) < 0)
        assertEquals(0, compareBytes(byteArrayOf(5, 6), byteArrayOf(5, 6)))
    }

}
