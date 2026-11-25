package org.yapmessenger.app

import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Native envelope bytes are unchanged across Bluetooth and this TLS link. */
internal class InternetRelay(
    private val status: (Boolean, String) -> Unit,
    private val incoming: (ByteArray, String, () -> Unit) -> Unit,
    private val custody: (String) -> Unit
) {
    companion object {
        fun validate(url: String, credential: String, ca: String = "") {
            val uri = URI(url)
            require(url.length <= 2048 && uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.path == "/yap/v1" && uri.rawQuery == null && uri.userInfo == null && uri.fragment == null) {
                "Use wss://hostname:port/yap/v1 without URL credentials or query parameters"
            }
            require(credential.length in 32..128 && credential.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) {
                "Relay credential must be 32–128 base64url characters"
            }
            if (ca.isNotBlank()) certificate(ca)
        }
        private fun certificate(pem: String): X509Certificate {
            require(pem.length <= 8192 && pem.trim().matches(Regex("-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\r\\n]+-----END CERTIFICATE-----"))) { "Provide one public PEM CA certificate, never a private key" }
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
            require(cert.basicConstraints >= 0) { "The supplied certificate is not a CA" }; cert.checkValidity(); return cert
        }
        private fun transport(ca: String): OkHttpClient {
            val builder = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS)
            if (ca.isNotBlank()) {
                val systemFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
                val system = systemFactory.trustManagers.filterIsInstance<X509TrustManager>().first()
                val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("configured-relay-ca", certificate(ca)) }
                val extra = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }.trustManagers.filterIsInstance<X509TrustManager>().first()
                val trust = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
                    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                        try { system.checkServerTrusted(chain, authType) } catch (_: CertificateException) { extra.checkServerTrusted(chain, authType) }
                    }
                    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + extra.acceptedIssuers
                }
                val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                builder.sslSocketFactory(tls.socketFactory, trust) // OkHttp's hostname verifier remains unchanged.
            }
            return builder.build()
        }
    }
    private var client = transport("")
    private var root = ""
    @Volatile var connected = false
        private set
    @Volatile private var enabled = false
    @Volatile private var socket: WebSocket? = null
    private val polling = AtomicBoolean(false)
    private var received = 0
    private val submissions = ArrayDeque<String>()
    fun connect(url: String, credential: String, ca: String = "") {
        validate(url, credential, ca)
        stop(); enabled = true
        if (root != ca) {
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            client = transport(ca); root = ca
        }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $credential").build()
        val connection = BoundedWebSocket(client, request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!enabled || socket !== webSocket) { webSocket.cancel(); return }
                connected = true; status(true, "Internet relay connected")
            }
            override fun onMessage(webSocket: WebSocket, text: String) { fail(webSocket, "Relay sent an invalid text frame") }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!enabled || socket !== webSocket) return
                val raw = bytes.toByteArray()
                if (raw.isEmpty() || raw.size > 65_585) { fail(webSocket, "Relay frame exceeded its limit"); return }
                when (raw[0].toInt() and 255) {
                    0x81 -> {
                        val expected = synchronized(submissions) { if (submissions.isEmpty()) null else submissions.removeFirst() }
                        if (expected == null || raw.size < 2) { fail(webSocket, "Unsolicited relay acknowledgement"); return }
                        if (raw[1] == 0.toByte()) {
                            if (raw.size != 50 || encodeToken(raw.copyOfRange(2, 50)) != expected) { fail(webSocket, "Relay acknowledged a different envelope"); return }
                            custody(expected)
                        } else if (raw.size != 2) fail(webSocket, "Invalid custody status")
                        else status(true, when (raw[1].toInt()) { 1 -> "Relay recently forwarded this message"; 2 -> "Relay rate limit; ciphertext remains queued"; 3 -> "Relay storage full; ciphertext remains queued"; else -> "Relay rejected the envelope; check mailbox configuration" })
                    }
                    0x82 -> {
                        if (!polling.get() || ++received > 16 || raw.size < 123) { fail(webSocket, "Unexpected mailbox response"); return }
                        val key = encodeToken(raw.copyOfRange(1, 49))
                        incoming(raw.copyOfRange(49, raw.size), key) {
                            if (connected && socket === webSocket) webSocket.send((byteArrayOf(3) + decodeToken(key)).toByteString())
                        }
                    }
                    0x83 -> { if (raw.size != 1 || !polling.compareAndSet(true, false)) fail(webSocket, "Unexpected poll completion") }
                    0x84 -> if (raw.size != 2 || raw[1].toInt() !in 0..1) fail(webSocket, "Invalid mailbox acknowledgement")
                    else -> fail(webSocket, "Unsupported relay response")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended(webSocket, "Relay disconnected; messages remain queued") }
            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                ended(webSocket, if (response?.code == 401) "Relay credential was rejected" else "Relay unavailable; check its address, certificate and connection")
            }
        })
        socket = connection; connection.start()
    }
    private fun ended(webSocket: WebSocket, message: String) {
        if (socket !== webSocket) return
        connected = false; polling.set(false); synchronized(submissions) { submissions.clear() }
        if (enabled) status(false, message)
    }
    private fun fail(webSocket: WebSocket, message: String) { webSocket.cancel(); ended(webSocket, message) }
    fun offer(bytes: ByteArray, key: String) {
        val current = socket ?: return
        if (!connected || bytes.size > 65_536) return
        synchronized(submissions) {
            if (submissions.size >= 64 || submissions.contains(key)) return
            submissions.addLast(key)
            if (!current.send((byteArrayOf(1) + bytes).toByteString())) submissions.removeLast()
        }
    }
    fun poll() {
        val current = socket ?: return
        if (!connected || !polling.compareAndSet(false, true)) return
        received = 0
        if (!current.send(byteArrayOf(2, 0, 16).toByteString())) polling.set(false)
    }
    fun stop() {
        enabled = false; connected = false; polling.set(false)
        val previous = socket; socket = null; previous?.cancel()
        synchronized(submissions) { submissions.clear() }
    }
    fun close() { stop(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
