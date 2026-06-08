package com.test.bafangcon.canble.log

import android.util.Log
import com.test.bafangcon.canble.model.CanBleFrame
import com.test.bafangcon.canble.model.CanBleSessionState
import com.test.bafangcon.canble.model.RawCanNotification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CanBleMetricsCollector {

    private val startTimeMs = System.currentTimeMillis()
    private val _totalNotifications = AtomicInteger()
    private val _acceptedFrames = AtomicInteger()
    private val _filteredFrames = AtomicInteger()
    private val _decryptFailures = AtomicInteger()
    private val _uniqueCanIndexes = ConcurrentHashMap.newKeySet<String>()
    private val _uniqueSourceNodes = ConcurrentHashMap.newKeySet<Int>()
    private val _uniqueDestNodes = ConcurrentHashMap.newKeySet<Int>()
    @Volatile
    private var _handshakeDurationMs: Long? = null

    fun onNotification() {
        _totalNotifications.incrementAndGet()
    }

    fun onFrameAccepted(frame: CanBleFrame) {
        _acceptedFrames.incrementAndGet()
        _uniqueCanIndexes.add(frame.canIndex)
        _uniqueSourceNodes.add(frame.sourceNode)
        _uniqueDestNodes.add(frame.destNode)
    }

    fun onFrameFiltered(frame: CanBleFrame, reason: String) {
        _filteredFrames.incrementAndGet()
        _uniqueCanIndexes.add(frame.canIndex)
        _uniqueSourceNodes.add(frame.sourceNode)
        _uniqueDestNodes.add(frame.destNode)
    }

    fun onDecryptFailure() {
        _decryptFailures.incrementAndGet()
    }

    fun handshakeCompleted(durationMs: Long) {
        _handshakeDurationMs = durationMs
    }

    fun snapshot(): CanBleMetrics {
        val totalNotif = _totalNotifications.get()
        // Phase 1: simple average since collector start.
        // Rolling 10s window deferred to Phase 2.
        val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000.0
        val notifPerSec = if (elapsed > 0.0 && totalNotif > 0) {
            totalNotif / elapsed
        } else 0.0

        return CanBleMetrics(
            notificationsPerSec = notifPerSec,
            totalNotifications = totalNotif,
            totalFrames = _acceptedFrames.get() + _filteredFrames.get(),
            acceptedFrames = _acceptedFrames.get(),
            filteredFrames = _filteredFrames.get(),
            uniqueCanIndexes = _uniqueCanIndexes.toSet(),
            uniqueSourceNodes = _uniqueSourceNodes.toSet(),
            uniqueDestNodes = _uniqueDestNodes.toSet(),
            decryptFailures = _decryptFailures.get(),
            handshakeDurationMs = _handshakeDurationMs
        )
    }

    fun reset() {
        _totalNotifications.set(0)
        _acceptedFrames.set(0)
        _filteredFrames.set(0)
        _decryptFailures.set(0)
        _uniqueCanIndexes.clear()
        _uniqueSourceNodes.clear()
        _uniqueDestNodes.clear()
        _handshakeDurationMs = null
    }
}

data class CanBleMetrics(
    val notificationsPerSec: Double,
    val totalNotifications: Int,
    val totalFrames: Int,
    val acceptedFrames: Int,
    val filteredFrames: Int,
    val uniqueCanIndexes: Set<String>,
    val uniqueSourceNodes: Set<Int>,
    val uniqueDestNodes: Set<Int>,
    val decryptFailures: Int,
    val handshakeDurationMs: Long?
)

class CanBleLogger(
    private val metricsCollector: CanBleMetricsCollector = CanBleMetricsCollector()
) {

    fun logNotification(raw: RawCanNotification) {
        metricsCollector.onNotification()
        val preview = raw.rawHex.take(64)
        logI("NOTIFICATION  len=${raw.length} uuid=${raw.uuid} raw=$preview")
    }

    fun logDecrypted(encryptedHex: String, decryptedHex: String) {
        logI("DECRYPTED     enc=${encryptedHex.take(64)} dec=${decryptedHex.take(64)}")
    }

    fun logFrame(frame: CanBleFrame) {
        metricsCollector.onFrameAccepted(frame)
        logI("FRAME         ${frame.toShortString()}")
    }

    fun logFiltered(frame: CanBleFrame, reason: String) {
        metricsCollector.onFrameFiltered(frame, reason)
        logI("FILTERED      ${frame.toShortString()} reason=$reason")
    }

    fun logSessionState(state: CanBleSessionState) {
        logI("STATE         $state")
    }

    fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            logE("ERROR         $message", throwable)
        } else {
            logE("ERROR         $message")
        }
    }

    fun logMetrics() {
        val m = metricsCollector.snapshot()
        logI("METRICS       " +
                "notif/s=${"%.1f".format(m.notificationsPerSec)} " +
                "total=${m.totalNotifications} " +
                "frames=${m.totalFrames} " +
                "acc=${m.acceptedFrames} " +
                "flt=${m.filteredFrames} " +
                "idx=${m.uniqueCanIndexes.size} " +
                "src=${m.uniqueSourceNodes.size} " +
                "dst=${m.uniqueDestNodes.size}")
    }

    fun getMetrics(): CanBleMetrics = metricsCollector.snapshot()

    private fun logI(msg: String) {
        try { Log.i(TAG, msg) } catch (_: RuntimeException) { }
    }

    private fun logE(msg: String, t: Throwable? = null) {
        try { Log.e(TAG, msg, t) } catch (_: RuntimeException) { }
    }

    companion object {
        private const val TAG = "CanBleLogger"
    }
}
