package org.yapmessenger.app

import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/** RFC 6455 client for Yap's binary-only protocol. No compression or extensions.
 * Frame and cumulative message lengths are checked before reading payload bytes.
 */
internal class BoundedWebSocket(
    private val client: OkHttpClient, private val request: Request,
    private val listener: WebSocketListener
) : WebSocket {
    companion object { private val deadlines = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "yap-wss-deadlines").apply { isDaemon = true } } }
    private val random = SecureRandom()
    private val terminal = AtomicBoolean(false)
    private val writes = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64))
    @Volatile private var transport: Socket? = null
    @Volatile private var cancelled = false
    @Volatile private var output: OutputStream? = null
    override fun request() = request
    override fun queueSize(): Long = writes.queue.size.toLong() * 65_537
    override fun send(text: String): Boolean = false
    override fun send(bytes: ByteString): Boolean {
        if (cancelled || bytes.size > 65_537 || output == null) return false
        return try { writes.execute { try { writeFrame(2, bytes.toByteArray()) } catch (error: Exception) { failed(error, null) } }; true }
        catch (_: java.util.concurrent.RejectedExecutionException) { false }
    }
    override fun close(code: Int, reason: String?): Boolean { cancel(); return true }
    override fun cancel() { cancelled = true; runCatching { transport?.close() }; writes.shutdownNow() }
    private fun failed(error: Exception, response: Response?) {
        if (!cancelled && terminal.compareAndSet(false, true)) listener.onFailure(this, error, response)
        cancel()
    }
    fun start() { Thread({ runConnection() }, "yap-wss-reader").apply { isDaemon = true; start() } }
    private fun runConnection() {
        var response: Response? = null
        try {
            val uri = URI(request.url.toString())
            val host = requireNotNull(uri.host)
            val port = if (uri.port == -1) 443 else uri.port
            val tcp = Socket(); transport = tcp
            if (cancelled) { tcp.close(); return }
            tcp.connect(InetSocketAddress(host, port), 10_000)
            val tls = client.sslSocketFactory.createSocket(tcp, host, port, true) as SSLSocket
            transport = tls
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.soTimeout = 15_000; tls.startHandshake()
            if (cancelled) { tls.close(); return }
            val input = DataInputStream(BufferedInputStream(tls.inputStream, 8192))
            val sink = BufferedOutputStream(tls.outputStream, 8192); output = sink
            val nonce = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
            val authority = request.url.let { if (it.port == 443) it.host else "${it.host}:${it.port}" }
            val header = "GET /yap/v1 HTTP/1.1\r\nHost: $authority\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $nonce\r\nSec-WebSocket-Version: 13\r\nAuthorization: ${request.header("Authorization")}\r\n\r\n"
            sink.write(header.toByteArray(Charsets.US_ASCII)); sink.flush()
            val status = readLine(input); val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP status")
            val headers = Headers.Builder(); var total = status.length
            while (true) {
                val line = readLine(input); total += line.length + 2; require(total <= 16_384)
                if (line.isEmpty()) break
                val colon = line.indexOf(':'); require(colon > 0)
                headers.add(line.substring(0, colon), line.substring(colon + 1).trim())
            }
            val parsed = headers.build()
            response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("WebSocket upgrade").headers(parsed).build()
            require(code == 101 && parsed["Upgrade"].equals("websocket", true) && parsed["Connection"]?.split(',')?.any { it.trim().equals("upgrade", true) } == true)
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
            require(parsed["Sec-WebSocket-Accept"] == accept && parsed["Sec-WebSocket-Extensions"] == null && parsed["Sec-WebSocket-Protocol"] == null)
            listener.onOpen(this, response)
            readMessages(input) { listener.onMessage(this, it.toByteString()) }
            if (!cancelled && terminal.compareAndSet(false, true)) listener.onClosed(this, 1000, "")
        } catch (error: Exception) { failed(error, response) }
        finally { cancel() }
    }
    @Synchronized private fun writeFrame(opcode: Int, bytes: ByteArray) {
        val deadline = deadlines.schedule({ failed(IOException("WebSocket write timeout"), null) }, 15, TimeUnit.SECONDS)
        try {
        val sink = output ?: error("Not connected")
        sink.write(0x80 or opcode)
        when {
            bytes.size < 126 -> sink.write(0x80 or bytes.size)
            bytes.size <= 65_535 -> { sink.write(0x80 or 126); sink.write(bytes.size ushr 8); sink.write(bytes.size) }
            else -> { sink.write(0x80 or 127); for (shift in 56 downTo 0 step 8) sink.write((bytes.size.toLong() ushr shift).toInt()) }
        }
        val mask = ByteArray(4).also(random::nextBytes); sink.write(mask)
        val masked = ByteArray(bytes.size) { (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() }
        sink.write(masked); sink.flush()
        } finally { deadline.cancel(false) }
    }
    private fun readLine(input: DataInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.readUnsignedByte()
            if (value == 13) { require(input.readUnsignedByte() == 10); return bytes.toString("US-ASCII") }
            require(value in 32..126 && bytes.size() < 4096); bytes.write(value)
        }
    }
    internal fun readMessages(input: DataInputStream, deliver: (ByteArray) -> Unit) {
        var message: ByteArrayOutputStream? = null
        var fragments = 0
        while (!cancelled) {
            val first = input.readUnsignedByte(); val second = input.readUnsignedByte()
            val fin = first and 128 != 0; val opcode = first and 15
            require(first and 112 == 0 && second and 128 == 0) { "Unexpected WebSocket extensions/masking" }
            var length = (second and 127).toLong()
            if (length == 126L) { length = input.readUnsignedShort().toLong(); require(length >= 126) }
            else if (length == 127L) { length = input.readLong(); require(length >= 65_536) }
            val control = opcode >= 8
            if (control) require(fin && length <= 125)
            else {
                require(opcode == 2 && message == null || opcode == 0 && message != null) { "Binary messages only" }
                require(length <= 65_585L - (message?.size() ?: 0)) { "WebSocket message exceeds limit" }
                if (message == null) { message = ByteArrayOutputStream(); fragments = 0 }
                require(++fragments <= 128) { "Too many WebSocket fragments" }
            }
            require(length in 0..65_585)
            val payload = ByteArray(length.toInt()); input.readFully(payload)
            when (opcode) {
                8 -> return
                9 -> writeFrame(10, payload)
                10 -> Unit
                0, 2 -> { message!!.write(payload); if (fin) { deliver(message.toByteArray()); message = null } }
                else -> error("Unsupported WebSocket opcode")
            }
        }
    }
}
