package org.tgtunnel.app

import java.io.ByteArrayOutputStream

/**
 * Reassembles the decrypted MTProto byte stream and emits one transport packet
 * per WebSocket binary message, matching Flowseal's MsgSplitter behaviour.
 *
 * Ciphertext and plaintext are buffered in lockstep: packet boundaries are
 * found in plaintext, while the exact corresponding ciphertext bytes are sent.
 */
internal class MtprotoMsgSplitter(transportTag: ByteArray) {
    private val protocol = when {
        transportTag.contentEquals(byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())) -> ABRIDGED
        transportTag.contentEquals(byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())) -> INTERMEDIATE
        transportTag.contentEquals(byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())) -> PADDED_INTERMEDIATE
        else -> UNKNOWN
    }
    private val cipherBuffer = ByteArrayOutputStream()
    private val plainBuffer = ByteArrayOutputStream()
    private var disabled = protocol == UNKNOWN
    private var firstFrameDiagnostic: String? = null

    @Synchronized
    fun split(ciphertext: ByteArray, plaintext: ByteArray): List<ByteArray> {
        if (ciphertext.isEmpty()) return emptyList()
        require(ciphertext.size == plaintext.size) { "Cipher/plain stream length mismatch" }
        if (disabled) return listOf(ciphertext)

        cipherBuffer.write(ciphertext)
        plainBuffer.write(plaintext)
        val cipher = cipherBuffer.toByteArray()
        val plain = plainBuffer.toByteArray()
        val packets = ArrayList<ByteArray>()
        var offset = 0

        while (offset < plain.size) {
            val available = plain.size - offset
            val packetLength = nextPacketLength(plain, offset, available)
            if (packetLength == null) {
                if (firstFrameDiagnostic == null || firstFrameDiagnostic!!.startsWith("pending")) {
                    firstFrameDiagnostic = describeFrame(plain, offset, available, complete = false)
                }
                break
            }
            if (packetLength <= 0 || packetLength > available) {
                if (firstFrameDiagnostic == null || firstFrameDiagnostic!!.startsWith("pending")) {
                    firstFrameDiagnostic = describeFrame(plain, offset, available, complete = false) + "; invalid framing"
                }
                disabled = true
                if (offset == 0) packets += cipher
                else packets += cipher.copyOfRange(offset, cipher.size)
                offset = cipher.size
                break
            }
            if (firstFrameDiagnostic == null || firstFrameDiagnostic!!.startsWith("pending")) {
                firstFrameDiagnostic = describeFrame(plain, offset, available, complete = true)
            }
            packets += cipher.copyOfRange(offset, offset + packetLength)
            offset += packetLength
        }

        if (offset > 0) {
            cipherBuffer.reset()
            plainBuffer.reset()
            if (offset < cipher.size) {
                cipherBuffer.write(cipher, offset, cipher.size - offset)
                plainBuffer.write(plain, offset, plain.size - offset)
            }
        }
        return packets
    }

    @Synchronized
    fun diagnosticSnapshot(): String {
        val buffered = cipherBuffer.size()
        val state = firstFrameDiagnostic ?: if (disabled) "splitter disabled" else "first frame not parsed"
        return "transport=${protocolName()} $state buffered=$buffered disabled=$disabled"
    }

    private fun protocolName(): String = when (protocol) {
        ABRIDGED -> "abridged"
        INTERMEDIATE -> "intermediate"
        PADDED_INTERMEDIATE -> "padded-intermediate"
        else -> "unknown"
    }

    private fun describeFrame(data: ByteArray, offset: Int, available: Int, complete: Boolean): String {
        if (available <= 0) return "pending: no plaintext bytes"
        return when (protocol) {
            ABRIDGED -> {
                val first = data[offset].toInt() and 0xff
                val header = if (first == 0x7f || first == 0xff) 4 else 1
                if (available < header) return "pending: abridged header bytes=$available/$header"
                val words = if (header == 4) {
                    (data[offset + 1].toInt() and 0xff) or
                        ((data[offset + 2].toInt() and 0xff) shl 8) or
                        ((data[offset + 3].toInt() and 0xff) shl 16)
                } else first and 0x7f
                val payload = words.toLong() * 4L
                "${if (complete) "frame" else "pending"}: abridged header=$header words=$words payload=$payload total=${header + payload} available=$available"
            }
            INTERMEDIATE, PADDED_INTERMEDIATE -> {
                if (available < 4) return "pending: ${protocolName()} header bytes=$available/4"
                val raw = (data[offset].toLong() and 0xffL) or
                    ((data[offset + 1].toLong() and 0xffL) shl 8) or
                    ((data[offset + 2].toLong() and 0xffL) shl 16) or
                    ((data[offset + 3].toLong() and 0xffL) shl 24)
                val payload = raw and 0x7fffffffL
                val quickAck = (raw and 0x80000000L) != 0L
                "${if (complete) "frame" else "pending"}: ${protocolName()} payload=$payload total=${4L + payload} quickAck=$quickAck available=$available"
            }
            else -> "unknown transport framing"
        }
    }

    @Synchronized
    fun flush(): List<ByteArray> {
        if (cipherBuffer.size() == 0) return emptyList()
        val tail = cipherBuffer.toByteArray()
        cipherBuffer.reset()
        plainBuffer.reset()
        return listOf(tail)
    }

    private fun nextPacketLength(data: ByteArray, offset: Int, available: Int): Int? {
        if (available <= 0) return null
        return when (protocol) {
            ABRIDGED -> {
                val first = data[offset].toInt() and 0xff
                val header: Int
                val payload: Long
                if (first == 0x7f || first == 0xff) {
                    if (available < 4) return null
                    header = 4
                    payload = ((data[offset + 1].toInt() and 0xff) or
                        ((data[offset + 2].toInt() and 0xff) shl 8) or
                        ((data[offset + 3].toInt() and 0xff) shl 16)).toLong() * 4L
                } else {
                    header = 1
                    payload = (first and 0x7f).toLong() * 4L
                }
                if (payload <= 0L || payload > MAX_PACKET_SIZE) return -1
                val total = header + payload
                if (available.toLong() < total) null else total.toInt()
            }
            INTERMEDIATE, PADDED_INTERMEDIATE -> {
                if (available < 4) return null
                val payload = ((data[offset].toLong() and 0xffL) or
                    ((data[offset + 1].toLong() and 0xffL) shl 8) or
                    ((data[offset + 2].toLong() and 0xffL) shl 16) or
                    ((data[offset + 3].toLong() and 0xffL) shl 24)) and 0x7fffffffL
                if (payload <= 0L || payload > MAX_PACKET_SIZE) return -1
                val total = 4L + payload
                if (available.toLong() < total) null else total.toInt()
            }
            else -> -1
        }
    }

    private companion object {
        const val ABRIDGED = 1
        const val INTERMEDIATE = 2
        const val PADDED_INTERMEDIATE = 3
        const val UNKNOWN = 0
        const val MAX_PACKET_SIZE = 16L * 1024L * 1024L
    }
}
