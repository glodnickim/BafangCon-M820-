package com.test.bafangcon.canble.handshake

data class HandshakeRetryConfig(
    val maxRetries: Int = 2,
    val baseDelayMs: Long = 1000L,
    val maxDelayMs: Long = 4000L
) {
    fun delayForAttempt(attempt: Int): Long =
        (baseDelayMs * attempt).coerceAtMost(maxDelayMs)
}
