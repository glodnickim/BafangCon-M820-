package com.test.bafangcon.canble.handshake

import com.test.bafangcon.canble.crypto.CryptoProvider
import com.test.bafangcon.canble.model.CanBleSessionState
import com.test.bafangcon.canble.model.HandshakeState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class CanBleHandshakeTest {

    private class FakeTimerHandle : TimerHandle {
        var active = true
        override val isActive: Boolean get() = active
        override fun cancel(): Boolean {
            if (!active) return false
            active = false
            return true
        }
    }

    private data class ScheduledCallback(
        val scheduledAtMs: Long,
        val callback: () -> Unit,
        val handle: FakeTimerHandle
    )

    private class FakeHandshakeTimer : HandshakeTimer {
        var currentTimeMs: Long = 0L
        private val scheduled = mutableListOf<ScheduledCallback>()

        override fun schedule(delayMs: Long, callback: () -> Unit): TimerHandle {
            val handle = FakeTimerHandle()
            scheduled.add(ScheduledCallback(currentTimeMs + delayMs, callback, handle))
            return handle
        }

        override fun cancel(handle: TimerHandle) {
            (handle as? FakeTimerHandle)?.active = false
            scheduled.removeAll { it.handle == handle }
        }

        override fun currentTimeMs(): Long = currentTimeMs

        fun advanceTimeBy(delayMs: Long) {
            currentTimeMs += delayMs
            val expired = mutableListOf<ScheduledCallback>()
            val iterator = scheduled.iterator()
            while (iterator.hasNext()) {
                val sc = iterator.next()
                if (sc.scheduledAtMs <= currentTimeMs && sc.handle.active) {
                    expired.add(sc)
                    iterator.remove()
                }
            }
            expired.forEach { it.callback() }
        }

        fun cancelAll() {
            scheduled.forEach { it.handle.active = false }
            scheduled.clear()
        }
    }

    private class FakeCryptoProvider : CryptoProvider {
        var supportsCanCryptoResult = true
        var encryptReturnsNull = false
        private val decryptResults = mutableListOf<ByteArray?>()
        val setDynamicKeyCalls = mutableListOf<ByteArray>()
        var lastEncryptData: ByteArray? = null
        var lastEncryptContext: Int = -1

        fun queueDecryptResult(result: ByteArray?) {
            decryptResults.add(result)
        }

        override fun supportsCanCrypto(): Boolean = supportsCanCryptoResult

        override fun encrypt(data: ByteArray, context: Int): ByteArray? {
            lastEncryptData = data
            lastEncryptContext = context
            if (encryptReturnsNull) return null
            return data
        }

        override fun decrypt(data: ByteArray, context: Int): ByteArray? {
            if (decryptResults.isNotEmpty()) {
                return decryptResults.removeAt(0)
            }
            return data
        }

        override fun setDynamicKey(key: ByteArray) {
            setDynamicKeyCalls.add(key.copyOf())
        }
    }

    private lateinit var crypto: FakeCryptoProvider
    private lateinit var timer: FakeHandshakeTimer
    private lateinit var handshake: CanBleHandshake
    private val writtenData = mutableListOf<Pair<UUID, ByteArray>>()
    private var completeState: CanBleSessionState? = null
    private var failureResult: HandshakeFailure? = null
    private var failureAttempt: Int = 0
    private var failureMaxRetries: Int = 0

    @Before
    fun setUp() {
        crypto = FakeCryptoProvider()
        timer = FakeHandshakeTimer()
        handshake = CanBleHandshake(crypto, timer)
        writtenData.clear()
        completeState = null
        failureResult = null

        handshake.onWriteRequired = { uuid, data ->
            writtenData.add(uuid to data.copyOf())
        }
        handshake.onHandshakeComplete = { state ->
            completeState = state
        }
        handshake.onHandshakeFailed = { failure, attempt, maxRetries ->
            failureResult = failure
            failureAttempt = attempt
            failureMaxRetries = maxRetries
        }
    }

    private fun deviceResponse(
        cmd: Byte = 0x02,
        deviceRand: ByteArray = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D),
        extra: ByteArray = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)
    ): ByteArray {
        val result = ByteArray(1 + deviceRand.size + extra.size)
        result[0] = cmd
        deviceRand.copyInto(result, 1)
        extra.copyInto(result, 1 + deviceRand.size)
        return result.copyOf(16)
    }

    private fun mtuResponse(cmd: Byte = 0x09): ByteArray {
        val result = ByteArray(16)
        result[0] = cmd
        return result
    }

    private fun startHandshake() {
        handshake.start()
    }

    private fun injectDeviceResponse(
        cmd: Byte = 0x02,
        deviceRand: ByteArray = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D),
        extra: ByteArray = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)
    ) {
        crypto.queueDecryptResult(deviceResponse(cmd, deviceRand, extra))
        handshake.onControlNotificationReceived(deviceResponse(cmd, deviceRand, extra))
    }

    private fun injectMtuResponse(cmd: Byte = 0x09) {
        crypto.queueDecryptResult(mtuResponse(cmd))
        handshake.onControlNotificationReceived(mtuResponse(cmd))
    }

    private fun completeHappyPath() {
        startHandshake()
        injectDeviceResponse()
        injectMtuResponse()
    }

    @Test
    fun `start sets RAND1_SENT and writes RAND1 to CAN_CONTROL`() {
        startHandshake()

        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)
        assertEquals(1, writtenData.size)
        val (uuid, data) = writtenData[0]
        assertEquals("49d55e56-76b1-11e9-8f9e-2a86e4085a59", uuid.toString())
        assertEquals(0x01.toByte(), data[0])
        assertEquals(5, data.size)
    }

    @Test
    fun `full happy path completes handshake`() {
        completeHappyPath()

        assertEquals(HandshakeState.READY, handshake.currentState)
        assertNotNull(completeState)
        assertEquals(0, completeState?.aU)
        assertEquals(2, writtenData.size)
        assertEquals(0x01.toByte(), writtenData[0].second[0])
        assertEquals(0x08.toByte(), writtenData[1].second[0])
    }

    @Test
    fun `device response with correct 0x02 sets dynamic key`() {
        val deviceRand = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val extra = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)

        startHandshake()
        injectDeviceResponse(cmd = 0x02, deviceRand = deviceRand, extra = extra)

        assertEquals(1, crypto.setDynamicKeyCalls.size)
        val key = crypto.setDynamicKeyCalls[0]
        assertEquals(16, key.size)
        assertEquals(deviceRand[0], key[1])
        assertEquals(deviceRand[1], key[4])
        assertEquals(deviceRand[2], key[7])
        assertEquals(deviceRand[3], key[10])
        assertEquals(extra[0], key[2])
        assertEquals(extra[1], key[5])
        assertEquals(extra[2], key[8])
        assertEquals(extra[3], key[11])
        assertEquals(0x55.toByte(), key[12])
        assertEquals(0xAA.toByte(), key[13])
        assertEquals(extra[4], key[14])
        assertEquals(extra[5], key[15])
    }

    @Test
    fun `after device response handshake sends GET_MTU`() {
        startHandshake()
        writtenData.clear()

        injectDeviceResponse()

        assertEquals(1, writtenData.size)
        val (_, data) = writtenData[0]
        assertEquals(0x08.toByte(), data[0])
        assertEquals(HandshakeState.MTU_REQUEST_SENT, handshake.currentState)
    }

    @Test
    fun `MTU response transitions to READY with aU zero`() {
        startHandshake()
        injectDeviceResponse()
        injectMtuResponse()

        assertEquals(HandshakeState.READY, handshake.currentState)
        assertEquals(0, handshake.sessionState.aU)
        assertNotNull(completeState)
    }

    @Test
    fun `handshake complete callback fires on ready`() {
        startHandshake()
        injectDeviceResponse()
        injectMtuResponse()

        assertNotNull(completeState)
        assertEquals(0, completeState?.aU)
        assertEquals(HandshakeState.READY, completeState?.handshakeState)
    }

    @Test
    fun `RAND1_SENT timeout triggers failure`() {
        startHandshake()
        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)

        timer.advanceTimeBy(5000)

        assertTrue(failureResult is HandshakeFailure.Timeout)
        assertEquals(HandshakeState.FAILED, handshake.currentState)
    }

    @Test
    fun `MTU_REQUEST_SENT timeout triggers failure`() {
        startHandshake()
        injectDeviceResponse()
        assertEquals(HandshakeState.MTU_REQUEST_SENT, handshake.currentState)

        timer.advanceTimeBy(5000)

        assertTrue(failureResult is HandshakeFailure.Timeout)
        assertEquals(HandshakeState.FAILED, handshake.currentState)
    }

    @Test
    fun `no timeout after valid response`() {
        startHandshake()
        injectDeviceResponse()
        injectMtuResponse()

        timer.advanceTimeBy(10000)

        assertEquals(HandshakeState.READY, handshake.currentState)
        assertNull(failureResult)
    }

    @Test
    fun `wrong command byte in device response fails`() {
        startHandshake()
        injectDeviceResponse(cmd = 0x08)

        assertTrue(failureResult is HandshakeFailure.InvalidResponse)
        assertEquals(HandshakeState.FAILED, handshake.currentState)
    }

    @Test
    fun `short device response fails`() {
        startHandshake()
        val shortData = ByteArray(16)
        shortData[0] = 0x02
        crypto.queueDecryptResult(shortData.copyOf(5))
        handshake.onControlNotificationReceived(shortData)

        assertTrue(failureResult is HandshakeFailure.InvalidResponse)
        assertEquals(HandshakeState.FAILED, handshake.currentState)
    }

    @Test
    fun `non sixteen byte notification fails`() {
        startHandshake()
        handshake.onControlNotificationReceived(ByteArray(15))

        assertTrue(failureResult is HandshakeFailure.InvalidResponse)
        assertEquals(HandshakeState.FAILED, handshake.currentState)
    }

    @Test
    fun `wrong command byte in MTU response fails`() {
        startHandshake()
        injectDeviceResponse()
        injectMtuResponse(cmd = 0x02)

        assertTrue(failureResult is HandshakeFailure.InvalidResponse)
        assertEquals(HandshakeState.FAILED, handshake.currentState)
    }

    @Test
    fun `decrypt failure retries once then succeeds`() {
        startHandshake()

        crypto.queueDecryptResult(null)
        handshake.onControlNotificationReceived(ByteArray(16))

        assertTrue(failureResult is HandshakeFailure.DecryptFailure)
        assertEquals(HandshakeState.FAILED, handshake.currentState)

        failureResult = null
        handshake.resetAndRetry()
        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)

        injectDeviceResponse()
        injectMtuResponse()

        assertEquals(HandshakeState.READY, handshake.currentState)
        assertNotNull(completeState)
    }

    @Test
    fun `decrypt failure twice is terminal`() {
        startHandshake()

        crypto.queueDecryptResult(null)
        handshake.onControlNotificationReceived(ByteArray(16))
        assertTrue(failureResult is HandshakeFailure.DecryptFailure)
        assertEquals(HandshakeState.FAILED, handshake.currentState)

        handshake.resetAndRetry()
        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)

        failureResult = null
        crypto.queueDecryptResult(null)
        handshake.onControlNotificationReceived(ByteArray(16))

        assertTrue(failureResult is HandshakeFailure.DecryptFailure)
        assertEquals(HandshakeState.FAILED, handshake.currentState)
    }

    @Test
    fun `decrypt failure on MTU response retries once`() {
        startHandshake()
        injectDeviceResponse()
        assertEquals(HandshakeState.MTU_REQUEST_SENT, handshake.currentState)

        crypto.queueDecryptResult(null)
        handshake.onControlNotificationReceived(ByteArray(16))
        assertTrue(failureResult is HandshakeFailure.DecryptFailure)
        assertEquals(HandshakeState.FAILED, handshake.currentState)

        failureResult = null
        handshake.resetAndRetry()
        injectDeviceResponse()
        injectMtuResponse()

        assertEquals(HandshakeState.READY, handshake.currentState)
        assertNotNull(completeState)
    }

    @Test
    fun `timeout triggers retry and second attempt succeeds`() {
        startHandshake()
        timer.advanceTimeBy(5000)
        assertTrue(failureResult is HandshakeFailure.Timeout)
        assertEquals(HandshakeState.FAILED, handshake.currentState)

        failureResult = null
        handshake.resetAndRetry()
        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)
        assertEquals(2, writtenData.size)

        injectDeviceResponse()
        injectMtuResponse()

        assertEquals(HandshakeState.READY, handshake.currentState)
    }

    @Test
    fun `max retries exhausted stays in FAILED`() {
        startHandshake()

        timer.advanceTimeBy(5000)
        assertTrue(failureResult is HandshakeFailure.Timeout)
        assertEquals(1, failureAttempt)
        assertEquals(HandshakeState.FAILED, handshake.currentState)

        handshake.resetAndRetry()
        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)
        failureResult = null

        timer.advanceTimeBy(5000)
        assertTrue(failureResult is HandshakeFailure.Timeout)
        assertEquals(2, failureAttempt)
        assertEquals(HandshakeState.FAILED, handshake.currentState)

        handshake.resetAndRetry()
        assertEquals(HandshakeState.FAILED, handshake.currentState)
    }

    @Test
    fun `crypto unavailable on start fails immediately`() {
        crypto.supportsCanCryptoResult = false
        startHandshake()

        assertTrue(failureResult is HandshakeFailure.CryptoUnavailable)
        assertEquals(HandshakeState.FAILED, handshake.currentState)
    }

    @Test
    fun `key derivation with known inputs`() {
        val deviceRand = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val extra = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)

        startHandshake()
        injectDeviceResponse(deviceRand = deviceRand, extra = extra)

        assertEquals(1, crypto.setDynamicKeyCalls.size)
        val key = crypto.setDynamicKeyCalls[0]

        assertEquals(deviceRand[0], key[1])
        assertEquals(deviceRand[1], key[4])
        assertEquals(deviceRand[2], key[7])
        assertEquals(deviceRand[3], key[10])
        assertEquals(extra[0], key[2])
        assertEquals(extra[1], key[5])
        assertEquals(extra[2], key[8])
        assertEquals(extra[3], key[11])
        assertEquals(0x55.toByte(), key[12])
        assertEquals(0xAA.toByte(), key[13])
        assertEquals(extra[4], key[14])
        assertEquals(extra[5], key[15])
    }

    @Test
    fun `different appRand on each start`() {
        startHandshake()
        val firstData = writtenData[0].second.copyOf()

        handshake.reset()
        writtenData.clear()

        startHandshake()
        val secondData = writtenData[0].second.copyOf()

        val firstRand = firstData.copyOfRange(1, 5)
        val secondRand = secondData.copyOfRange(1, 5)

        assertFalse(firstRand.contentEquals(secondRand))
    }

    @Test
    fun `appRand payload has 4 random bytes`() {
        startHandshake()
        val data = writtenData[0].second
        assertEquals(5, data.size)
        assertEquals(0x01.toByte(), data[0])
        assertEquals(4, data.size - 1)
    }

    @Test
    fun `reset during handshake returns to IDLE`() {
        startHandshake()
        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)

        handshake.reset()

        assertEquals(HandshakeState.IDLE, handshake.currentState)
        assertEquals(-1, handshake.sessionState.aU)
    }

    @Test
    fun `destroy clears callbacks and state`() {
        startHandshake()
        assertNotNull(handshake.onWriteRequired)
        assertNotNull(handshake.onHandshakeComplete)
        assertNotNull(handshake.onHandshakeFailed)

        handshake.destroy()

        assertEquals(HandshakeState.IDLE, handshake.currentState)
        assertNull(handshake.onWriteRequired)
        assertNull(handshake.onHandshakeComplete)
        assertNull(handshake.onHandshakeFailed)
    }

    @Test
    fun `notification ignored when handshake is idle`() {
        handshake.onControlNotificationReceived(ByteArray(16))

        assertEquals(HandshakeState.IDLE, handshake.currentState)
        assertNull(failureResult)
    }

    @Test
    fun `notification ignored when handshake is ready`() {
        completeHappyPath()
        assertEquals(HandshakeState.READY, handshake.currentState)

        failureResult = null
        handshake.onControlNotificationReceived(ByteArray(16))

        assertEquals(HandshakeState.READY, handshake.currentState)
        assertNull(failureResult)
    }

    @Test
    fun `resetAndRetry no-op when not in FAILED`() {
        startHandshake()
        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)

        handshake.resetAndRetry()

        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)
    }

    @Test
    fun `resetAndRetry transitions from FAILED to RAND1_SENT`() {
        startHandshake()
        crypto.queueDecryptResult(null)
        handshake.onControlNotificationReceived(ByteArray(16))
        assertEquals(HandshakeState.FAILED, handshake.currentState)

        handshake.resetAndRetry()

        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)
    }

    @Test
    fun `state machine progresses through all expected states`() {
        startHandshake()
        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)

        val deviceRand = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val extra = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)

        injectDeviceResponse(deviceRand = deviceRand, extra = extra)
        assertEquals(HandshakeState.MTU_REQUEST_SENT, handshake.currentState)
        assertEquals(1, crypto.setDynamicKeyCalls.size)

        injectMtuResponse()
        assertEquals(HandshakeState.READY, handshake.currentState)
        assertEquals(0, handshake.sessionState.aU)
    }

    @Test
    fun `empty payload notification fails with invalid response`() {
        startHandshake()
        handshake.onControlNotificationReceived(ByteArray(0))

        assertTrue(failureResult is HandshakeFailure.InvalidResponse)
        assertEquals(HandshakeState.FAILED, handshake.currentState)
    }

    @Test
    fun `handshake failure reports attempt and maxRetries`() {
        startHandshake()

        timer.advanceTimeBy(5000)

        assertEquals(1, failureAttempt)
        assertEquals(2, failureMaxRetries)
    }
}
