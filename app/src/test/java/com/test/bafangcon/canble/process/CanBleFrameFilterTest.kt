package com.test.bafangcon.canble.process

import com.test.bafangcon.canble.model.CanBleFrame
import org.junit.Assert.*
import org.junit.Test

class CanBleFrameFilterTest {

    private fun frame(
        sourceNode: Int = 2,
        destNode: Int = 19,
        op: Int = 0,
        func: Int = 0x60,
        index: Int = 1,
        payloadLen: Int = 0
    ): CanBleFrame {
        return CanBleFrame(
            sourceNode = sourceNode,
            destNode = destNode,
            op = op,
            func = func,
            index = index,
            payload = ByteArray(payloadLen),
            payloadLen = payloadLen,
            rawHex = ""
        )
    }

    @Test
    fun `sourceNode 19 is rejected as self_echo`() {
        val f = frame(sourceNode = 19)
        val result = CanBleFrameFilter.apply(f)
        assertTrue(result is FilterResult.Rejected)
        assertEquals("self_echo", (result as FilterResult.Rejected).reason)
    }

    @Test
    fun `sourceNode 5 is rejected as self_echo`() {
        val f = frame(sourceNode = 5)
        val result = CanBleFrameFilter.apply(f)
        assertTrue(result is FilterResult.Rejected)
        assertEquals("self_echo", (result as FilterResult.Rejected).reason)
    }

    @Test
    fun `long data template func 0x60 index 0 is rejected`() {
        val f = frame(func = 0x60, index = 0)
        val result = CanBleFrameFilter.apply(f)
        assertTrue(result is FilterResult.Rejected)
        assertEquals("long_data_template", (result as FilterResult.Rejected).reason)
    }

    @Test
    fun `long data op 4 with wrong dest is rejected`() {
        val f = frame(op = 4, destNode = 2)
        val result = CanBleFrameFilter.apply(f)
        assertTrue(result is FilterResult.Rejected)
        assertEquals("long_data_not_for_us", (result as FilterResult.Rejected).reason)
    }

    @Test
    fun `long data op 4 addressed to app is accepted`() {
        val f = frame(op = 4, destNode = 19)
        val result = CanBleFrameFilter.apply(f)
        assertTrue(result is FilterResult.Accepted)
    }

    @Test
    fun `wrong dest not broadcast not app not battery func is rejected`() {
        val f = frame(destNode = 2, func = 50)
        val result = CanBleFrameFilter.apply(f)
        assertTrue(result is FilterResult.Rejected)
        assertEquals("wrong_dest", (result as FilterResult.Rejected).reason)
    }

    @Test
    fun `broadcast dest is accepted`() {
        val f = frame(destNode = 31)
        val result = CanBleFrameFilter.apply(f)
        assertTrue("Broadcast should be accepted", result is FilterResult.Accepted)
    }

    @Test
    fun `app dest with normal frame is accepted`() {
        val f = frame(destNode = 19, func = 0x60, index = 1)
        val result = CanBleFrameFilter.apply(f)
        assertTrue(result is FilterResult.Accepted)
    }

    @Test
    fun `battery percent func 99 to non-app non-broadcast is accepted`() {
        val f = frame(destNode = 2, func = 99)
        val result = CanBleFrameFilter.apply(f)
        assertTrue(result is FilterResult.Accepted)
    }
}
