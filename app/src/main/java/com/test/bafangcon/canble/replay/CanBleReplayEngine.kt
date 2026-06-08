package com.test.bafangcon.canble.replay

import com.test.bafangcon.canble.log.CanBleMetricsCollector
import com.test.bafangcon.canble.model.CanBleFrame
import com.test.bafangcon.canble.model.RecordedCanNotification
import com.test.bafangcon.canble.model.RecordedCanSession
import com.test.bafangcon.canble.process.CanBleFrameExtractor
import com.test.bafangcon.canble.process.CanBleFrameFilter
import com.test.bafangcon.canble.process.FilterResult

sealed class StepResult {
    data class Processed(
        val notification: RecordedCanNotification,
        val decryptedBytes: ByteArray,
        val frames: List<CanBleFrame>,
        val accepted: List<CanBleFrame>,
        val rejected: List<RejectedFrame>
    ) : StepResult()

    data class Skipped(
        val notification: RecordedCanNotification,
        val reason: String
    ) : StepResult()
}

data class RejectedFrame(
    val frame: CanBleFrame,
    val reason: String
)

data class ReplaySummary(
    val totalNotifications: Int,
    val processedNotifications: Int,
    val skippedNotifications: Int,
    val totalFrames: Int,
    val acceptedFrames: Int,
    val rejectedFrames: Int,
    val uniqueSourceNodes: Set<Int>,
    val uniqueDestNodes: Set<Int>,
    val uniqueFuncIndexes: Set<String>,
    val aUModes: Set<Int>,
    val sessionDurationMs: Long,
    val startTimestampMs: Long,
    val endTimestampMs: Long
)

/**
 * Full replay result with per-step details.
 * Intended for small/medium sessions only. For large sessions use [replayStepByStep].
 */
data class ReplayResult(
    val steps: List<StepResult>,
    val summary: ReplaySummary
)

class CanBleReplayEngine(
    private val extractor: CanBleFrameExtractor = CanBleFrameExtractor,
    private val filter: CanBleFrameFilter = CanBleFrameFilter,
    private val metricsCollector: CanBleMetricsCollector = CanBleMetricsCollector()
) {

    /**
     * Process all notifications in a session.
     * Returns [ReplayResult] with per-step details — use only for small/medium sessions.
     * For large sessions, use [replayStepByStep] to avoid holding all results in memory.
     */
    fun replay(session: RecordedCanSession): ReplayResult {
        val steps = mutableListOf<StepResult>()
        val summary = replayStepByStep(session) { step ->
            steps.add(step)
        }
        return ReplayResult(steps = steps, summary = summary)
    }

    /**
     * Process all notifications, streaming each [StepResult] via [onStep] callback.
     * Returns only [ReplaySummary] — safe for sessions of any size.
     */
    fun replayStepByStep(
        session: RecordedCanSession,
        onStep: (StepResult) -> Unit = {}
    ): ReplaySummary {
        val notifications = session.replay()
        var processed = 0
        var skipped = 0
        val allAUModes = mutableSetOf<Int>()
        val allUuids = mutableSetOf<String>()
        var firstTs = Long.MAX_VALUE
        var lastTs = Long.MIN_VALUE
        var accepted = 0
        var rejected = 0
        var frames = 0
        val srcNodes = mutableSetOf<Int>()
        val dstNodes = mutableSetOf<Int>()
        val funcIndexes = mutableSetOf<String>()

        for (notification in notifications) {
            val result = step(notification)
            onStep(result)

            when (result) {
                is StepResult.Processed -> {
                    processed++
                    accepted += result.accepted.size
                    rejected += result.rejected.size
                    frames += result.frames.size
                    for (frame in result.frames) {
                        srcNodes.add(frame.sourceNode)
                        dstNodes.add(frame.destNode)
                        funcIndexes.add(frame.canIndex)
                    }
                }
                is StepResult.Skipped -> {
                    skipped++
                }
            }

            allAUModes.add(notification.aUMode)
            allUuids.add(notification.sourceUuid)
            if (notification.timestampMs < firstTs) firstTs = notification.timestampMs
            if (notification.timestampMs > lastTs) lastTs = notification.timestampMs
        }

        if (firstTs == Long.MAX_VALUE) firstTs = 0L
        if (lastTs == Long.MIN_VALUE) lastTs = 0L

        return ReplaySummary(
            totalNotifications = notifications.size,
            processedNotifications = processed,
            skippedNotifications = skipped,
            totalFrames = frames,
            acceptedFrames = accepted,
            rejectedFrames = rejected,
            uniqueSourceNodes = srcNodes,
            uniqueDestNodes = dstNodes,
            uniqueFuncIndexes = funcIndexes,
            aUModes = allAUModes,
            sessionDurationMs = lastTs - firstTs,
            startTimestampMs = firstTs,
            endTimestampMs = lastTs
        )
    }

    fun step(notification: RecordedCanNotification): StepResult {
        val hex = notification.decryptedHex
        if (hex == null) {
            return StepResult.Skipped(notification, "no decrypted data")
        }

        val bytes = parseHex(hex)
        if (bytes == null) {
            return StepResult.Skipped(notification, "invalid_hex")
        }

        metricsCollector.onNotification()
        val frames = extractor.extract(bytes)
        val accepted = mutableListOf<CanBleFrame>()
        val rejected = mutableListOf<RejectedFrame>()

        for (frame in frames) {
            when (val result = filter.apply(frame)) {
                is FilterResult.Accepted -> {
                    accepted.add(frame)
                    metricsCollector.onFrameAccepted(frame)
                }
                is FilterResult.Rejected -> {
                    rejected.add(RejectedFrame(frame, result.reason))
                    metricsCollector.onFrameFiltered(frame, result.reason)
                }
            }
        }

        return StepResult.Processed(
            notification = notification,
            decryptedBytes = bytes,
            frames = frames,
            accepted = accepted,
            rejected = rejected
        )
    }

    fun getMetrics() = metricsCollector.snapshot()

    fun reset() {
        metricsCollector.reset()
    }

    private fun parseHex(hex: String): ByteArray? {
        if (hex.isEmpty()) return null
        if (hex.length % 2 != 0) return null
        val chars = hex.toCharArray()
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val hi = digitValue(chars[i * 2]) ?: return null
            val lo = digitValue(chars[i * 2 + 1]) ?: return null
            result[i] = ((hi shl 4) or lo).toByte()
        }
        return result
    }

    private fun digitValue(c: Char): Int? {
        return when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> null
        }
    }
}
