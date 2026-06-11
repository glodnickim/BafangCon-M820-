package com.test.bafangcon

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aa55RawLoggerTest {

    private lateinit var logger: Aa55RawLogger

    @Before
    fun setUp() {
        logger = Aa55RawLogger()
    }

    @Test
    fun `initial snapshot has all zeros`() {
        val stats = logger.snapshot()
        assertEquals(0, stats.rxFrames)
        assertEquals(0, stats.txFrames)
        assertEquals(0, stats.unknownFrames)
        assertEquals("", stats.lastRxCommand)
        assertEquals("", stats.lastTxCommand)
        assertEquals("", stats.lastFrameHex)
        assertFalse(stats.isLogging)
        assertEquals("", stats.currentLogFilePath)
        assertEquals(0, stats.fileWriteErrors)
    }

    @Test
    fun `logTx increments txFrames and sets lastTxCommand`() {
        val frame = byteArrayOf(0x55, 0xAA.toByte(), 0x01, 0x11, 0xA3.toByte(), 0x01, 0x00, 0x00, 0x00)
        logger.logTx(frame)
        val stats = logger.snapshot()
        assertEquals(1, stats.txFrames)
        assertEquals(0, stats.rxFrames)
        assertEquals("CONTROLLER", stats.lastTxCommand)
        assertEquals("", stats.lastRxCommand)
    }

    @Test
    fun `logRx increments rxFrames and sets lastRxCommand`() {
        val frame = byteArrayOf(0x55, 0xAA.toByte(), 0x18, 0xA5.toByte(), 0x11, 0x04, 0x00, 0x00, 0x00)
        logger.logRx(frame)
        val stats = logger.snapshot()
        assertEquals(1, stats.rxFrames)
        assertEquals(0, stats.txFrames)
        assertEquals("METER", stats.lastRxCommand)
        assertEquals("", stats.lastTxCommand)
    }

    @Test
    fun `unknown command increments unknownFrames`() {
        val frame = byteArrayOf(0x55, 0xAA.toByte(), 0x01, 0xAB.toByte(), 0x11, 0x04, 0x00, 0x00, 0x00)
        logger.logRx(frame)
        val stats = logger.snapshot()
        assertEquals(1, stats.unknownFrames)
        assertEquals(1, stats.rxFrames)
        assertEquals("0xAB", stats.lastRxCommand)
    }

    @Test
    fun `clear resets all stats`() {
        val txFrame = byteArrayOf(0x55, 0xAA.toByte(), 0x01, 0x11, 0xA3.toByte(), 0x01, 0x00, 0x00, 0x00)
        val rxFrame = byteArrayOf(0x55, 0xAA.toByte(), 0x18, 0xA5.toByte(), 0x11, 0x04, 0x00, 0x00, 0x00)
        logger.logTx(txFrame)
        logger.logRx(rxFrame)
        assertTrue(logger.snapshot().rxFrames > 0)
        logger.clear()
        val stats = logger.snapshot()
        assertEquals(0, stats.rxFrames)
        assertEquals(0, stats.txFrames)
        assertEquals(0, stats.unknownFrames)
    }

    @Test
    fun `multiple frames accumulate correctly`() {
        val tx1 = byteArrayOf(0x55, 0xAA.toByte(), 0x01, 0x11, 0xA3.toByte(), 0x01, 0x00, 0x00, 0x00)
        val tx2 = byteArrayOf(0x55, 0xAA.toByte(), 0x01, 0x11, 0xA5.toByte(), 0x01, 0x00, 0x00, 0x00)
        val rx1 = byteArrayOf(0x55, 0xAA.toByte(), 0x18, 0xA5.toByte(), 0x11, 0x04, 0x00, 0x00, 0x00)
        val rx2 = byteArrayOf(0x55, 0xAA.toByte(), 0x30, 0xA3.toByte(), 0x11, 0x04, 0x00, 0x00, 0x00)
        logger.logTx(tx1)
        logger.logRx(rx1)
        logger.logTx(tx2)
        logger.logRx(rx2)
        val stats = logger.snapshot()
        assertEquals(2, stats.txFrames)
        assertEquals(2, stats.rxFrames)
        assertEquals(0, stats.unknownFrames)
    }

    @Test
    fun `unknown tx command increments unknownFrames`() {
        val frame = byteArrayOf(0x55, 0xAA.toByte(), 0x01, 0x11, 0xCC.toByte(), 0x01, 0x00, 0x00, 0x00)
        logger.logTx(frame)
        val stats = logger.snapshot()
        assertEquals(1, stats.unknownFrames)
        assertEquals(1, stats.txFrames)
        assertEquals("0xCC", stats.lastTxCommand)
    }

    @Test
    fun `short data returns N_A`() {
        val frame = byteArrayOf(0x55, 0xAA.toByte())
        logger.logRx(frame)
        val stats = logger.snapshot()
        assertEquals(1, stats.unknownFrames)
        assertEquals("N/A", stats.lastRxCommand)
    }

    @Test
    fun `lastFrameHex is set on tx and rx`() {
        val frame = byteArrayOf(0x55, 0xAA.toByte(), 0x01, 0x11, 0xA3.toByte(), 0x01, 0x00, 0x00, 0x00)
        logger.logTx(frame)
        val stats = logger.snapshot()
        assertTrue(stats.lastFrameHex.isNotEmpty())
        logger.logRx(frame)
        val stats2 = logger.snapshot()
        assertTrue(stats2.lastFrameHex.isNotEmpty())
    }
}
