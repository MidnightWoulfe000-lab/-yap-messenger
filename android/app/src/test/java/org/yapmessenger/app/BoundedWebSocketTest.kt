package org.yapmessenger.app

import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okio.ByteString.Companion.toByteString

class BoundedWebSocketTest {
    private fun client() = BoundedWebSocket(OkHttpClient(), Request.Builder().url("https://localhost/yap/v1").build(), object : WebSocketListener() {})
    private fun header(op: Int, length: Int, fin: Boolean = true) = ByteArrayOutputStream().apply {
        val out = DataOutputStream(this); out.writeByte(op or if (fin) 128 else 0)
        when { length < 126 -> out.writeByte(length); length <= 65_535 -> { out.writeByte(126); out.writeShort(length) }; else -> { out.writeByte(127); out.writeLong(length.toLong()) } }
    }.toByteArray()
    private fun rejected(bytes: ByteArray) {
        val error = runCatching { client().readMessages(DataInputStream(bytes.inputStream())) { fail("Unexpected message") } }.exceptionOrNull()
        assertTrue("Reject header before reading missing payload: $error", error is IllegalArgumentException)
    }
    @Test fun oversizedBinaryAndTextRejectBeforePayloadRead() {
        rejected(header(2, 65_586)); rejected(header(1, 60_000))
    }
    @Test fun fragmentedMessageRejectsBeforeExcessPayloadRead() {
        rejected(header(2, 40_000, false) + ByteArray(40_000) + header(0, 30_000))
    }
    @Test fun emptyFragmentFloodIsBounded() {
        rejected(header(2, 0, false) + (1..128).flatMap { header(0, 0, false).toList() }.toByteArray())
    }
    @Test fun boundedFragmentsReassembleExactly() {
        var result: ByteArray? = null
        val wire = header(2, 3, false) + byteArrayOf(1, 2, 3) + header(0, 2) + byteArrayOf(4, 5) + header(8, 0)
        client().readMessages(DataInputStream(wire.inputStream())) { result = it }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result)
    }
    @Test fun writerFailureNotifiesAndClosesInsteadOfStayingConnected() {
        val failed = CountDownLatch(1); val count = AtomicInteger()
        val socket = BoundedWebSocket(OkHttpClient(), Request.Builder().url("https://localhost/yap/v1").build(), object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { count.incrementAndGet(); failed.countDown() }
        })
        BoundedWebSocket::class.java.getDeclaredField("output").apply { isAccessible = true }.set(socket, object : OutputStream() {
            override fun write(value: Int) { throw IOException("Injected connection reset") }
        })
        assertTrue(socket.send(byteArrayOf(1).toByteString()))
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        socket.cancel()
        assertFalse(socket.send(byteArrayOf(1).toByteString()))
        assertEquals(1, count.get())
    }
}
