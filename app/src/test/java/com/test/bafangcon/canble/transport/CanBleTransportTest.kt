package com.test.bafangcon.canble.transport

import com.test.bafangcon.canble.crypto.CryptoProvider
import com.test.bafangcon.canble.handshake.CanBleHandshake
import com.test.bafangcon.canble.handshake.HandshakeTimer
import com.test.bafangcon.canble.handshake.TimerHandle
import com.test.bafangcon.canble.model.CanBleSessionState
import com.test.bafangcon.canble.model.HandshakeState
import com.test.bafangcon.canble.process.CanBleFrameExtractor
import com.test.bafangcon.canble.process.CanBleFrameFilter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class CanBleTransportTest {

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
    }

    private class FakeCryptoProvider : CryptoProvider {
        var supportsCanCryptoResult = true
        private val decryptResults = mutableListOf<ByteArray?>()

        fun queueDecryptResult(result: ByteArray?) {
            decryptResults.add(result)
        }

        override fun supportsCanCrypto(): Boolean = supportsCanCryptoResult
        override fun encrypt(data: ByteArray, context: Int): ByteArray? = data
        override fun decrypt(data: ByteArray, context: Int): ByteArray? =
            if (decryptResults.isNotEmpty()) decryptResults.removeAt(0) else data
        override fun setDynamicKey(key: ByteArray) { }
    }

    private lateinit var crypto: FakeCryptoProvider
    private lateinit var timer: FakeHandshakeTimer
    private lateinit var handshake: CanBleHandshake
    private lateinit var transport: CanBleTransport
    private val writtenData = mutableListOf<Pair<UUID, ByteArray>>()
    private var readyState: CanBleSessionState? = null

    private val uuidCanControl = UUID.fromString("49d55e56-76b1-11e9-8f9e-2a86e4085a59")
    private val uuidCanRx = UUID.fromString("49d5571d-76b1-11e9-8f9e-2a86e4085a59")

    private fun completeHandshake() {
        transport.onServiceFound()
        transport.onNotificationsSubscribed()
        writtenData.clear()

        val deviceResponse = ByteArray(16)
        deviceResponse[0] = 0x02
        crypto.queueDecryptResult(deviceResponse)
        transport.onControlNotificationReceived(deviceResponse)
        writtenData.clear()

        val mtuResponse = ByteArray(16)
        mtuResponse[0] = 0x09
        crypto.queueDecryptResult(mtuResponse)
        transport.onControlNotificationReceived(mtuResponse)
    }

    @Before
    fun setUp() {
        crypto = FakeCryptoProvider()
        timer = FakeHandshakeTimer()
        handshake = CanBleHandshake(crypto, timer)
        transport = CanBleTransport(
            handshake = handshake,
            extractor = CanBleFrameExtractor,
            filter = CanBleFrameFilter
        )
        writtenData.clear()
        readyState = null

        transport.onWriteRequired = { uuid, data ->
            writtenData.add(uuid to data.copyOf())
        }
        transport.onHandshakeReady = { state ->
            readyState = state
        }
    }

    @Test
    fun `initial state is IDLE`() {
        assertEquals(CanBleTransportState.IDLE, transport.state)
    }

    @Test
    fun `onServiceFound transitions to SERVICE_FOUND`() {
        transport.onServiceFound()
        assertEquals(CanBleTransportState.SERVICE_FOUND, transport.state)
    }

    @Test
    fun `onServiceFound is idempotent`() {
        transport.onServiceFound()
        transport.onServiceFound()
        assertEquals(CanBleTransportState.SERVICE_FOUND, transport.state)
    }

    @Test
    fun `onNotificationsSubscribed from SERVICE_FOUND transitions to HANDSHAKE_IN_PROGRESS`() {
        transport.onServiceFound()
        transport.onNotificationsSubscribed()
        assertEquals(CanBleTransportState.HANDSHAKE_IN_PROGRESS, transport.state)
    }

    @Test
    fun `onNotificationsSubscribed from IDLE is ignored`() {
        transport.onNotificationsSubscribed()
        assertEquals(CanBleTransportState.IDLE, transport.state)
    }

    @Test
    fun `onNotificationsSubscribed triggers handshake RAND1 write`() {
        transport.onServiceFound()
        transport.onNotificationsSubscribed()

        assertFalse("handshake must send RAND1", writtenData.isEmpty())
        val (uuid, data) = writtenData[0]
        assertEquals(uuidCanControl, uuid)
        assertEquals(0x01.toByte(), data[0])
    }

    @Test
    fun `full happy path reaches READY`() {
        completeHandshake()

        assertEquals(CanBleTransportState.READY, transport.state)
        assertNotNull(readyState)
        assertEquals(HandshakeState.READY, readyState?.handshakeState)
    }

    @Test
    fun `control notification during HANDSHAKE_IN_PROGRESS advances handshake state`() {
        transport.onServiceFound()
        transport.onNotificationsSubscribed()

        val deviceResponse = ByteArray(16)
        deviceResponse[0] = 0x02
        crypto.queueDecryptResult(deviceResponse)
        transport.onControlNotificationReceived(deviceResponse)

        assertEquals(HandshakeState.MTU_REQUEST_SENT, handshake.currentState)
    }

    @Test
    fun `control notification during IDLE is ignored`() {
        transport.onControlNotificationReceived(ByteArray(16))
        assertEquals(HandshakeState.IDLE, handshake.currentState)
    }

    @Test
    fun `control notification during SERVICE_FOUND is ignored`() {
        transport.onServiceFound()
        transport.onControlNotificationReceived(ByteArray(16))
        assertEquals(HandshakeState.IDLE, handshake.currentState)
    }

    @Test
    fun `RX notification during READY extracts frames`() {
        completeHandshake()

        val rxData = byteArrayOf(
            0x02, ((0x13 shl 3) or 0x02).toByte(), 0x60, 0x01, 0x01, 0xAA.toByte()
        )
        transport.onRxNotificationReceived(rxData, uuidCanRx)

        assertTrue("handshake must stay READY after RX", handshake.currentState == HandshakeState.READY)
    }

    @Test
    fun `RX notification during IDLE is ignored`() {
        transport.onRxNotificationReceived(ByteArray(1), uuidCanRx)
        assertEquals(HandshakeState.IDLE, handshake.currentState)
    }

    @Test
    fun `RX notification during SERVICE_FOUND is ignored`() {
        transport.onServiceFound()
        transport.onRxNotificationReceived(ByteArray(1), uuidCanRx)
        assertEquals(HandshakeState.IDLE, handshake.currentState)
    }

    @Test
    fun `RX notification during HANDSHAKE_IN_PROGRESS is ignored`() {
        transport.onServiceFound()
        transport.onNotificationsSubscribed()
        transport.onRxNotificationReceived(ByteArray(1), uuidCanRx)
        assertEquals(HandshakeState.RAND1_SENT, handshake.currentState)
    }

    @Test
    fun `onDisconnected resets to IDLE`() {
        transport.onServiceFound()
        transport.onNotificationsSubscribed()
        assertEquals(CanBleTransportState.HANDSHAKE_IN_PROGRESS, transport.state)

        transport.onDisconnected()
        assertEquals(CanBleTransportState.IDLE, transport.state)
        assertEquals(HandshakeState.IDLE, handshake.currentState)
    }

    @Test
    fun `onDisconnected during READY resets to IDLE`() {
        completeHandshake()
        assertEquals(CanBleTransportState.READY, transport.state)

        transport.onDisconnected()
        assertEquals(CanBleTransportState.IDLE, transport.state)
    }

    @Test
    fun `onDisconnected during IDLE is safe`() {
        transport.onDisconnected()
        assertEquals(CanBleTransportState.IDLE, transport.state)
    }

    @Test
    fun `crypto unavailable transitions to FAILED`() {
        crypto.supportsCanCryptoResult = false
        transport.onServiceFound()
        transport.onNotificationsSubscribed()

        assertEquals(CanBleTransportState.FAILED, transport.state)
    }

    @Test
    fun `handshake timeout transitions to FAILED`() {
        transport.onServiceFound()
        transport.onNotificationsSubscribed()

        timer.advanceTimeBy(5000)

        assertEquals(CanBleTransportState.FAILED, transport.state)
    }

    @Test
    fun `handshake READY callback fires`() {
        completeHandshake()

        assertNotNull(readyState)
        assertEquals(HandshakeState.READY, readyState?.handshakeState)
    }

    @Test
    fun `reconnect after disconnect starts fresh`() {
        transport.onServiceFound()
        transport.onNotificationsSubscribed()
        transport.onDisconnected()
        assertEquals(CanBleTransportState.IDLE, transport.state)

        transport.onServiceFound()
        assertEquals(CanBleTransportState.SERVICE_FOUND, transport.state)

        transport.onNotificationsSubscribed()
        assertEquals(CanBleTransportState.HANDSHAKE_IN_PROGRESS, transport.state)
    }

    @Test
    fun `decrypt failure retry eventually reaches READY`() {
        transport.onServiceFound()
        transport.onNotificationsSubscribed()

        crypto.queueDecryptResult(null)
        transport.onControlNotificationReceived(ByteArray(16))
        assertEquals(CanBleTransportState.FAILED, transport.state)

        handshake.resetAndRetry()
        writtenData.clear()

        crypto.queueDecryptResult(ByteArray(16).apply { this[0] = 0x02 })
        transport.onControlNotificationReceived(ByteArray(16))

        crypto.queueDecryptResult(ByteArray(16).apply { this[0] = 0x09 })
        transport.onControlNotificationReceived(ByteArray(16))

        assertEquals(CanBleTransportState.READY, transport.state)
    }

    @Test
    fun `destroy clears callbacks and state`() {
        assertNotNull(transport.onWriteRequired)
        assertNotNull(transport.onHandshakeReady)

        transport.destroy()

        assertNull(transport.onWriteRequired)
        assertNull(transport.onHandshakeReady)
        assertEquals(HandshakeState.IDLE, handshake.currentState)
    }

    @Test
    fun `full lifecycle IDLE to READY to IDLE cycle`() {
        assertEquals(CanBleTransportState.IDLE, transport.state)

        transport.onServiceFound()
        assertEquals(CanBleTransportState.SERVICE_FOUND, transport.state)

        transport.onNotificationsSubscribed()
        assertEquals(CanBleTransportState.HANDSHAKE_IN_PROGRESS, transport.state)

        val deviceResponse = ByteArray(16).apply { this[0] = 0x02 }
        crypto.queueDecryptResult(deviceResponse)
        transport.onControlNotificationReceived(deviceResponse)

        val mtuResponse = ByteArray(16).apply { this[0] = 0x09 }
        crypto.queueDecryptResult(mtuResponse)
        transport.onControlNotificationReceived(mtuResponse)
        assertEquals(CanBleTransportState.READY, transport.state)

        val rxData = byteArrayOf(0x02, ((0x13 shl 3) or 0x02).toByte(), 0x60, 0x01, 0x00)
        transport.onRxNotificationReceived(rxData, uuidCanRx)
        assertEquals(CanBleTransportState.READY, transport.state)

        transport.onDisconnected()
        assertEquals(CanBleTransportState.IDLE, transport.state)
    }

    @Test
    fun `idempotent onDisconnected is safe`() {
        transport.onDisconnected()
        transport.onDisconnected()
        transport.onDisconnected()
        assertEquals(CanBleTransportState.IDLE, transport.state)
    }

    @Test
    fun `multiple RX notifications during READY keep READY state`() {
        completeHandshake()

        val rxData = byteArrayOf(0x02, ((0x13 shl 3) or 0x02).toByte(), 0x60, 0x01, 0x00)
        transport.onRxNotificationReceived(rxData, uuidCanRx)
        transport.onRxNotificationReceived(rxData, uuidCanRx)
        transport.onRxNotificationReceived(rxData, uuidCanRx)

        assertEquals(CanBleTransportState.READY, transport.state)
    }
}
