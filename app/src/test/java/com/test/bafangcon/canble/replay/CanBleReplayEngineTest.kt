package com.test.bafangcon.canble.replay

import com.test.bafangcon.canble.model.CanBleFrame
import com.test.bafangcon.canble.model.RecordedCanNotification
import com.test.bafangcon.canble.model.RecordedCanSession
import org.junit.Assert.*
import org.junit.Test

class CanBleReplayEngineTest {

    private val engine = CanBleReplayEngine()

    /** Build a 5-byte CAN frame hex: src, (dest<<3)|op, func, index, payloadLen */
    private fun frameHex(
        sourceNode: Int, destNode: Int, op: Int, func: Int, index: Int, payloadLen: Int
    ): String {
        val destOp = (destNode shl 3) or op
        return "%02x%02x%02x%02x%02x".format(sourceNode, destOp, func, index, payloadLen)
    }

    private fun notification(
        encryptedHex: String = "00",
        decryptedHex: String? = frameHex(2, 19, 2, 0x60, 1, 8) + "0102030405060708",
        aUMode: Int = 2,
        timestampMs: Long = System.currentTimeMillis(),
        sourceUuid: String = "49d5571d-76b1-11e9-8f9e-2a86e4085a59"
    ): RecordedCanNotification {
        return RecordedCanNotification(
            timestampMs = timestampMs,
            encryptedHex = encryptedHex,
            decryptedHex = decryptedHex,
            sourceUuid = sourceUuid,
            aUMode = aUMode
        )
    }

    @Test
    fun `empty session returns zero counts`() {
        val session = RecordedCanSession("test-empty")
        val result = engine.replay(session)

        assertEquals(0, result.summary.totalNotifications)
        assertEquals(0, result.summary.processedNotifications)
        assertEquals(0, result.summary.skippedNotifications)
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun `single notification with decrypted data processes frames`() {
        // decrypted: src=2, dest=19|op=2, func=0x60, idx=1, len=8, payload=01..08
        val notif = notification(
            decryptedHex = frameHex(2, 19, 2, 0x60, 1, 8) + "0102030405060708"
        )
        val session = RecordedCanSession("test-single")
        session.startRecording()
        session.recordDecrypted(
            encryptedHex = notif.encryptedHex,
            decryptedHex = notif.decryptedHex!!,
            uuid = java.util.UUID.randomUUID(),
            aUMode = notif.aUMode
        )
        session.stopRecording()

        val result = engine.replay(session)

        assertEquals(1, result.summary.processedNotifications)
        assertEquals(0, result.summary.skippedNotifications)
        assertEquals(1, result.summary.totalFrames)

        val step = result.steps[0] as StepResult.Processed
        assertEquals(1, step.frames.size)
        assertEquals(2, step.frames[0].sourceNode)
        assertEquals(19, step.frames[0].destNode)
        assertEquals(0x60, step.frames[0].func)
        assertEquals(1, step.frames[0].index)
        assertEquals(8, step.frames[0].payloadLen)
    }

    @Test
    fun `notification without decrypted data is skipped`() {
        val notif = notification(decryptedHex = null)
        val result = engine.step(notif)

        assertTrue(result is StepResult.Skipped)
        assertEquals("no decrypted data", (result as StepResult.Skipped).reason)
    }

    @Test
    fun `mixed notifications with and without decrypted data`() {
        val n1 = notification(decryptedHex = frameHex(2, 19, 2, 0x60, 1, 8) + "0102030405060708", aUMode = 2)
        val n2 = notification(decryptedHex = null, aUMode = 1)
        val n3 = notification(decryptedHex = frameHex(3, 19, 2, 0x60, 2, 2) + "01BB", aUMode = 2)

        assertEquals(true, engine.step(n1) is StepResult.Processed)
        assertEquals(true, engine.step(n2) is StepResult.Skipped)
        assertEquals(true, engine.step(n3) is StepResult.Processed)
    }

    @Test
    fun `replay summary contains timestamps and aUModes`() {
        val now = System.currentTimeMillis()
        val old = now - 5000

        val session = RecordedCanSession("test-ts")
        val n1 = notification(timestampMs = old, aUMode = 1, decryptedHex = frameHex(2, 19, 2, 0x60, 0, 0))
        val n2 = notification(timestampMs = now, aUMode = 2, decryptedHex = frameHex(3, 19, 2, 0x61, 0, 0))
        session.startRecording()
        session.recordDecrypted(n1.encryptedHex, n1.decryptedHex!!, java.util.UUID.randomUUID(), 1, timestampMs = old)
        session.recordDecrypted(n2.encryptedHex, n2.decryptedHex!!, java.util.UUID.randomUUID(), 2, timestampMs = now)
        session.stopRecording()

        val summary = engine.replayStepByStep(session)

        assertEquals(2, summary.processedNotifications)
        assertEquals(0, summary.skippedNotifications)
        assertTrue(summary.sessionDurationMs >= 5000)
        assertTrue(summary.aUModes.containsAll(setOf(1, 2)))
    }

    @Test
    fun `accepted and rejected frames are counted correctly`() {
        // Frame 1: src=2, dest=19 (normal, accepted)
        // Frame 2: src=19, dest=2 (self echo, rejected)
        val session = RecordedCanSession("test-filter")
        val notif = notification(
            decryptedHex = frameHex(2, 19, 2, 0x60, 1, 0) +
                frameHex(19, 2, 0, 0x61, 0, 5) + "0000000000" // valid + echo packed
        )
        session.startRecording()
        session.recordDecrypted(notif.encryptedHex, notif.decryptedHex!!, java.util.UUID.randomUUID(), 2)
        session.stopRecording()

        val result = engine.replay(session)

        assertEquals(1, result.summary.processedNotifications)
        assertEquals(2, result.summary.totalFrames)
        assertEquals(1, result.summary.acceptedFrames)
        assertEquals(1, result.summary.rejectedFrames)

        val step = result.steps[0] as StepResult.Processed
        assertEquals(1, step.accepted.size)
        assertEquals(1, step.rejected.size)
        assertEquals("self_echo", step.rejected[0].reason)
    }

    @Test
    fun `odd length hex returns skipped with invalid_hex reason`() {
        val notif = notification(decryptedHex = "021")
        val result = engine.step(notif)

        assertTrue(result is StepResult.Skipped)
        assertEquals("invalid_hex", (result as StepResult.Skipped).reason)
    }

    @Test
    fun `empty hex string returns skipped with invalid_hex reason`() {
        val notif = notification(decryptedHex = "")
        val result = engine.step(notif)

        assertTrue(result is StepResult.Skipped)
        assertEquals("invalid_hex", (result as StepResult.Skipped).reason)
    }

    @Test
    fun `hex with invalid characters returns skipped with invalid_hex reason`() {
        val notif = notification(decryptedHex = "0x02zz")
        val result = engine.step(notif)

        assertTrue(result is StepResult.Skipped)
        assertEquals("invalid_hex", (result as StepResult.Skipped).reason)
    }

    @Test
    fun `replayStepByStep returns summary without storing all steps`() {
        val session = RecordedCanSession("test-stream")
        session.startRecording()
        for (i in 0 until 10) {
            session.recordDecrypted("00", frameHex(2, 19, 2, 0x60, 0, 0), java.util.UUID.randomUUID(), 2)
        }
        session.stopRecording()

        var stepCount = 0
        val summary = engine.replayStepByStep(session) { stepCount++ }

        assertEquals(10, summary.totalNotifications)
        assertEquals(10, summary.processedNotifications)
        assertEquals(10, stepCount)
    }
}
