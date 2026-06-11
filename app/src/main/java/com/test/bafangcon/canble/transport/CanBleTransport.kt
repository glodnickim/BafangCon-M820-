package com.test.bafangcon.canble.transport

import android.util.Log
import com.test.bafangcon.canble.handshake.CanBleHandshake
import com.test.bafangcon.canble.log.CanBleLogger
import com.test.bafangcon.canble.model.CanBleSessionState
import com.test.bafangcon.canble.model.HandshakeState
import com.test.bafangcon.canble.model.RawCanNotification
import com.test.bafangcon.canble.process.CanBleFrameExtractor
import com.test.bafangcon.canble.process.CanBleFrameFilter
import com.test.bafangcon.canble.process.FilterResult
import java.util.UUID

class CanBleTransport(
    private val handshake: CanBleHandshake,
    private val extractor: CanBleFrameExtractor = CanBleFrameExtractor,
    private val filter: CanBleFrameFilter = CanBleFrameFilter,
    private val logger: CanBleLogger = CanBleLogger()
) {
    private var _state: CanBleTransportState = CanBleTransportState.IDLE
    val state: CanBleTransportState
        get() = _state

    var onWriteRequired: ((uuid: UUID, data: ByteArray) -> Unit)? = null
    var onHandshakeReady: ((CanBleSessionState) -> Unit)? = null

    init {
        handshake.onWriteRequired = { uuid, data ->
            onWriteRequired?.invoke(uuid, data)
        }
        handshake.onHandshakeComplete = { sessionState ->
            _state = CanBleTransportState.READY
            logger.logSessionState(sessionState)
            safeLog("[CANBLE] handshake READY")
            onHandshakeReady?.invoke(sessionState)
        }
        handshake.onHandshakeFailed = { failure, _, _ ->
            _state = CanBleTransportState.FAILED
            logger.logError("handshake failed: $failure")
            safeLog("[CANBLE] handshake failed: $failure")
        }
    }

    fun onServiceFound() {
        if (_state != CanBleTransportState.IDLE && _state != CanBleTransportState.DETECTING) return
        _state = CanBleTransportState.SERVICE_FOUND
        safeLog("[CANBLE] service found")
    }

    fun onNotificationsSubscribed() {
        if (_state != CanBleTransportState.SERVICE_FOUND) return
        _state = CanBleTransportState.SUBSCRIBED
        safeLog("[CANBLE] notifications enabled")
        _state = CanBleTransportState.HANDSHAKE_IN_PROGRESS
        safeLog("[CANBLE] handshake started")
        handshake.start()
    }

    fun onControlNotificationReceived(data: ByteArray) {
        val hs = handshake.currentState
        if (hs != HandshakeState.RAND1_SENT && hs != HandshakeState.MTU_REQUEST_SENT) return
        if (_state == CanBleTransportState.FAILED) {
            _state = CanBleTransportState.HANDSHAKE_IN_PROGRESS
        }
        handshake.onControlNotificationReceived(data)
    }

    fun onRxNotificationReceived(data: ByteArray, uuid: UUID) {
        if (_state != CanBleTransportState.READY) return

        val raw = RawCanNotification(rawData = data, uuid = uuid)
        logger.logNotification(raw)

        val decrypted = if (handshake.sessionState.useRxDecryption) {
            logger.logError("RX decryption requested but not implemented in Phase 1")
            data
        } else {
            data
        }

        val frames = extractor.extract(decrypted)
        for (frame in frames) {
            when (val result = filter.apply(frame)) {
                is FilterResult.Accepted -> logger.logFrame(frame)
                is FilterResult.Rejected -> logger.logFiltered(frame, result.reason)
            }
        }
    }

    fun onDisconnected() {
        handshake.reset()
        _state = CanBleTransportState.IDLE
    }

    fun destroy() {
        handshake.destroy()
        onWriteRequired = null
        onHandshakeReady = null
    }

    private fun safeLog(msg: String) {
        try { Log.i(TAG, msg) } catch (_: RuntimeException) { }
    }

    companion object {
        private const val TAG = "CanBleTransport"
    }
}
