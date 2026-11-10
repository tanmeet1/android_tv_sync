package com.tvsync

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Streams strip colors to WLED using the DDP protocol over UDP. */
class WledDdpSender(host: String, private val port: Int = Ddp.PORT) : Closeable {
    private val address: InetAddress = InetAddress.getByName(host)
    private val socket = DatagramSocket()
    private var sequence = 0

    fun send(rgb: ByteArray) {
        sequence = sequence % 15 + 1
        for (packet in Ddp.packets(rgb, sequence)) {
            socket.send(DatagramPacket(packet, packet.size, address, port))
        }
    }

    override fun close() = socket.close()
}

object Ddp {
    const val PORT = 4048
    const val HEADER_SIZE = 10

    /** 480 RGB LEDs per packet keeps datagrams under a typical 1500-byte MTU. */
    const val MAX_DATA = 1440

    private const val VERSION_1 = 0x40
    private const val PUSH = 0x01
    private const val TYPE_RGB_8BIT = 0x0B
    private const val DEFAULT_OUTPUT = 0x01

    fun packets(rgb: ByteArray, sequence: Int): List<ByteArray> {
        val packets = ArrayList<ByteArray>((rgb.size + MAX_DATA - 1) / MAX_DATA)
        var offset = 0
        while (offset < rgb.size) {
            val length = minOf(MAX_DATA, rgb.size - offset)
            val last = offset + length >= rgb.size
            val p = ByteArray(HEADER_SIZE + length)
            p[0] = (VERSION_1 or if (last) PUSH else 0).toByte()
            p[1] = (sequence and 0x0F).toByte()
            p[2] = TYPE_RGB_8BIT.toByte()
            p[3] = DEFAULT_OUTPUT.toByte()
            p[4] = (offset ushr 24).toByte()
            p[5] = (offset ushr 16).toByte()
            p[6] = (offset ushr 8).toByte()
            p[7] = offset.toByte()
            p[8] = (length ushr 8).toByte()
            p[9] = length.toByte()
            System.arraycopy(rgb, offset, p, HEADER_SIZE, length)
            packets += p
            offset += length
        }
        return packets
    }
}
