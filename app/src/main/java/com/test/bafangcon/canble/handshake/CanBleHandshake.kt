package com.test.bafangcon.canble.handshake

import com.test.bafangcon.canble.crypto.CryptoProvider
import com.test.bafangcon.canble.model.CanBleSessionState
import com.test.bafangcon.canble.model.HandshakeState
import java.security.SecureRandom
import java.util.UUID

class CanBleHandshake(
    private val crypto: CryptoProvider,
    private val timer: HandshakeTimer,
    private val retryConfig: HandshakeRetryConfig = HandshakeRetryConfig()
) {
    private val uuidCanControl = UUID.fromString("49d55e56-76b1-11e9-8f9e-2a86e4085a59")
    private var activeTimer: TimerHandle? = null
    private var appRand: ByteArray = ByteArray(4)
    private var attempt = 0
    private var decryptFailureRetried = false
    private val secureRandom = SecureRandom()

    var sessionState: CanBleSessionState = CanBleSessionState()
        private set
    val currentState: HandshakeState
        get() = sessionState.handshakeState

    var onWriteRequired: ((uuid: UUID, data: ByteArray) -> Unit)? = null
    var onHandshakeComplete: ((CanBleSessionState) -> Unit)? = null
    var onHandshakeFailed: ((failure: HandshakeFailure, attempt: Int, maxRetries: Int) -> Unit)? = null

    fun start() {
        cancelTimer()
        attempt = 0
        decryptFailureRetried = false
        appRand = ByteArray(4)
        sessionState = CanBleSessionState().copy(handshakeState = HandshakeState.DISCOVERED)

        if (!crypto.supportsCanCrypto()) {
            failImmediate(HandshakeFailure.CryptoUnavailable)
            return
        }

        sendRand1()
    }

    fun resetAndRetry() {
        if (sessionState.handshakeState != HandshakeState.FAILED) return
        if (attempt >= retryConfig.maxRetries) return
        cancelTimer()
        decryptFailureRetried = false
        appRand = ByteArray(4)
        sessionState = sessionState.copy(dynamicKey = null, handshakeState = HandshakeState.DISCOVERED)
        sendRand1()
    }

    fun reset() {
        cancelTimer()
        attempt = 0
        decryptFailureRetried = false
        appRand = ByteArray(4)
        sessionState = CanBleSessionState()
    }

    fun destroy() {
        reset()
        onWriteRequired = null
        onHandshakeComplete = null
        onHandshakeFailed = null
    }

    fun onControlNotificationReceived(encryptedData: ByteArray) {
        val state = sessionState.handshakeState
        if (state != HandshakeState.RAND1_SENT && state != HandshakeState.MTU_REQUEST_SENT) return

        if (encryptedData.size % 16 != 0) {
            fail(HandshakeFailure.InvalidResponse(state, "notification length ${encryptedData.size} not multiple of 16"))
            return
        }

        val decrypted = crypto.decrypt(encryptedData, CONTEXT_FIXED_KEY)
        if (decrypted == null) {
            fail(HandshakeFailure.DecryptFailure(state))
            return
        }

        when (state) {
            HandshakeState.RAND1_SENT -> handleDeviceRandomResponse(decrypted)
            HandshakeState.MTU_REQUEST_SENT -> handleMtuResponse(decrypted)
            else -> {}
        }
    }

    private fun sendRand1() {
        secureRandom.nextBytes(appRand)
        val payload = byteArrayOf(CAN_RAND1, appRand[0], appRand[1], appRand[2], appRand[3])
        val encrypted = crypto.encrypt(payload, CONTEXT_FIXED_KEY)
        if (encrypted == null) {
            failImmediate(HandshakeFailure.CryptoUnavailable)
            return
        }
        sessionState = sessionState.copy(handshakeState = HandshakeState.RAND1_SENT)
        onWriteRequired?.invoke(uuidCanControl, encrypted)
        startTimer(TIMEOUT_RAND1_SENT)
    }

    private fun handleDeviceRandomResponse(decrypted: ByteArray) {
        if (decrypted.size < 11) {
            fail(HandshakeFailure.InvalidResponse(HandshakeState.RAND1_SENT, "device response too short: ${decrypted.size}"))
            return
        }
        if (decrypted[0] != CAN_RAND2) {
            fail(HandshakeFailure.InvalidResponse(HandshakeState.RAND1_SENT, "expected cmd 0x02 but got 0x${"%02x".format(decrypted[0])}"))
            return
        }

        cancelTimer()
        val deviceRand = decrypted.copyOfRange(1, 5)
        val extra = decrypted.copyOfRange(5, 11)

        sessionState = sessionState.copy(handshakeState = HandshakeState.DEVICE_RANDOM_RECEIVED)

        val key = deriveKey(appRand, deviceRand, extra)
        crypto.setDynamicKey(key)
        sessionState = sessionState.copy(dynamicKey = key, handshakeState = HandshakeState.DYNAMIC_KEY_DERIVED)

        sendMtuRequest()
    }

    private fun sendMtuRequest() {
        val payload = byteArrayOf(CAN_GET_MTU)
        val encrypted = crypto.encrypt(payload, CONTEXT_FIXED_KEY)
        if (encrypted == null) {
            failImmediate(HandshakeFailure.CryptoUnavailable)
            return
        }
        sessionState = sessionState.copy(handshakeState = HandshakeState.MTU_REQUEST_SENT)
        onWriteRequired?.invoke(uuidCanControl, encrypted)
        startTimer(TIMEOUT_MTU_REQUEST_SENT)
    }

    private fun handleMtuResponse(decrypted: ByteArray) {
        if (decrypted.isEmpty() || decrypted[0] != CAN_RES_MTU) {
            fail(HandshakeFailure.InvalidResponse(HandshakeState.MTU_REQUEST_SENT, "expected cmd 0x09 but got 0x${"%02x".format(decrypted[0])}"))
            return
        }
        cancelTimer()
        sessionState = sessionState.copy(
            aU = 0,
            handshakeState = HandshakeState.READY,
            connectedSince = timer.currentTimeMs()
        )
        onHandshakeComplete?.invoke(sessionState)
    }

    private fun deriveKey(appRand: ByteArray, deviceRand: ByteArray, extra: ByteArray): ByteArray {
        val key = ByteArray(16)
        for (i in 0 until 4) {
            key[i * 3] = appRand[i]
            key[i * 3 + 1] = deviceRand[i]
            key[i * 3 + 2] = extra[i]
        }
        key[12] = 0x55
        key[13] = 0xAA.toByte()
        key[14] = extra[4]
        key[15] = extra[5]
        return key
    }

    private fun startTimer(delayMs: Long) {
        activeTimer = timer.schedule(delayMs) {
            fail(HandshakeFailure.Timeout(sessionState.handshakeState))
        }
    }

    private fun cancelTimer() {
        activeTimer?.cancel()
        activeTimer = null
    }

    private fun fail(failure: HandshakeFailure) {
        if (sessionState.handshakeState == HandshakeState.FAILED) return
        cancelTimer()

        if (failure is HandshakeFailure.DecryptFailure && decryptFailureRetried) {
            sessionState = sessionState.copy(handshakeState = HandshakeState.FAILED)
            onHandshakeFailed?.invoke(failure, Int.MAX_VALUE, retryConfig.maxRetries)
            return
        }

        if (failure is HandshakeFailure.DecryptFailure) {
            decryptFailureRetried = true
        }

        attempt++
        sessionState = sessionState.copy(handshakeState = HandshakeState.FAILED)
        onHandshakeFailed?.invoke(failure, attempt, retryConfig.maxRetries)
    }

    private fun failImmediate(failure: HandshakeFailure) {
        cancelTimer()
        sessionState = sessionState.copy(handshakeState = HandshakeState.FAILED)
        onHandshakeFailed?.invoke(failure, Int.MAX_VALUE, retryConfig.maxRetries)
    }

    companion object {
        private const val CAN_RAND1: Byte = 0x01
        private const val CAN_RAND2: Byte = 0x02
        private const val CAN_GET_MTU: Byte = 0x08
        private const val CAN_RES_MTU: Byte = 0x09
        private const val CONTEXT_FIXED_KEY: Int = 1
        private const val TIMEOUT_RAND1_SENT: Long = 5000L
        private const val TIMEOUT_MTU_REQUEST_SENT: Long = 5000L
    }
}
