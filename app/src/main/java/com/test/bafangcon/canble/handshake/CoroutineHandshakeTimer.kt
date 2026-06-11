package com.test.bafangcon.canble.handshake

import android.os.Handler
import android.os.Looper

class CoroutineHandshakeTimer : HandshakeTimer {

    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMs: Long, callback: () -> Unit): TimerHandle {
        val handle = HandlerTimerHandle(callback, handler)
        handler.postDelayed(handle.runnable, delayMs)
        return handle
    }

    override fun cancel(handle: TimerHandle) {
        if (handle is HandlerTimerHandle) {
            handle.cancel()
        }
    }

    override fun currentTimeMs(): Long = System.currentTimeMillis()
}

private class HandlerTimerHandle(
    private val userCallback: () -> Unit,
    private val handler: Handler
) : TimerHandle {
    @Volatile
    var cancelled = false

    val runnable = Runnable {
        if (!cancelled) {
            userCallback()
        }
    }

    override val isActive: Boolean get() = !cancelled

    override fun cancel(): Boolean {
        if (cancelled) return false
        cancelled = true
        handler.removeCallbacks(runnable)
        return true
    }
}
