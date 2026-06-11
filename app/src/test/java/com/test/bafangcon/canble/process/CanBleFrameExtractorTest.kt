package com.test.bafangcon.canble.process

import com.test.bafangcon.canble.model.CanBleFrame
import org.junit.Assert.*
import org.junit.Test

class CanBleFrameExtractorTest {

    @Test
    fun `empty array returns empty list`() {
        val result = CanBleFrameExtractor.extract(byteArrayOf())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single minimal frame with payloadLen zero`() {
        val data = byteArrayOf(
            0x02,
            ((0x13 shl 3) or 0x02).toByte(),
            0x60,
            0x01,
            0x00
        )
        val result = CanBleFrameExtractor.extract(data)
        assertEquals(1, result.size)

        val frame = result[0]
        assertEquals(2, frame.sourceNode)
        assertEquals(19, frame.destNode)
        assertEquals(2, frame.op)
        assertEquals(0x60, frame.func)
        assertEquals(0x01, frame.index)
        assertEquals(0, frame.payloadLen)
        assertTrue(frame.payload.isEmpty())
    }

    @Test
    fun `single frame with 8-byte payload`() {
        val data = byteArrayOf(
            0x02,
            ((0x13 shl 3) or 0x02).toByte(),
            0x60,
            0x01,
            0x08,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        )
        val result = CanBleFrameExtractor.extract(data)
        assertEquals(1, result.size)
        assertEquals(8, result[0].payloadLen)
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            result[0].payload
        )
    }

    @Test
    fun `two frames packed`() {
        val frame1 = byteArrayOf(
            0x02, ((0x13 shl 3) or 0x02).toByte(), 0x60, 0x01, 0x01, 0xAA.toByte()
        )
        val frame2 = byteArrayOf(
            0x03, ((0x13 shl 3) or 0x00).toByte(), 0x61, 0x02, 0x02, 0xBB.toByte(), 0xCC.toByte()
        )
        val data = frame1 + frame2

        val result = CanBleFrameExtractor.extract(data)
        assertEquals(2, result.size)
        assertEquals(2, result[0].sourceNode)
        assertEquals(0x60, result[0].func)
        assertEquals(3, result[1].sourceNode)
        assertEquals(0x61, result[1].func)
    }

    @Test
    fun `truncated frame at end stops safely`() {
        val data = byteArrayOf(
            0x02, ((0x13 shl 3) or 0x02).toByte(), 0x60, 0x01,
            0x08,
            0x01, 0x02, 0x03
        )
        val result = CanBleFrameExtractor.extract(data)
        assertTrue("Truncated frame should not be extracted", result.isEmpty())
    }

    @Test
    fun `truncated header stops safely`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val result = CanBleFrameExtractor.extract(data)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `payloadLen above 8 is extracted anyway`() {
        val payload = ByteArray(10) { it.toByte() }
        val data = byteArrayOf(
            0x02, ((0x13 shl 3) or 0x02).toByte(), 0x60, 0x01,
            10
        ) + payload

        val result = CanBleFrameExtractor.extract(data)
        assertEquals(1, result.size)
        assertEquals(10, result[0].payloadLen)
        assertArrayEquals(payload, result[0].payload)
    }

    @Test
    fun `payloadLen 0x80 treated as unsigned 128 stops when payload incomplete`() {
        val data = byteArrayOf(
            0x02, ((0x13 shl 3) or 0x02).toByte(), 0x60, 0x01,
            0x80.toByte(),
            0x01, 0x02, 0x03
        )
        val result = CanBleFrameExtractor.extract(data)
        assertTrue("Incomplete frame with payloadLen=128 should not be extracted", result.isEmpty())
    }

    @Test
    fun `frame with max field values`() {
        val data = byteArrayOf(
            31,
            ((31 shl 3) or 7).toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0x00
        )
        val result = CanBleFrameExtractor.extract(data)
        assertEquals(1, result.size)

        val frame = result[0]
        assertEquals(31, frame.sourceNode)
        assertEquals(31, frame.destNode)
        assertEquals(7, frame.op)
        assertEquals(0xFF, frame.func)
        assertEquals(0xFF, frame.index)
        assertEquals(0, frame.payloadLen)
    }


}
