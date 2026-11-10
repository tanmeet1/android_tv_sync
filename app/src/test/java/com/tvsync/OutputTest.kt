package com.tvsync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputTest {
    @Test
    fun ddpSplitsLargeStripsAndPushesOnLastPacket() {
        val rgb = ByteArray(500 * 3) { it.toByte() }
        val packets = Ddp.packets(rgb, sequence = 5)

        assertEquals(2, packets.size)
        val first = packets[0]
        assertEquals(0x40, first[0].toInt())
        assertEquals(5, first[1].toInt())
        assertEquals(0x0B, first[2].toInt())
        assertEquals(0x01, first[3].toInt())
        assertEquals(Ddp.HEADER_SIZE + Ddp.MAX_DATA, first.size)

        val second = packets[1]
        assertEquals(0x41, second[0].toInt())
        val offset = ((second[4].toInt() and 0xff) shl 24) or ((second[5].toInt() and 0xff) shl 16) or
            ((second[6].toInt() and 0xff) shl 8) or (second[7].toInt() and 0xff)
        assertEquals(Ddp.MAX_DATA, offset)
        val length = ((second[8].toInt() and 0xff) shl 8) or (second[9].toInt() and 0xff)
        assertEquals(rgb.size - Ddp.MAX_DATA, length)
        assertEquals(rgb[Ddp.MAX_DATA], second[Ddp.HEADER_SIZE])
    }

    @Test
    fun lampColorSeparatesHueFromBrightness() {
        val (rgb, brightness) = LampColor.toHomeAssistant(0x402010, minBrightnessPct = 5)
        assertArrayEquals(intArrayOf(255, 128, 64), rgb)
        assertEquals(0x40, brightness)
    }

    @Test
    fun lampColorNeverGoesBelowMinimumBrightness() {
        val (_, dim) = LampColor.toHomeAssistant(0x010101, minBrightnessPct = 10)
        assertEquals(26, dim)
        val (rgb, black) = LampColor.toHomeAssistant(0x000000, minBrightnessPct = 10)
        assertArrayEquals(intArrayOf(255, 255, 255), rgb)
        assertEquals(26, black)
    }
}
