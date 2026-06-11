package com.test.bafangcon.canble.log

import com.test.bafangcon.canble.model.CanBleFrame
import org.junit.Assert.*
import org.junit.Test

class CanBleMetricsCollectorTest {

    private fun frame(
        sourceNode: Int = 2,
        destNode: Int = 19,
        op: Int = 0,
        func: Int = 0x60,
        index: Int = 1,
        payloadLen: Int = 0,
        rawHex: String = ""
    ): CanBleFrame {
        return CanBleFrame(
            sourceNode = sourceNode,
            destNode = destNode,
            op = op,
            func = func,
            index = index,
            payload = ByteArray(payloadLen),
            payloadLen = payloadLen,
            rawHex = rawHex
        )
    }

    @Test
    fun `initial snapshot has zero values and null handshake`() {
        val collector = CanBleMetricsCollector()
        val m = collector.snapshot()

        assertEquals(0, m.totalNotifications)
        assertEquals(0, m.totalFrames)
        assertEquals(0, m.acceptedFrames)
        assertEquals(0, m.filteredFrames)
        assertEquals(0, m.decryptFailures)
        assertTrue(m.uniqueCanIndexes.isEmpty())
        assertTrue(m.uniqueSourceNodes.isEmpty())
        assertTrue(m.uniqueDestNodes.isEmpty())
        assertNull(m.handshakeDurationMs)
    }

    @Test
    fun `single notification increments counter`() {
        val collector = CanBleMetricsCollector()
        collector.onNotification()

        val m = collector.snapshot()
        assertEquals(1, m.totalNotifications)
        assertEquals(0, m.totalFrames)
    }

    @Test
    fun `accepted and filtered frames update counts`() {
        val collector = CanBleMetricsCollector()
        val f1 = frame(func = 0x60, index = 0x01)
        val f2 = frame(sourceNode = 19, func = 0x61, index = 0x02)

        collector.onFrameAccepted(f1)
        collector.onFrameFiltered(f2, "self_echo")

        val m = collector.snapshot()
        assertEquals(1, m.acceptedFrames)
        assertEquals(1, m.filteredFrames)
        assertEquals(2, m.totalFrames)
    }

    @Test
    fun `unique sets collect distinct values across frames`() {
        val collector = CanBleMetricsCollector()
        val f1 = frame(sourceNode = 2, destNode = 19, func = 0x60, index = 0x01)
        val f2 = frame(sourceNode = 3, destNode = 31, func = 0x61, index = 0x02)

        collector.onFrameAccepted(f1)
        collector.onFrameAccepted(f2)

        val m = collector.snapshot()
        assertEquals(2, m.uniqueSourceNodes.size)
        assertEquals(2, m.uniqueDestNodes.size)
        assertEquals(2, m.uniqueCanIndexes.size)
        assertTrue(m.uniqueCanIndexes.contains("60:1"))
        assertTrue(m.uniqueCanIndexes.contains("61:2"))
    }

    @Test
    fun `decrypt failures increment counter`() {
        val collector = CanBleMetricsCollector()
        collector.onDecryptFailure()
        collector.onDecryptFailure()
        collector.onDecryptFailure()

        val m = collector.snapshot()
        assertEquals(3, m.decryptFailures)
    }

    @Test
    fun `handshake completed sets duration`() {
        val collector = CanBleMetricsCollector()
        collector.handshakeCompleted(1500)

        val m = collector.snapshot()
        assertEquals(1500L, m.handshakeDurationMs?.toLong())
    }

    @Test
    fun `reset clears all state`() {
        val collector = CanBleMetricsCollector()
        collector.onNotification()
        collector.onFrameAccepted(frame())
        collector.onDecryptFailure()
        collector.handshakeCompleted(500)

        collector.reset()
        val m = collector.snapshot()
        assertEquals(0, m.totalNotifications)
        assertEquals(0, m.totalFrames)
        assertEquals(0, m.acceptedFrames)
        assertEquals(0, m.filteredFrames)
        assertEquals(0, m.decryptFailures)
        assertTrue(m.uniqueCanIndexes.isEmpty())
        assertNull(m.handshakeDurationMs)
    }
}
