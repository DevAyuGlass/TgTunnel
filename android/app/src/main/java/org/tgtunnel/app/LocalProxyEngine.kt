package org.tgtunnel.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CompletionService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Android-local MTProto bridge.
 *
 * It accepts the obfuscated MTProto client handshake on loopback, decrypts it
 * with the configured 16-byte secret, then relays the stream to Telegram's
 * WebSocket MTProto endpoint (with direct DC TCP as a fallback).
 */
internal object LocalProxyEngine {
    private const val HANDSHAKE_SIZE = 64
    private const val MAX_CONNECTIONS = 32
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private val random = SecureRandom()
    private val cfConnectExecutor = Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "tgtunnel-cf-connect").apply { isDaemon = true }
    }
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()
    // Includes sockets during TCP connect, TLS handshake, and HTTP Upgrade.
    private val activeUpstreams = ConcurrentHashMap.newKeySet<Socket>()
    private val running = AtomicBoolean(false)

    @Volatile private var listener: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var logCallback: ((String) -> Unit)? = null
    @Volatile private var workerDomains: List<String> = emptyList()
    @Volatile private var cloudflareProxyEnabled: Boolean = true
    @Volatile private var dcRedirects: Map<Int, String> = mapOf(
        2 to "149.154.167.220",
        4 to "149.154.167.220"
    )
    @Volatile private var frontingFirst: Boolean = false
    @Volatile private var cfProxyDomains: List<String> = emptyList()
    @Volatile private var cfProxyRefreshThread: Thread? = null
    private val activeCfProxyDomains = ConcurrentHashMap<Int, String>()
    private val cfProxyFailures = ConcurrentHashMap<String, Int>()
    private val cfProxyRetryAfter = ConcurrentHashMap<String, Long>()
    private val tcpFailures = ConcurrentHashMap<String, Int>()
    private val tcpRetryAfter = ConcurrentHashMap<String, Long>()
    private val tcpConnecting = ConcurrentHashMap.newKeySet<String>()
    private val dnsCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    // Encoded list and decoding are kept compatible with Flowseal's upstream
    // config.py. A built-in list lets the fallback work before GitHub refresh.
    private val encodedCfProxyDomains = listOf(
        "virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com",
        "twdmbzcm.com", "awzwsldi.com", "clngqrflngqin.com", "tjacxbqtj.com",
        "bxaxtxmrw.com", "dmohrsgmohcrwb.com", "vwbmtmoi.com", "khgrre.com",
        "ulihssf.com", "tmhqsdqmfpmk.com", "xwuwoqbm.com", "orgcnunpj.com",
        "zhkuldz.com", "zypoljnslxa.com", "efabnxaowuzs.com", "zaftuzsftqdq.com"
    )
    private val defaultCfProxyDomains = encodedCfProxyDomains.map(::decodeCfProxyDomain)

    private interface Upstream {
        fun socketForTracking(): Socket
        fun send(data: ByteArray)
        fun receive(): ByteArray?
        fun close()
    }

    private class TcpUpstream(private val socket: Socket) : Upstream {
        override fun socketForTracking(): Socket = socket
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        override fun send(data: ByteArray) {
            output.write(data)
            output.flush()
        }
        override fun receive(): ByteArray? {
            val buffer = ByteArray(65536)
            val count = input.read(buffer)
            return if (count <= 0) null else buffer.copyOf(count)
        }
        override fun close() {
            runCatching { socket.close() }
        }
    }

    private class WebSocketUpstream(
        private val socket: SSLSocket,
        val splitFrames: Boolean,
        val cfProxyDomain: String? = null,
        val cfProxyDc: Int? = null
    ) : Upstream {
        val connectedAtMs: Long = System.currentTimeMillis()
        @Volatile var receivedBytes: Long = 0L
        @Volatile var firstReceivedAtMs: Long = 0L
        @Volatile var sentBytes: Long = 0L
        @Volatile var firstSentAtMs: Long = 0L
        private val firstResponseLogged = AtomicBoolean(false)
        @Volatile var closedByPeer: Boolean = false
        @Volatile var closeCode: Int? = null
        @Volatile var closeReason: String = ""
        @Volatile var upstreamFailure: String = ""
        @Volatile var upstreamFailed: Boolean = false
        @Volatile var lastFrameReceivedAtMs: Long = connectedAtMs
        @Volatile var lastPongAtMs: Long = connectedAtMs
        @Volatile var lastPingAtMs: Long = 0L
        override fun socketForTracking(): Socket = socket
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()
        private val writeLock = Any()
        private val fragments = ByteArrayOutputStream()
        private val closed = AtomicBoolean(false)

        override fun send(data: ByteArray) {
            sendFrame(0x2, data)
        }

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            if (closed.get()) throw SocketException("WebSocket is closed")
            synchronized(writeLock) {
                val length = payload.size
                output.write(0x80 or opcode)
                when {
                    length < 126 -> output.write(0x80 or length)
                    length <= 0xffff -> {
                        output.write(0x80 or 126)
                        output.write((length ushr 8) and 0xff)
                        output.write(length and 0xff)
                    }
                    else -> {
                        output.write(0x80 or 127)
                        for (shift in 56 downTo 0 step 8) {
                            output.write(((length.toLong() ushr shift) and 0xff).toInt())
                        }
                    }
                }
                val mask = ByteArray(4).also { random.nextBytes(it) }
                output.write(mask)
                val masked = ByteArray(length)
                for (i in payload.indices) masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                output.write(masked)
                output.flush()
                if (sentBytes == 0L) firstSentAtMs = System.currentTimeMillis()
                sentBytes += length.toLong()
            }
        }

        override fun receive(): ByteArray? {
            while (!closed.get()) {
                val first = readByte()
                val second = readByte()
                val fin = (first and 0x80) != 0
                val opcode = first and 0x0f
                var length = (second and 0x7f).toLong()
                if (length == 126L) {
                    length = ((readByte().toLong() shl 8) or readByte().toLong())
                } else if (length == 127L) {
                    length = 0
                    repeat(8) { length = (length shl 8) or readByte().toLong() }
                }
                if (length < 0 || length > 16L * 1024L * 1024L) {
                    throw SocketException("WebSocket frame exceeds 16 MiB")
                }
                val mask = if ((second and 0x80) != 0) readExact(4) else null
                val payload = readExact(length.toInt())
                lastFrameReceivedAtMs = System.currentTimeMillis()
                // Any upstream frame proves the transport is alive; do not leave an old Ping outstanding.
                if (lastPingAtMs > 0L) lastPingAtMs = 0L
                if (mask != null) {
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                }
                when (opcode) {
                    0x8 -> {
                        val closeCode = if (payload.size >= 2) {
                            ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
                        } else null
                        val closeReason = if (payload.size > 2) {
                            payload.copyOfRange(2, payload.size).toString(Charsets.UTF_8)
                        } else ""
                        closedByPeer = true
                        this.closeCode = closeCode
                        this.closeReason = closeReason
                        log("Upstream WebSocket CLOSE: code=" + (closeCode?.toString() ?: "none") +
                            if (closeReason.isNotEmpty()) " reason=" + closeReason else "")
                        runCatching { sendFrame(0x8, payload.take(2).toByteArray()) }
                        closed.set(true)
                        return null
                    }
                    0x9 -> {
                        sendFrame(0xA, payload)
                    }
                    0xA -> {
                        lastPongAtMs = System.currentTimeMillis()
                    }
                    0x2, 0x0, 0x1 -> {
                        // Only binary WebSocket messages carry the MTProto stream.
                        // Text/control traffic must never count as tunnel health.
                        if (opcode == 0x1) continue
                        receivedBytes += payload.size.toLong()
                        if (opcode != 0x0 && fragments.size() > 0) fragments.reset()
                        fragments.write(payload)
                        if (fragments.size() > 16 * 1024 * 1024) {
                            throw SocketException("WebSocket message exceeds 16 MiB")
                        }
                        if (fin) {
                            val result = fragments.toByteArray()
                            fragments.reset()
                            if (opcode == 0x1) continue
                            if (firstReceivedAtMs == 0L) {
                                firstReceivedAtMs = System.currentTimeMillis()
                                if (cfProxyDomain != null && cfProxyDc != null) {
                                    val previous = activeCfProxyDomains.put(cfProxyDc, cfProxyDomain)
                                    cfProxyFailures.remove(cfProxyDomain)
                                    cfProxyRetryAfter.remove(cfProxyDomain)
                                    if (previous != cfProxyDomain) {
                                        log("Cloudflare Proxy route promoted for DC" + cfProxyDc + ": " + cfProxyDomain)
                                    }
                                }
                                if (firstResponseLogged.compareAndSet(false, true)) {
                                    val responseDelay = if (firstSentAtMs > 0L) firstReceivedAtMs - firstSentAtMs else -1L
                                    log("WebSocket first binary response: " +
                                        result.size + " bytes after " +
                                        (if (responseDelay >= 0L) responseDelay.toString() + "ms from first send" else "unknown send time") +
                                        (cfProxyDomain?.let { " · CF domain=" + it + " DC" + (cfProxyDc ?: "?") } ?: ""))
                                }
                            }
                            return result
                        }
                    }
                    else -> Unit
                }
            }
            return null
        }

        private fun readByte(): Int {
            val value = input.read()
            if (value < 0) throw SocketException("WebSocket closed by upstream")
            return value
        }

        private fun readExact(size: Int): ByteArray {
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(result, offset, size - offset)
                if (count < 0) throw SocketException("Unexpected EOF in WebSocket frame")
                offset += count
            }
            return result
        }

        fun sendHealthPing() {
            lastPingAtMs = System.currentTimeMillis()
            sendFrame(0x9, ByteArray(8).also { random.nextBytes(it) })
        }

        override fun close() {
            if (closed.get()) {
                runCatching { socket.close() }
                return
            }
            runCatching { sendFrame(0x8, ByteArray(0)) }
            closed.set(true)
            runCatching { socket.close() }
        }
    }

    private data class Handshake(
        val transportTag: ByteArray,
        val dcId: Int,
        val media: Boolean,
        val clientPrekeyIv: ByteArray
    )

    private data class CryptoContext(
        val clientDecrypt: Cipher,
        val clientEncrypt: Cipher,
        val telegramEncrypt: Cipher,
        val telegramDecrypt: Cipher
    )

    fun info(): String = "Android MTProto bridge · Telegram WS + CF Worker + CF Proxy + TCP fallback"

    fun isRunning(): Boolean = running.get()

    fun start(port: Int, secretHex: String, workerDomain: String, cfProxyEnabled: Boolean, dcIpList: String, onLog: (String) -> Unit): String {
        if (port !in 1..65535) return "ERR:Port must be between 1 and 65535"
        val secret = parseSecret(secretHex) ?: return "ERR:Secret must contain exactly 32 hexadecimal characters"
        val configuredWorkers = parseWorkerDomains(workerDomain)
        if (workerDomain.isNotBlank() && configuredWorkers.isEmpty()) {
            return "ERR:Worker domain must be a hostname only (for example name.username.workers.dev)"
        }
        val configuredDcRedirects = parseDcIpList(dcIpList)
            ?: return "ERR:DC → IP must use comma-separated DC:IPv4 entries (for example 2:149.154.167.220)"
        synchronized(this) {
            if (running.get()) return "ERR:Proxy is already running"
            var server: ServerSocket? = null
            return try {
                val boundServer = ServerSocket()
                server = boundServer
                boundServer.reuseAddress = true
                boundServer.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 64)
                logCallback = onLog
                workerDomains = configuredWorkers
                cloudflareProxyEnabled = cfProxyEnabled
                dcRedirects = configuredDcRedirects
                frontingFirst = false
                cfProxyDomains = defaultCfProxyDomains
                // Keep CF Proxy health learned from previous start/stop cycles.
                tcpFailures.clear()
                tcpRetryAfter.clear()
                tcpConnecting.clear()
                listener = boundServer
                running.set(true)
                acceptThread = Thread({ acceptLoop(boundServer, secret) }, "tgtunnel-accept").apply {
                    isDaemon = true
                    start()
                }
                if (cfProxyEnabled) startCfProxyDomainRefresh()
                log("Local listener started on 127.0.0.1:" + port)
                "OK:Listening on 127.0.0.1:" + port
            } catch (error: Exception) {
                running.set(false)
                listener = null
                runCatching { server?.close() }
                "ERR:Could not bind local port: " + (error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun stop(): String {
        synchronized(this) {
            if (!running.getAndSet(false)) return "Proxy is already stopped"
            runCatching { listener?.close() }
            listener = null
            workerDomains = emptyList()
            cloudflareProxyEnabled = false
            cfProxyRefreshThread?.interrupt()
            cfProxyRefreshThread = null
            cfProxyDomains = emptyList()
            // Preserve known-good routes and per-domain backoff across manual stops.
            tcpFailures.clear()
            tcpRetryAfter.clear()
            tcpConnecting.clear()
            dnsCache.clear()
            activeClients.toList().forEach { runCatching { it.close() } }
            activeUpstreams.toList().forEach { runCatching { it.close() } }
            activeClients.clear()
            activeUpstreams.clear()
            acceptThread = null
            log("Local listener stopped")
            return "Proxy stopped"
        }
    }

    private fun acceptLoop(server: ServerSocket, secret: ByteArray) {
        while (running.get()) {
            try {
                val client = server.accept()
                if (activeClients.size >= MAX_CONNECTIONS) {
                    runCatching { client.close() }
                    log("Connection rejected: limit of " + MAX_CONNECTIONS + " reached")
                    continue
                }
                client.tcpNoDelay = true
                activeClients.add(client)
                log("Local client accepted · active sessions=" + activeClients.size)
                Thread({
                    try {
                        handleClient(client, secret)
                    } finally {
                        activeClients.remove(client)
                        runCatching { client.close() }
                        if (running.get()) log("Local client session ended · listener remains ready for Telegram reconnect")
                    }
                }, "tgtunnel-client").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: SocketException) {
                if (running.get()) log("Local listener socket closed unexpectedly")
                break
            } catch (error: Exception) {
                if (running.get()) log("Accept error: " + (error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun readClientHandshake(client: Socket): ByteArray {
        val result = ByteArray(HANDSHAKE_SIZE)
        val input = client.getInputStream()
        var offset = 0
        val startedAt = System.currentTimeMillis()
        while (offset < HANDSHAKE_SIZE) {
            try {
                val count = input.read(result, offset, HANDSHAKE_SIZE - offset)
                if (count < 0) {
                    log("Handshake EOF · received " + offset + "/64 bytes before client closed")
                    throw SocketException("Client disconnected during MTProto handshake (" + offset + "/64 bytes)")
                }
                offset += count
                if (offset == HANDSHAKE_SIZE) {
                    log("Handshake received · 64/64 bytes in " +
                        (System.currentTimeMillis() - startedAt) + "ms")
                } else if (count > 0) {
                    log("Handshake progress · " + offset + "/64 bytes")
                }
            } catch (error: SocketTimeoutException) {
                log("Handshake timeout · received " + offset + "/64 bytes in 10000ms")
                throw error
            }
        }
        return result
    }

    private fun handleClient(client: Socket, secret: ByteArray) {
        var upstream: Upstream? = null
        try {
            client.soTimeout = 10000
            log("Handshake started · waiting for 64 bytes · " + (client.remoteSocketAddress?.toString() ?: "unknown client"))
            val header = readClientHandshake(client)
            if (!running.get()) return
            val handshake = decodeHandshake(header, secret) ?: run {
                log("Rejected client: invalid MTProto handshake or secret")
                return
            }
            client.soTimeout = 0
            val relayInit = generateRelayInit(handshake)
            val crypto = buildCryptoContext(handshake, secret, relayInit)
            upstream = connectUpstream(handshake)
            if (upstream == null) {
                if (running.get()) log("DC" + handshake.dcId + " upstream connection failed")
                return
            }
            if (!running.get()) return
            when (upstream) {
                is TcpUpstream -> upstream.send(relayInit)
                is WebSocketUpstream -> upstream.send(relayInit)
            }
            log("MTProto relayInit sent: ${relayInit.size} bytes · DC${handshake.dcId}" +
                (if (handshake.media) " media" else "") +
                " · transport=" + transportName(handshake.transportTag))
            if (!running.get()) return
            log("Client connected · DC" + handshake.dcId +
                (if (handshake.media) " media" else "") +
                " · local client " + (client.remoteSocketAddress?.toString() ?: "unknown"))
            bridge(client, upstream, crypto, handshake.transportTag)
        } catch (error: Exception) {
            if (running.get()) log("Client session error: " + (error.message ?: error.javaClass.simpleName))
        } finally {
            // A user-initiated stop closes active sockets. Do not treat that as
            // evidence that the selected Cloudflare domain is unhealthy.
            if (running.get()) {
                (upstream as? WebSocketUpstream)?.let(::recordCfProxyOutcome)
            }
            upstream?.close()
            upstream?.let { activeUpstreams.remove(extractSocket(it)) }
        }
    }

    private fun recordCfProxyOutcome(upstream: WebSocketUpstream) {
        val domain = upstream.cfProxyDomain ?: return
        val dc = upstream.cfProxyDc ?: return
        val elapsed = System.currentTimeMillis() - upstream.connectedAtMs
        val failedEarly = (upstream.closedByPeer || upstream.upstreamFailed) &&
            elapsed < 15_000L && upstream.receivedBytes < 64L
        val hasMeaningfulResponse = upstream.receivedBytes >= 64L
        val healthySession = !upstream.closedByPeer && !upstream.upstreamFailed &&
            elapsed >= 5_000L && hasMeaningfulResponse
        if (failedEarly) {
            val failures = (cfProxyFailures[domain] ?: 0) + 1
            cfProxyFailures[domain] = failures
            val backoffSeconds = minOf(300L, 5L shl minOf(failures - 1, 5))
            cfProxyRetryAfter[domain] = System.currentTimeMillis() + backoffSeconds * 1000L
            if (activeCfProxyDomains[dc] == domain) activeCfProxyDomains.remove(dc, domain)
            log("Cloudflare Proxy domain marked unhealthy: " + domain +
                " (DC" + dc + ", age " + elapsed + "ms, received " +
                upstream.receivedBytes + " bytes); retry in " + backoffSeconds + "s")
        } else if (healthySession) {
            cfProxyFailures.remove(domain)
            cfProxyRetryAfter.remove(domain)
            activeCfProxyDomains[dc] = domain
            log("Cloudflare Proxy domain healthy: " + domain +
                " (DC" + dc + ", age " + elapsed + "ms, received " + upstream.receivedBytes + " bytes)")
        } else {
            log("Cloudflare Proxy domain health inconclusive: " + domain +
                " (DC" + dc + ", age " + elapsed + "ms, received " +
                upstream.receivedBytes + " bytes)")
        }
    }

    private fun extractSocket(upstream: Upstream): Socket = upstream.socketForTracking()

    /**
     * Register before blocking network operations. The second running check closes
     * the race where stop() snapshots activeUpstreams between the first check and add().
     */
    private fun registerUpstreamSocket(socket: Socket): Boolean {
        if (!running.get()) {
            runCatching { socket.close() }
            return false
        }
        activeUpstreams.add(socket)
        if (!running.get()) {
            activeUpstreams.remove(socket)
            runCatching { socket.close() }
            return false
        }
        return true
    }

    private fun unregisterUpstreamSocket(socket: Socket) {
        activeUpstreams.remove(socket)
        runCatching { socket.close() }
    }

    private fun bridge(client: Socket, upstream: Upstream, crypto: CryptoContext, transportTag: ByteArray) {
        val wsSplitter = (upstream as? WebSocketUpstream)
            ?.takeIf { it.splitFrames }
            ?.let { MtprotoMsgSplitter(transportTag) }
        val finished = AtomicBoolean(false)
        val closeReason = AtomicReference("session ended")
        val upBytes = AtomicLong(0L)
        val downBytes = AtomicLong(0L)
        val upPackets = AtomicLong(0L)
        val downPackets = AtomicLong(0L)
        val startedAt = System.currentTimeMillis()
        val wsHealth = upstream as? WebSocketUpstream
        val healthMonitor = if (wsHealth != null) Thread({
            while (!finished.get() && running.get()) {
                try { Thread.sleep(15_000L) } catch (_: InterruptedException) { break }
                if (finished.get() || !running.get()) break
                val now = System.currentTimeMillis()
                val lastPing = wsHealth.lastPingAtMs
                val lastFrame = wsHealth.lastFrameReceivedAtMs
                if (lastPing > 0L && lastFrame <= lastPing && now - lastPing >= 40_000L) {
                    val reason = "WebSocket health timeout: no frame/Pong for " + (now - lastPing) + "ms"
                    wsHealth.upstreamFailed = true
                    wsHealth.upstreamFailure = reason
                    closeReason.compareAndSet("session ended", reason)
                    log(reason + " · closing local client so Telegram can reconnect")
                    finished.set(true)
                    runCatching { client.close() }
                    runCatching { upstream.close() }
                    break
                }
                if (now - lastFrame >= 20_000L && (lastPing == 0L || lastPing <= wsHealth.lastPongAtMs)) {
                    try {
                        wsHealth.sendHealthPing()
                        log("WebSocket health check: Ping sent after " + (now - lastFrame) + "ms without upstream frames")
                    } catch (error: Exception) {
                        val reason = "WebSocket health Ping failed: " + (error.message ?: error.javaClass.simpleName)
                        wsHealth.upstreamFailed = true
                        wsHealth.upstreamFailure = reason
                        closeReason.compareAndSet("session ended", reason)
                        log(reason + " · closing local client so Telegram can reconnect")
                        finished.set(true)
                        runCatching { client.close() }
                        runCatching { upstream.close() }
                        break
                    }
                }
            }
        }, "tgtunnel-ws-health").apply { isDaemon = true; start() } else null
        var firstClientPayloadLogged = false
        var splitterDiagnosticLogged = false
        val clientInput = client.getInputStream()
        val clientOutput = client.getOutputStream()

        val upstreamToClient = Thread({
            try {
                while (!finished.get() && running.get()) {
                    val data = try {
                        upstream.receive()
                    } catch (error: Exception) {
                        // Closing the upstream socket after a local client EOF or a
                        // user stop also wakes this blocked read with "Socket closed".
                        // That is a consequence of our own shutdown, not an upstream
                        // transport failure and must not poison CF Proxy health.
                        if (!finished.get() && running.get()) {
                            (upstream as? WebSocketUpstream)?.let {
                                it.upstreamFailed = true
                                it.upstreamFailure = error.message ?: error.javaClass.simpleName
                            }
                            closeReason.compareAndSet("session ended", "upstream error: " +
                                (error.message ?: error.javaClass.simpleName))
                        }
                        throw error
                    }
                    if (data == null) {
                        val ws = upstream as? WebSocketUpstream
                        val reason = if (ws?.closedByPeer == true) {
                            "upstream WebSocket CLOSE" + (ws.closeCode?.let { " code=$it" } ?: "") +
                                if (ws.closeReason.isNotEmpty()) " reason=${ws.closeReason}" else ""
                        } else "upstream EOF"
                        closeReason.compareAndSet("session ended", reason)
                        break
                    }
                    downPackets.incrementAndGet()
                    downBytes.addAndGet(data.size.toLong())
                    val plain = crypto.telegramDecrypt.update(data) ?: ByteArray(0)
                    val encrypted = crypto.clientEncrypt.update(plain) ?: ByteArray(0)
                    if (encrypted.isNotEmpty()) {
                        clientOutput.write(encrypted)
                        clientOutput.flush()
                    }
                }
            } catch (error: Exception) {
                if (running.get() && !finished.get()) {
                    log("Upstream -> client bridge error: " +
                        (error.message ?: error.javaClass.simpleName))
                }
            } finally {
                finished.set(true)
                runCatching { client.close() }
                upstream.close()
            }
        }, "tgtunnel-upstream")
        upstreamToClient.isDaemon = true
        upstreamToClient.start()

        try {
            val buffer = ByteArray(65536)
            while (!finished.get() && running.get()) {
                val count = clientInput.read(buffer)
                if (count < 0) {
                    val peer = client.remoteSocketAddress?.toString() ?: "unknown"
                    closeReason.compareAndSet("session ended", "client EOF from " + peer)
                    log("Local client sent EOF · closing its upstream session; listener stays available for reconnect")
                    wsSplitter?.flush()?.forEach {
                        upstream.send(it)
                        upPackets.incrementAndGet()
                        upBytes.addAndGet(it.size.toLong())
                    }
                    break
                }
                val data = buffer.copyOf(count)
                upPackets.incrementAndGet()
                upBytes.addAndGet(count.toLong())
                val plain = crypto.clientDecrypt.update(data) ?: ByteArray(0)
                val encrypted = crypto.telegramEncrypt.update(plain) ?: ByteArray(0)
                if (encrypted.isNotEmpty()) {
                    if (wsSplitter == null) {
                        if (!firstClientPayloadLogged) {
                            firstClientPayloadLogged = true
                            log("MTProto client payload decrypted: ciphertext=${data.size} bytes, " +
                                "plaintext=${plain.size} bytes · transport=" + transportName(transportTag) +
                                " · frame splitting disabled")
                        }
                        upstream.send(encrypted)
                    } else {
                        val frames = wsSplitter.split(encrypted, plain)
                        if (!firstClientPayloadLogged) {
                            firstClientPayloadLogged = true
                            log("MTProto client payload decrypted: ciphertext=${data.size} bytes, " +
                                "plaintext=${plain.size} bytes")
                        }
                        if (!splitterDiagnosticLogged) {
                            splitterDiagnosticLogged = true
                            log("MTProto transport framing: " + wsSplitter.diagnosticSnapshot() +
                                " · emittedFrames=" + frames.size)
                        }
                        frames.forEach(upstream::send)
                    }
                }
            }
        } catch (error: Exception) {
            val detail = error.message ?: error.javaClass.simpleName
            closeReason.compareAndSet("session ended", "client read/write error: " + error.javaClass.simpleName + ": " + detail)
            if (running.get() && !finished.get()) {
                log("Client socket operation failed: " + error.javaClass.simpleName + ": " + detail +
                    " · localClosed=" + client.isClosed + " inputShutdown=" + client.isInputShutdown +
                    " outputShutdown=" + client.isOutputShutdown)
            }
        } finally {
            finished.set(true)
            runCatching { client.close() }
            upstream.close()
            runCatching { upstreamToClient.join(1000) }
            healthMonitor?.interrupt()
            runCatching { healthMonitor?.join(500) }
            val elapsed = System.currentTimeMillis() - startedAt
            val ws = upstream as? WebSocketUpstream
            wsSplitter?.let { log("MTProto framing at close: " + it.diagnosticSnapshot()) }
            val finalReason = closeReason.get()
            log("Bridge closed (${finalReason}): " +
                "client→upstream ${upBytes.get()} bytes/${upPackets.get()} reads, " +
                "upstream→client ${downBytes.get()} bytes/${downPackets.get()} frames, " +
                "elapsed ${elapsed}ms" +
                ", localSocket(closed=${client.isClosed}, inputShutdown=${client.isInputShutdown}, outputShutdown=${client.isOutputShutdown})" +
                (ws?.let {
                    ", WS sent=${it.sentBytes} received=${it.receivedBytes}" +
                        (if (it.closeCode != null) ", closeCode=${it.closeCode}" else "") +
                        (if (it.closeReason.isNotEmpty()) ", closeReason=${it.closeReason}" else "") +
                        (if (it.upstreamFailure.isNotEmpty()) ", upstreamError=${it.upstreamFailure}" else "") +
                        (if (it.firstReceivedAtMs > 0L) ", firstResponse=${it.firstReceivedAtMs - it.connectedAtMs}ms" else ", firstResponse=none")
                } ?: ""))
        }
    }

    private data class CfProxyAttemptResult(
        val domain: String,
        val upstream: WebSocketUpstream? = null,
        val error: Exception? = null,
        val superseded: Boolean = false
    )

    /**
     * Race two Cloudflare routes with a short stagger. The first successful
     * WebSocket wins; a slower successful attempt closes itself instead of
     * leaking a spare upstream. Remaining pool entries are tried sequentially.
     */
    private fun connectCloudflareCandidates(
        candidates: List<String>,
        dcForDomain: Int,
        telegramDc: Int
    ): WebSocketUpstream? {
        if (candidates.isEmpty() || !running.get()) return null

        val racingCandidates = candidates.take(2)
        val completion: CompletionService<CfProxyAttemptResult> =
            ExecutorCompletionService(cfConnectExecutor)
        val winner = AtomicReference<WebSocketUpstream?>(null)
        val raceFinished = AtomicBoolean(false)
        val futures = mutableListOf<Future<CfProxyAttemptResult>>()
        var submitted = 0
        var completed = 0

        fun submit(domain: String) {
            futures += completion.submit(Callable {
                try {
                    val host = "kws" + dcForDomain + "." + domain
                    val upstream = connectWebSocket(
                        host,
                        cfProxyDomain = domain,
                        cfProxyDc = dcForDomain
                    )
                    if (!running.get() || raceFinished.get() || !winner.compareAndSet(null, upstream)) {
                        unregisterUpstreamSocket(extractSocket(upstream))
                        CfProxyAttemptResult(domain, superseded = true)
                    } else {
                        CfProxyAttemptResult(domain, upstream = upstream)
                    }
                } catch (error: Exception) {
                    CfProxyAttemptResult(domain, error = error)
                }
            })
            submitted++
        }

        submit(racingCandidates.first())
        val secondLaunchAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(180)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)

        try {
            while (completed < submitted || submitted < racingCandidates.size) {
                if (!running.get()) break

                val now = System.nanoTime()
                if (submitted < racingCandidates.size && now >= secondLaunchAt) {
                    submit(racingCandidates[submitted])
                }

                val remaining = deadline - now
                if (remaining <= 0L) break
                val waitNanos = minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50))
                val future = completion.poll(waitNanos, TimeUnit.NANOSECONDS)

                if (future == null) {
                    if (completed == submitted && submitted < racingCandidates.size) {
                        submit(racingCandidates[submitted])
                    }
                    continue
                }

                completed++
                val result = runCatching { future.get() }.getOrNull() ?: continue
                if (result.upstream != null) {
                    raceFinished.set(true)
                    activeCfProxyDomains[dcForDomain] = result.domain
                    cfProxyFailures.remove(result.domain)
                    cfProxyRetryAfter.remove(result.domain)
                    futures.forEach { if (!it.isDone) it.cancel(true) }
                    log("WebSocket established to DC" + telegramDc +
                        " through Cloudflare Proxy (" + result.domain + "); fast route selected")
                    return result.upstream
                }
                if (!result.superseded && result.error != null && running.get()) {
                    recordCfProxyConnectFailure(result.domain, result.error)
                }
                if (completed == submitted && submitted < racingCandidates.size) {
                    submit(racingCandidates[submitted])
                }
            }
        } finally {
            raceFinished.set(true)
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }

        val selected = winner.get()
        if (selected != null && running.get()) {
            activeCfProxyDomains[dcForDomain] = selected.cfProxyDomain ?: return null
            return selected
        }
        return null
    }

    private fun recordCfProxyConnectFailure(domain: String, error: Exception) {
        val failures = (cfProxyFailures[domain] ?: 0) + 1
        cfProxyFailures[domain] = failures
        val backoffSeconds = minOf(300L, 5L shl minOf(failures - 1, 5))
        cfProxyRetryAfter[domain] = System.currentTimeMillis() + backoffSeconds * 1000L
        log("Cloudflare Proxy " + domain + " failed (retry in " + backoffSeconds + "s): " +
            (error.message ?: error.javaClass.simpleName))
    }

    private fun connectUpstream(handshake: Handshake): Upstream? {
        if (!running.get()) return null
        val dcForDomain = if (handshake.dcId == 203) 2 else handshake.dcId
        if (dcForDomain !in 1..5) {
            log("Unsupported Telegram DC: " + handshake.dcId)
            return null
        }
        val domains = if (handshake.media) {
            listOf("kws" + dcForDomain + "-1.web.telegram.org", "kws" + dcForDomain + ".web.telegram.org")
        } else {
            listOf("kws" + dcForDomain + ".web.telegram.org")
        }
        // Flowseal's CF Proxy tier: connect to kws<dc>.<Cloudflare-proxied-domain>.
        // It is independent from the optional user-owned Worker tunnel above.
        if (cloudflareProxyEnabled) {
            val preferred = activeCfProxyDomains[dcForDomain]
            val now = System.currentTimeMillis()
            val candidates = buildList {
                if (preferred != null && (cfProxyRetryAfter[preferred] ?: 0L) <= now) add(preferred)
                addAll(cfProxyDomains.shuffled().filter {
                    it != preferred && (cfProxyRetryAfter[it] ?: 0L) <= now
                })
            }
            if (candidates.isEmpty()) {
                log("Cloudflare Proxy pool is cooling down; retrying with the full pool")
            }
            val orderedCandidates = candidates.ifEmpty { cfProxyDomains.shuffled() }
            connectCloudflareCandidates(orderedCandidates, dcForDomain, handshake.dcId)?.let { return it }
            for (baseDomain in orderedCandidates.drop(2)) {
                if (!running.get()) return null
                val host = "kws" + dcForDomain + "." + baseDomain
                try {
                    val upstream = connectWebSocket(host, cfProxyDomain = baseDomain, cfProxyDc = dcForDomain)
                    if (!running.get()) {
                        unregisterUpstreamSocket(extractSocket(upstream))
                        return null
                    }
                    activeCfProxyDomains[dcForDomain] = baseDomain
                    log("WebSocket established to DC" + handshake.dcId +
                        " through Cloudflare Proxy (" + baseDomain + "); waiting for tunnel health")
                    return upstream
                } catch (error: Exception) {
                    if (!running.get()) return null
                    recordCfProxyConnectFailure(baseDomain, error)
                }
            }
        }

        val redirectIp = dcRedirects[dcForDomain]
        for (domain in domains) {
            if (!running.get()) return null
            val modes = if (frontingFirst) listOf(true, false) else listOf(false, true)
            for (fronted in modes) {
                if (!running.get()) return null
                try {
                    val ws = connectWebSocket(
                        domain,
                        connectHost = redirectIp,
                        tlsSni = if (fronted) "sprinthost.ru" else domain,
                        verifyHostname = !fronted
                    )
                    if (!running.get()) {
                        unregisterUpstreamSocket(extractSocket(ws))
                        return null
                    }
                    frontingFirst = fronted
                    log("Connected to " + domain + " over TLS WebSocket" +
                        (redirectIp?.let { " via DC redirect $it" } ?: "") +
                        " (fronting=$fronted)")
                    return ws
                } catch (error: Exception) {
                    if (!running.get()) return null
                    log("WebSocket " + domain + " (fronting=$fronted) failed: " +
                        (error.message ?: error.javaClass.simpleName))
                }
            }
        }

        val workers = workerDomains
        if (workers.isNotEmpty()) {
            for (worker in workers) {
                if (!running.get()) return null
                for (destination in domains) {
                    if (!running.get()) return null
                    try {
                        val path = "/apiws?dst=" + URLEncoder.encode(destination, "UTF-8") +
                            "&dc=" + dcForDomain
                        val upstream = connectWebSocket(worker, path, splitFrames = false)
                        if (!running.get()) {
                            unregisterUpstreamSocket(extractSocket(upstream))
                            return null
                        }
                        log("Connected to " + destination + " through Cloudflare Worker " + worker)
                        return upstream
                    } catch (error: Exception) {
                        if (!running.get()) return null
                        log("Cloudflare Worker " + worker + " for " + destination + " failed: " +
                            (error.message ?: error.javaClass.simpleName))
                    }
                }
            }
        }

        val ip = when (handshake.dcId) {
            1 -> "149.154.175.50"
            2 -> "149.154.167.51"
            203 -> "91.105.192.100"
            3 -> "149.154.175.100"
            4 -> "149.154.167.91"
            5 -> "149.154.171.5"
            else -> return null
        }
        if (!running.get()) return null
        val endpoint = "$ip:443"
        val retryAt = tcpRetryAfter[endpoint] ?: 0L
        if (retryAt > System.currentTimeMillis()) {
            log("TCP fallback to " + endpoint + " is cooling down for " +
                ((retryAt - System.currentTimeMillis() + 999L) / 1000L) + "s")
            return null
        }
        if (!tcpConnecting.add(endpoint)) {
            log("TCP fallback to " + endpoint + " skipped: connection already in progress")
            return null
        }
        val socket = Socket()
        return try {
            if (!registerUpstreamSocket(socket)) return null
            socket.connect(InetSocketAddress(ip, 443), 10000)
            if (!running.get()) throw SocketException("Proxy stopped during TCP connect")
            socket.tcpNoDelay = true
            tcpFailures.remove(endpoint)
            tcpRetryAfter.remove(endpoint)
            log("Using direct TCP fallback to DC" + handshake.dcId)
            TcpUpstream(socket)
        } catch (error: Exception) {
            unregisterUpstreamSocket(socket)
            if (!running.get()) return null
            val failures = (tcpFailures[endpoint] ?: 0) + 1
            tcpFailures[endpoint] = failures
            val backoffSeconds = minOf(3600L, 30L shl minOf(failures - 1, 7))
            tcpRetryAfter[endpoint] = System.currentTimeMillis() + backoffSeconds * 1000L
            log("TCP fallback to " + endpoint + " failed (retry in " + backoffSeconds + "s): " +
                (error.message ?: error.javaClass.simpleName))
            null
        } finally {
            tcpConnecting.remove(endpoint)
        }
    }

    private fun connectWebSocket(
        domain: String,
        requestPath: String = "/apiws",
        splitFrames: Boolean = true,
        cfProxyDomain: String? = null,
        cfProxyDc: Int? = null,
        connectHost: String? = null,
        tlsSni: String? = null,
        verifyHostname: Boolean = true
    ): WebSocketUpstream {
        if (!running.get()) throw SocketException("Proxy is stopped")
        val socket = connectTlsSocketIpv4First(domain, connectHost, tlsSni ?: domain, verifyHostname)
        try {
            if (!running.get()) throw SocketException("Proxy stopped during TLS setup")
            val keyBytes = ByteArray(16).also { random.nextBytes(it) }
            val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            val request = (
                "GET " + requestPath + " HTTP/1.1\r\n" +
                "Host: " + domain + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Protocol: binary\r\n\r\n"
            )
            val output = socket.getOutputStream()
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()

            val input = socket.getInputStream()
            val header = ByteArrayOutputStream()
            var matched = 0
            val delimiter = byteArrayOf(13, 10, 13, 10)
            while (header.size() < 16384 && matched < 4) {
                val value = input.read()
                if (value < 0) throw SocketException("Empty WebSocket response")
                header.write(value)
                matched = if (value == delimiter[matched].toInt()) matched + 1
                    else if (value == 13) 1 else 0
            }
            if (matched != 4) throw SocketException("Invalid WebSocket HTTP headers")
            val response = header.toString("ISO-8859-1")
            val lines = response.split("\r\n")
            if (lines.firstOrNull()?.contains(" 101 ") != true) {
                throw SocketException("WebSocket upgrade rejected: " + (lines.firstOrNull() ?: "no status"))
            }
            val headers = lines.drop(1).mapNotNull { line ->
                val split = line.indexOf(':')
                if (split <= 0) null else line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim()
            }.toMap()
            val expectedAccept = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.US_ASCII)),
                Base64.NO_WRAP
            )
            if (headers["sec-websocket-accept"] != expectedAccept) {
                throw SocketException("Invalid WebSocket accept header")
            }
            if (!running.get()) throw SocketException("Proxy stopped during WebSocket upgrade")
            socket.soTimeout = 0
            return WebSocketUpstream(socket, splitFrames, cfProxyDomain, cfProxyDc)
        } catch (error: Exception) {
            unregisterUpstreamSocket(socket)
            throw error
        }
    }

    /**
     * Prefer IPv4 because many Android/mobile networks advertise AAAA records
     * without a usable IPv6 route. If Android DNS fails, query public DNS over
     * UDP directly, while retaining the hostname for TLS SNI and verification.
     */
    private fun connectTlsSocketIpv4First(
        domain: String,
        connectHost: String? = null,
        tlsSni: String = domain,
        verifyHostname: Boolean = true
    ): SSLSocket {
        if (!running.get()) throw SocketException("Proxy is stopped")
        val addresses = if (connectHost != null) {
            listOf(InetAddress.getByName(connectHost))
        } else {
            resolveIpv4(domain)
        }
        if (!running.get()) throw SocketException("Proxy stopped during DNS resolution")
        var lastError: Exception? = null
        for (address in addresses) {
            if (!running.get()) throw SocketException("Proxy stopped before connecting to $domain")
            val raw = Socket()
            if (!registerUpstreamSocket(raw)) throw SocketException("Proxy stopped before TCP connect")
            var ssl: SSLSocket? = null
            try {
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(address, 443), 7000)
                if (!running.get()) throw SocketException("Proxy stopped during TCP connect")
                ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, tlsSni, 443, true) as SSLSocket
                if (!registerUpstreamSocket(ssl)) throw SocketException("Proxy stopped before TLS handshake")
                // Register the TLS wrapper before dropping the raw socket so stop()
                // can close either representation without an untracked interval.
                activeUpstreams.remove(raw)
                ssl.soTimeout = 12000
                val parameters = ssl.sslParameters
                parameters.endpointIdentificationAlgorithm = if (verifyHostname) "HTTPS" else null
                parameters.serverNames = listOf(SNIHostName(tlsSni))
                ssl.sslParameters = parameters
                ssl.startHandshake()
                if (!running.get()) throw SocketException("Proxy stopped during TLS handshake")
                return ssl
            } catch (error: Exception) {
                lastError = error
                unregisterUpstreamSocket(raw)
                ssl?.let(::unregisterUpstreamSocket)
                if (!running.get()) throw SocketException("Proxy stopped while connecting to $domain")
            }
        }
        throw lastError ?: UnknownHostException("No IPv4 addresses for $domain")
    }

    /**
     * Resolve IPv4 without depending on Android DNS for the resolver hostname.
     * DoH bootstrap uses literal resolver IPs, while TLS SNI and certificate
     * verification remain pinned to dns.google / cloudflare-dns.com.
     */
    private fun resolveIpv4(domain: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        dnsCache[domain]?.let { cached ->
            if (cached.first > now && cached.second.isNotEmpty()) return cached.second
            dnsCache.remove(domain, cached)
        }

        val systemAddresses = runCatching {
            InetAddress.getAllByName(domain).filterIsInstance<Inet4Address>()
        }.getOrDefault(emptyList())
        if (systemAddresses.isNotEmpty()) {
            val result = systemAddresses.distinctBy { it.hostAddress }
            dnsCache[domain] = (now + 60_000L) to result
            return result
        }

        log("System DNS failed for " + domain + "; bootstrapping DNS-over-HTTPS by IP")
        val resolvers = listOf(
            Triple("dns.google", "8.8.8.8", "/resolve?name="),
            Triple("dns.google", "8.8.4.4", "/resolve?name="),
            Triple("cloudflare-dns.com", "1.1.1.1", "/dns-query?name="),
            Triple("cloudflare-dns.com", "1.0.0.1", "/dns-query?name=")
        )
        for ((host, ip, pathPrefix) in resolvers) {
            try {
                val query = URLEncoder.encode(domain, "UTF-8") + "&type=A"
                val body = queryDohJson(host, ip, pathPrefix + query)
                val answers = JSONObject(body).optJSONArray("Answer") ?: continue
                val addresses = buildList {
                    for (index in 0 until answers.length()) {
                        val answer = answers.optJSONObject(index) ?: continue
                        if (answer.optInt("type") != 1) continue
                        val octets = answer.optString("data").split('.')
                        if (octets.size != 4) continue
                        val bytes = octets.map { it.toIntOrNull() ?: -1 }
                        if (bytes.any { it !in 0..255 }) continue
                        add(InetAddress.getByAddress(domain, bytes.map { it.toByte() }.toByteArray()))
                    }
                }.distinctBy { it.hostAddress }
                if (addresses.isNotEmpty()) {
                    dnsCache[domain] = (System.currentTimeMillis() + 60_000L) to addresses
                    log("Resolved " + domain + " through bootstrapped DoH (" + host + " @ " + ip + ")")
                    return addresses
                }
            } catch (error: Exception) {
                log("Bootstrapped DoH " + host + " (" + ip + ") failed for " + domain + ": " +
                    (error.message ?: error.javaClass.simpleName))
            }
        }
        throw UnknownHostException("Unable to resolve $domain using Android DNS or IP-bootstrapped DoH")
    }

    private fun queryDohJson(host: String, resolverIp: String, path: String): String {
        if (!running.get()) throw SocketException("Proxy is stopped")
        val raw = Socket()
        if (!registerUpstreamSocket(raw)) throw SocketException("Proxy stopped before DoH connect")
        var tls: SSLSocket? = null
        var rawStillTracked = true
        try {
            raw.connect(InetSocketAddress(resolverIp, 443), 5000)
            if (!running.get()) throw SocketException("Proxy stopped during DoH connect")
            raw.soTimeout = 6000
            val connectedTls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, 443, true) as SSLSocket
            tls = connectedTls
            if (!registerUpstreamSocket(connectedTls)) throw SocketException("Proxy stopped before DoH TLS")
            activeUpstreams.remove(raw)
            rawStillTracked = false
            connectedTls.soTimeout = 6000
            val parameters = connectedTls.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            parameters.serverNames = listOf(SNIHostName(host))
            connectedTls.sslParameters = parameters
            connectedTls.startHandshake()
            if (!running.get()) throw SocketException("Proxy stopped during DoH TLS handshake")

            val request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: " + host + "\r\n" +
                "Accept: application/dns-json\r\n" +
                "Accept-Encoding: identity\r\n" +
                "User-Agent: TGTunnel/1.0\r\n" +
                "Connection: close\r\n\r\n"
            connectedTls.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }

            val input = connectedTls.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            var matched = 0
            val delimiter = byteArrayOf(13, 10, 13, 10)
            while (headerBytes.size() < 16 * 1024 && matched < 4) {
                val value = input.read()
                if (value < 0) throw SocketException("DoH server closed before HTTP headers")
                headerBytes.write(value)
                matched = if (value == delimiter[matched].toInt()) matched + 1
                    else if (value == 13) 1 else 0
            }
            if (matched != 4) throw SocketException("Invalid DoH HTTP headers")
            val lines = headerBytes.toString("ISO-8859-1").split("\r\n")
            if (lines.firstOrNull()?.contains(" 200 ") != true) {
                throw SocketException("DoH HTTP response: " + (lines.firstOrNull() ?: "empty"))
            }
            val headers = lines.drop(1).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }.toMap()
            val body = input.readBytes()
            val decoded = if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
                decodeHttpChunks(body)
            } else {
                val length = headers["content-length"]?.toIntOrNull()
                if (length != null && body.size < length) throw SocketException("Truncated DoH response")
                if (length != null) body.copyOf(length) else body
            }
            return decoded.toString(Charsets.UTF_8)
        } finally {
            if (rawStillTracked) unregisterUpstreamSocket(raw)
            tls?.let(::unregisterUpstreamSocket)
        }
    }

    private fun decodeHttpChunks(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < data.size) {
            val lineEnd = (offset until data.size - 1).firstOrNull {
                data[it] == 13.toByte() && data[it + 1] == 10.toByte()
            } ?: throw SocketException("Malformed chunked DoH response")
            val sizeText = data.copyOfRange(offset, lineEnd).toString(Charsets.US_ASCII)
                .substringBefore(';').trim()
            val size = sizeText.toIntOrNull(16) ?: throw SocketException("Invalid HTTP chunk size")
            offset = lineEnd + 2
            if (size == 0) break
            if (size < 0 || offset + size + 2 > data.size) throw SocketException("Truncated HTTP chunk")
            output.write(data, offset, size)
            offset += size
            if (data[offset] != 13.toByte() || data[offset + 1] != 10.toByte()) {
                throw SocketException("Malformed HTTP chunk terminator")
            }
            offset += 2
        }
        return output.toByteArray()
    }

    private fun buildDnsAQuery(domain: String, id: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write((id ushr 8) and 0xff)
        output.write(id and 0xff)
        output.write(0x01); output.write(0x00)
        output.write(0x00); output.write(0x01)
        output.write(ByteArray(6))
        domain.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.isNotEmpty() && bytes.size <= 63) { "Invalid DNS label" }
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        output.write(0); output.write(1)
        output.write(0); output.write(1)
        return output.toByteArray()
    }

    private fun parseDnsAResponse(data: ByteArray, expectedId: Int, hostname: String): List<InetAddress> {
        if (data.size < 12) return emptyList()
        fun u16(offset: Int): Int =
            ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        if (u16(0) != expectedId || (data[2].toInt() and 0x80) == 0 || (data[3].toInt() and 0x0f) != 0) {
            return emptyList()
        }
        val questions = u16(4)
        val answers = u16(6)
        var offset = 12
        repeat(questions) {
            while (offset < data.size && data[offset].toInt() != 0) {
                val length = data[offset].toInt() and 0xff
                if ((length and 0xc0) == 0xc0) {
                    offset += 2
                    break
                }
                offset += length + 1
            }
            if (offset < data.size) offset++
            offset += 4
        }
        val result = mutableListOf<InetAddress>()
        repeat(answers) {
            if (offset >= data.size) return@repeat
            val first = data[offset].toInt() and 0xff
            if ((first and 0xc0) == 0xc0) offset += 2
            else {
                while (offset < data.size && data[offset].toInt() != 0) {
                    val length = data[offset].toInt() and 0xff
                    offset += length + 1
                }
                offset++
            }
            if (offset + 10 > data.size) return@repeat
            val type = u16(offset)
            val clazz = u16(offset + 2)
            offset += 8
            val length = u16(offset)
            offset += 2
            if (offset + length > data.size) return@repeat
            if (type == 1 && clazz == 1 && length == 4) {
                result.add(InetAddress.getByAddress(hostname, data.copyOfRange(offset, offset + 4)))
            }
            offset += length
        }
        return result.distinctBy { it.hostAddress }
    }

    private fun startCfProxyDomainRefresh() {
        cfProxyRefreshThread?.interrupt()
        cfProxyRefreshThread = Thread({
            while (running.get() && cloudflareProxyEnabled) {
                refreshCfProxyDomains()
                try {
                    Thread.sleep(60L * 60L * 1000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "tgtunnel-cf-domain-refresh").apply {
            isDaemon = true
            start()
        }
    }

    private fun refreshCfProxyDomains() {
        val url = "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "TgTunnel-Android")
            val fetched = connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map(::decodeCfProxyDomain)
                    .filter(::isValidDomain)
                    .distinct()
                    .toList()
            }
            if (fetched.size >= 3 && running.get() && cloudflareProxyEnabled) {
                cfProxyDomains = fetched
                // Retain healthy DC routes while their domains remain in the refreshed pool.
                activeCfProxyDomains.entries.removeIf { entry -> !fetched.contains(entry.value) }
                log("Cloudflare Proxy domain pool refreshed: " + fetched.size + " domains")
            } else {
                log("Cloudflare Proxy refresh returned too few valid domains; keeping current pool")
            }
        } catch (error: Exception) {
            log("Cloudflare Proxy domain refresh failed; keeping built-in pool: " +
                (error.message ?: error.javaClass.simpleName))
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.isBlank() || domain.length > 253 || domain.startsWith(".") || domain.endsWith(".")) return false
        val labels = domain.split(".")
        if (labels.size < 2) return false
        return labels.all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first() != '-' && label.last() != '-' &&
                label.all { it.isLetterOrDigit() || it == '-' }
        } && labels.last().length >= 2 && labels.last().any { it.isLetter() }
    }

    private fun decodeCfProxyDomain(encoded: String): String {
        val prefix = encoded.removeSuffix(".com")
        val shift = prefix.count { it.isLetter() }
        val decoded = prefix.map { char ->
            when (char) {
                in 'a'..'z' -> 'a' + (char - 'a' - shift + 26) % 26
                in 'A'..'Z' -> 'A' + (char - 'A' - shift + 26) % 26
                else -> char
            }
        }.joinToString("")
        return decoded + ".co.uk"
    }

    private fun parseWorkerDomains(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val hosts = value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val hostnamePattern = Regex("^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$")
        if (hosts.size != hosts.distinct().size || hosts.any {
                !hostnamePattern.matches(it) || "." !in it || ".." in it || it.startsWith(".")
            }) return emptyList()
        return hosts
    }

    private fun parseDcIpList(value: String): Map<Int, String>? {
        if (value.isBlank()) return emptyMap()
        val result = linkedMapOf<Int, String>()
        for (entry in value.replace(';', ',').split(',')) {
            val parts = entry.trim().split(':', limit = 2)
            if (parts.size != 2) return null
            val dc = parts[0].trim().toIntOrNull() ?: return null
            val ip = parts[1].trim()
            if (dc !in 1..5 && dc != 203) return null
            val octets = ip.split('.')
            if (octets.size != 4 || octets.any { it.toIntOrNull()?.let { n -> n in 0..255 } != true }) {
                return null
            }
            if (result.put(dc, ip) != null) return null
        }
        return result
    }

    private fun decodeHandshake(encrypted: ByteArray, secret: ByteArray): Handshake? {
        val prekeyIv = encrypted.copyOfRange(8, 56)
        val keyMaterial = prekeyIv.copyOfRange(0, 32) + secret
        val key = sha256(keyMaterial)
        val iv = prekeyIv.copyOfRange(32, 48)
        val decrypt = aesCtr(Cipher.DECRYPT_MODE, key, iv)
        val plain = decrypt.doFinal(encrypted)
        val tag = plain.copyOfRange(56, 60)
        if (!tag.contentEquals(byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())) &&
            !tag.contentEquals(byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())) &&
            !tag.contentEquals(byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte()))) {
            return null
        }
        val dcSigned = (plain[60].toInt() and 0xff) or ((plain[61].toInt() and 0xff) shl 8)
        val signed = if ((dcSigned and 0x8000) != 0) dcSigned - 0x10000 else dcSigned
        if (signed == 0 || signed == -32768) return null
        return Handshake(tag, kotlin.math.abs(signed), signed < 0, prekeyIv)
    }

    private fun transportName(tag: ByteArray): String = when {
        tag.contentEquals(byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())) -> "abridged"
        tag.contentEquals(byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())) -> "intermediate"
        tag.contentEquals(byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())) -> "padded-intermediate"
        else -> "unknown"
    }

    private fun buildCryptoContext(handshake: Handshake, secret: ByteArray, relayInit: ByteArray): CryptoContext {
        val clientDecKey = sha256(handshake.clientPrekeyIv.copyOfRange(0, 32) + secret)
        val clientDecIv = handshake.clientPrekeyIv.copyOfRange(32, 48)
        val clientDecrypt = aesCtr(Cipher.ENCRYPT_MODE, clientDecKey, clientDecIv)
        clientDecrypt.update(ByteArray(HANDSHAKE_SIZE))

        val reversed = handshake.clientPrekeyIv.reversedArray()
        val clientEncKey = sha256(reversed.copyOfRange(0, 32) + secret)
        val clientEncIv = reversed.copyOfRange(32, 48)
        val clientEncrypt = aesCtr(Cipher.ENCRYPT_MODE, clientEncKey, clientEncIv)

        val relayKey = relayInit.copyOfRange(8, 40)
        val relayIv = relayInit.copyOfRange(40, 56)
        val telegramEncrypt = aesCtr(Cipher.ENCRYPT_MODE, relayKey, relayIv)
        telegramEncrypt.update(ByteArray(HANDSHAKE_SIZE))

        val reversedRelay = relayInit.copyOfRange(8, 56).reversedArray()
        val telegramDecrypt = aesCtr(
            Cipher.ENCRYPT_MODE,
            reversedRelay.copyOfRange(0, 32),
            reversedRelay.copyOfRange(32, 48)
        )
        return CryptoContext(clientDecrypt, clientEncrypt, telegramEncrypt, telegramDecrypt)
    }

    private fun generateRelayInit(handshake: Handshake): ByteArray {
        val relay = ByteArray(HANDSHAKE_SIZE)
        var valid = false
        for (attempt in 0 until 128) {
            random.nextBytes(relay)
            val first = relay[0].toInt() and 0xff
            val start = relay.copyOfRange(0, 4)
            val reserved = first == 0xef ||
                start.contentEquals("HEAD".toByteArray()) ||
                start.contentEquals("POST".toByteArray()) ||
                start.contentEquals("GET ".toByteArray()) ||
                start.contentEquals(byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())) ||
                start.contentEquals(byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())) ||
                start.contentEquals(byteArrayOf(0x16, 0x03, 0x01, 0x02)) ||
                start.contentEquals("OPTI".toByteArray()) ||
                relay.copyOfRange(4, 8).all { it == 0.toByte() }
            if (!reserved) {
                valid = true
                break
            }
        }
        if (!valid) throw IllegalStateException("Could not generate a valid relay init")
        val key = relay.copyOfRange(8, 40)
        val iv = relay.copyOfRange(40, 56)
        val stream = aesCtr(Cipher.ENCRYPT_MODE, key, iv).doFinal(relay)
        val dcSigned = if (handshake.media) -handshake.dcId else handshake.dcId
        val tail = ByteArray(8)
        handshake.transportTag.copyInto(tail, 0)
        tail[4] = (dcSigned and 0xff).toByte()
        tail[5] = ((dcSigned ushr 8) and 0xff).toByte()
        val randomTail = ByteArray(2).also { random.nextBytes(it) }
        randomTail.copyInto(tail, 6)
        for (i in 0 until 8) {
            val position = 56 + i
            val keystream = (stream[position].toInt() xor relay[position].toInt()).toByte()
            relay[position] = (tail[i].toInt() xor keystream.toInt()).toByte()
        }
        return relay
    }


    private fun aesCtr(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun parseSecret(hex: String): ByteArray? {
        if (hex.length != 32) return null
        return try {
            ByteArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) {
            null
        }
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) throw SocketException("Client disconnected during MTProto handshake")
            offset += count
        }
        return result
    }

    private fun log(message: String) {
        logCallback?.invoke(message)
    }
}
