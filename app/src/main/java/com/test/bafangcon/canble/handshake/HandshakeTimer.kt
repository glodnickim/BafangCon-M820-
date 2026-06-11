package com.test.bafangcon.canble.handshake

interface TimerHandle {
    val isActive: Boolean
    fun cancel(): Boolean
}

interface HandshakeTimer {
    fun schedule(delayMs: Long, callback: () -> Unit): TimerHandle
    fun cancel(handle: TimerHandle)
    fun currentTimeMs(): Long
}
