package org.tgtunnel.app

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel

/**
 * Socket decorator that splits the first TLS ClientHello record into two
 * handshake records. This is a TLS-record-level fragmentation primitive;
 * it does not promise separate TCP segments (the kernel may coalesce writes).
 */
internal class TlsClientHelloFragmentingSocket(
    private val delegate: Socket,
    private val onFragmented: () -> Unit = {}
) : Socket() {
    private val fragmentedOutput = ClientHelloFragmentingOutputStream(delegate.getOutputStream(), onFragmented)

    override fun getInputStream() = delegate.getInputStream()
    override fun getOutputStream(): OutputStream = fragmentedOutput
    override fun close() = delegate.close()
    override fun isConnected() = delegate.isConnected
    override fun isBound() = delegate.isBound
    override fun isClosed() = delegate.isClosed
    override fun isInputShutdown() = delegate.isInputShutdown
    override fun isOutputShutdown() = delegate.isOutputShutdown
    override fun getInetAddress() = delegate.inetAddress
    override fun getLocalAddress() = delegate.localAddress
    override fun getPort() = delegate.port
    override fun getLocalPort() = delegate.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun getChannel(): SocketChannel? = delegate.channel
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun toString(): String = "TlsClientHelloFragmentingSocket($delegate)"

    private class ClientHelloFragmentingOutputStream(
        private val output: OutputStream,
        private val onFragmented: () -> Unit
    ) : OutputStream() {
        private val pending = ByteArrayOutputStream()
        private var decided = false

        @Synchronized
        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            if (decided) {
                output.write(bytes, offset, length)
                return
            }

            pending.write(bytes, offset, length)
            val buffered = pending.toByteArray()
            if (buffered.size < TLS_RECORD_HEADER_SIZE) return

            val contentType = buffered[0].toInt() and 0xff
            val majorVersion = buffered[1].toInt() and 0xff
            val recordLength = ((buffered[3].toInt() and 0xff) shl 8) or
                (buffered[4].toInt() and 0xff)

            if (contentType != TLS_HANDSHAKE || majorVersion != 3 ||
                recordLength <= MIN_FRAGMENT_SIZE || recordLength > MAX_TLS_RECORD_SIZE
            ) {
                decided = true
                output.write(buffered)
                pending.reset()
                return
            }

            val totalLength = TLS_RECORD_HEADER_SIZE + recordLength
            if (buffered.size < totalLength) return

            val firstPayloadLength = recordLength / 2
            writeRecord(buffered, TLS_RECORD_HEADER_SIZE, firstPayloadLength)
            writeRecord(
                buffered,
                TLS_RECORD_HEADER_SIZE + firstPayloadLength,
                recordLength - firstPayloadLength
            )
            if (buffered.size > totalLength) {
                output.write(buffered, totalLength, buffered.size - totalLength)
            }
            output.flush()
            pending.reset()
            decided = true
            onFragmented()
        }

        private fun writeRecord(source: ByteArray, payloadOffset: Int, payloadLength: Int) {
            output.write(TLS_HANDSHAKE)
            output.write(source, 1, 2) // preserve legacy record-layer version
            output.write((payloadLength ushr 8) and 0xff)
            output.write(payloadLength and 0xff)
            output.write(source, payloadOffset, payloadLength)
        }

        @Synchronized
        override fun flush() {
            // Keep an incomplete first record buffered. It is emitted once the
            // complete record arrives, or unchanged if the stream is closed.
            if (decided) output.flush()
        }

        @Synchronized
        override fun close() {
            if (!decided && pending.size() > 0) {
                output.write(pending.toByteArray())
                pending.reset()
                decided = true
            }
            output.flush()
        }

        private companion object {
            const val TLS_HANDSHAKE = 22
            const val TLS_RECORD_HEADER_SIZE = 5
            const val MAX_TLS_RECORD_SIZE = 18432
            const val MIN_FRAGMENT_SIZE = 8
        }
    }
}
