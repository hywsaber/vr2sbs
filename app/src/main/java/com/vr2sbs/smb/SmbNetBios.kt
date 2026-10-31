package com.vr2sbs.smb

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resolves a friendly device name from a LAN host using a NetBIOS node status
 * request. Most Windows machines answer this without any extra configuration.
 */
internal object SmbNetBios {
    private const val NBNS_PORT = 137
    private const val NODE_STATUS_TYPE = 0x0021
    private const val NODE_STATUS_CLASS = 0x0001
    private const val DEFAULT_TIMEOUT_MS = 500
    private const val MAX_RESPONSE_BYTES = 2048

    fun queryName(host: InetAddress, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String? {
        val query = buildNodeStatusQuery()
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs.coerceAtLeast(1)
                socket.send(DatagramPacket(query, query.size, host, NBNS_PORT))
                val buffer = ByteArray(MAX_RESPONSE_BYTES)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                if (response.address != host) {
                    null
                } else {
                    parseNodeStatusResponse(response.data, response.length)
                }
            }
        }.getOrNull()
    }

    internal fun buildNodeStatusQuery(transactionId: Int = 0x4E42): ByteArray {
        val encodedName = encodeNetBiosName("*")
        val buffer = ByteBuffer
            .allocate(12 + 1 + encodedName.size + 1 + 4)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(transactionId.toShort())
        buffer.putShort(0) // flags
        buffer.putShort(1) // questions
        buffer.putShort(0) // answers
        buffer.putShort(0) // authority records
        buffer.putShort(0) // additional records
        buffer.put(encodedName.size.toByte())
        buffer.put(encodedName)
        buffer.put(0) // end of question name
        buffer.putShort(NODE_STATUS_TYPE.toShort())
        buffer.putShort(NODE_STATUS_CLASS.toShort())
        return buffer.array()
    }

    internal fun parseNodeStatusResponse(data: ByteArray, length: Int): String? {
        if (length < 12 || length > data.size) return null
        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        buffer.short // transaction id
        buffer.short // flags
        val questionCount = buffer.short.toInt() and 0xFFFF
        val answerCount = buffer.short.toInt() and 0xFFFF
        buffer.short // authority records
        buffer.short // additional records

        repeat(questionCount) {
            if (!skipName(buffer) || buffer.remaining() < 4) return null
            buffer.position(buffer.position() + 4)
        }
        if (answerCount <= 0) return null

        val names = mutableListOf<NodeName>()
        repeat(answerCount) {
            if (!skipName(buffer) || buffer.remaining() < 10) return null
            val type = buffer.short.toInt() and 0xFFFF
            buffer.short // class
            buffer.int // ttl
            val dataLength = buffer.short.toInt() and 0xFFFF
            if (dataLength < 1 || buffer.remaining() < dataLength) return null
            val dataStart = buffer.position()
            if (type == NODE_STATUS_TYPE) {
                parseNodeNames(data, dataStart, dataStart + dataLength, names)
            }
            buffer.position(dataStart + dataLength)
        }
        return selectName(names)
    }

    private fun parseNodeNames(
        data: ByteArray,
        start: Int,
        end: Int,
        target: MutableList<NodeName>
    ) {
        if (start >= end) return
        val count = data[start].toInt() and 0xFF
        var offset = start + 1
        repeat(count) {
            if (offset + 18 > end) return
            val rawName = data.copyOfRange(offset, offset + 15)
            val suffix = data[offset + 15].toInt() and 0xFF
            val flags = ((data[offset + 16].toInt() and 0xFF) shl 8) or
                (data[offset + 17].toInt() and 0xFF)
            val name = String(rawName, Charsets.US_ASCII).trimEnd(' ', '\u0000')
            if (name.isNotEmpty()) target += NodeName(name, suffix, flags)
            offset += 18
        }
    }

    private fun selectName(names: List<NodeName>): String? {
        val unique = names.filter { (it.flags and GROUP_NAME_FLAG) == 0 }
        return unique.firstOrNull { it.suffix == 0x00 }?.name
            ?: unique.firstOrNull { it.suffix == 0x20 }?.name
            ?: unique.firstOrNull { it.suffix != 0x1C && it.suffix != 0x1E }?.name
            ?: names.firstOrNull()?.name
    }

    private fun skipName(buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) {
            val length = buffer.get().toInt() and 0xFF
            if (length == 0) return true
            if ((length and 0xC0) == 0xC0) {
                if (!buffer.hasRemaining()) return false
                buffer.get() // compression pointer low byte
                return true
            }
            if ((length and 0xC0) != 0 || buffer.remaining() < length) return false
            buffer.position(buffer.position() + length)
        }
        return false
    }

    private fun encodeNetBiosName(name: String): ByteArray {
        val padded = name.padEnd(16, '\u0000').take(16)
        val encoded = ByteArray(32)
        padded.forEachIndexed { index, character ->
            val value = character.code
            encoded[index * 2] = ('A'.code + ((value shr 4) and 0x0F)).toByte()
            encoded[index * 2 + 1] = ('A'.code + (value and 0x0F)).toByte()
        }
        return encoded
    }

    private data class NodeName(
        val name: String,
        val suffix: Int,
        val flags: Int
    )

    private const val GROUP_NAME_FLAG = 0x8000
}
