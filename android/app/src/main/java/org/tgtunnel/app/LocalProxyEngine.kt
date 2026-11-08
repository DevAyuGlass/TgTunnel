package org.tgtunnel.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
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
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()
    private val activeUpstreams = ConcurrentHashMap.newKeySet<Socket>()
    private val running = AtomicBoolean(false)

    @Volatile private var listener: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var logCallback: ((String) -> Unit)? = null
    @Volatile private var workerDomains: List<String> = emptyList()

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

    private class WebSocketUpstream(private val socket: SSLSocket) : Upstream {
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
                if (mask != null) {
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                }
                when (opcode) {
                    0x8 -> {
                        runCatching { sendFrame(0x8, payload.take(2).toByteArray()) }
                        closed.set(true)
                        return null
                    }
                    0x9 -> {
                        sendFrame(0xA, payload)
                    }
                    0xA -> Unit
                    0x2, 0x0, 0x1 -> {
                        if (opcode != 0x0 && fragments.size() > 0) fragments.reset()
                        fragments.write(payload)
                        if (fragments.size() > 16 * 1024 * 1024) {
                            throw SocketException("WebSocket message exceeds 16 MiB")
                        }
                        if (fin) {
                            val result = fragments.toByteArray()
                            fragments.reset()
                            if (opcode == 0x1) continue
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

    fun info(): String = "Android MTProto bridge · Telegram WS + Cloudflare Worker fallback"

    fun isRunning(): Boolean = running.get()

    fun start(port: Int, secretHex: String, workerDomain: String, onLog: (String) -> Unit): String {
        if (port !in 1..65535) return "ERR:Port must be between 1 and 65535"
        val secret = parseSecret(secretHex) ?: return "ERR:Secret must contain exactly 32 hexadecimal characters"
        val configuredWorkers = parseWorkerDomains(workerDomain)
        if (workerDomain.isNotBlank() && configuredWorkers.isEmpty()) {
            return "ERR:Worker domain must be a hostname only (for example name.username.workers.dev)"
        }
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
                listener = boundServer
                running.set(true)
                acceptThread = Thread({ acceptLoop(boundServer, secret) }, "tgtunnel-accept").apply {
                    isDaemon = true
                    start()
                }
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
                Thread({
                    try {
                        handleClient(client, secret)
                    } finally {
                        activeClients.remove(client)
                        runCatching { client.close() }
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

    private fun handleClient(client: Socket, secret: ByteArray) {
        var upstream: Upstream? = null
        try {
            client.soTimeout = 10000
            val header = readExact(client.getInputStream(), HANDSHAKE_SIZE)
            val handshake = decodeHandshake(header, secret) ?: run {
                log("Rejected client: invalid MTProto handshake or secret")
                return
            }
            client.soTimeout = 0
            val relayInit = generateRelayInit(handshake)
            val crypto = buildCryptoContext(handshake, secret, relayInit)
            upstream = connectUpstream(handshake)
            if (upstream == null) {
                log("DC" + handshake.dcId + " upstream connection failed")
                return
            }
            when (upstream) {
                is TcpUpstream -> upstream.send(relayInit)
                is WebSocketUpstream -> upstream.send(relayInit)
            }
            activeUpstreams.add(extractSocket(upstream))
            log("Client connected · DC" + handshake.dcId + if (handshake.media) " media" else "")
            bridge(client, upstream, crypto)
        } catch (error: Exception) {
            if (running.get()) log("Client session error: " + (error.message ?: error.javaClass.simpleName))
        } finally {
            upstream?.close()
            upstream?.let { activeUpstreams.remove(extractSocket(it)) }
        }
    }

    private fun extractSocket(upstream: Upstream): Socket = upstream.socketForTracking()

    private fun bridge(client: Socket, upstream: Upstream, crypto: CryptoContext) {
        val finished = AtomicBoolean(false)
        val clientInput = client.getInputStream()
        val clientOutput = client.getOutputStream()
        val upstreamToClient = Thread({
            try {
                while (!finished.get() && running.get()) {
                    val data = upstream.receive() ?: break
                    val plain = crypto.telegramDecrypt.update(data) ?: ByteArray(0)
                    val encrypted = crypto.clientEncrypt.update(plain) ?: ByteArray(0)
                    if (encrypted.isNotEmpty()) {
                        clientOutput.write(encrypted)
                        clientOutput.flush()
                    }
                }
            } catch (_: Exception) {
                // Closing either side ends the session.
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
                if (count < 0) break
                val data = buffer.copyOf(count)
                val plain = crypto.clientDecrypt.update(data) ?: ByteArray(0)
                val encrypted = crypto.telegramEncrypt.update(plain) ?: ByteArray(0)
                if (encrypted.isNotEmpty()) upstream.send(encrypted)
            }
        } finally {
            finished.set(true)
            runCatching { client.close() }
            upstream.close()
            runCatching { upstreamToClient.join(1000) }
        }
    }

    private fun connectUpstream(handshake: Handshake): Upstream? {
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
        for (domain in domains) {
            try {
                val ws = connectWebSocket(domain)
                log("Connected to " + domain + " over TLS WebSocket")
                return ws
            } catch (error: Exception) {
                log("WebSocket " + domain + " failed: " + (error.message ?: error.javaClass.simpleName))
            }
        }

        val workers = workerDomains
        if (workers.isNotEmpty()) {
            for (worker in workers) {
                for (destination in domains) {
                    try {
                        val path = "/apiws?dst=" + URLEncoder.encode(destination, "UTF-8")
                        val upstream = connectWebSocket(worker, path)
                        log("Connected to " + destination + " through Cloudflare Worker " + worker)
                        return upstream
                    } catch (error: Exception) {
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
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, 443), 10000)
            socket.tcpNoDelay = true
            activeUpstreams.add(socket)
            log("Using direct TCP fallback to DC" + handshake.dcId)
            TcpUpstream(socket)
        } catch (error: Exception) {
            log("TCP fallback failed: " + (error.message ?: error.javaClass.simpleName))
            null
        }
    }

    private fun connectWebSocket(domain: String, requestPath: String = "/apiws"): WebSocketUpstream {
        val socket = SSLSocketFactory.getDefault().createSocket(domain, 443) as SSLSocket
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 12000
            val parameters = socket.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = parameters
            socket.startHandshake()

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
            socket.soTimeout = 0
            return WebSocketUpstream(socket)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
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
