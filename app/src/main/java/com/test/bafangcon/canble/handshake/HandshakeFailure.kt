package com.test.bafangcon.canble.handshake

import com.test.bafangcon.canble.model.HandshakeState

sealed class HandshakeFailure {
    abstract val isRetryable: Boolean

    data class Timeout(val state: HandshakeState) : HandshakeFailure() {
        override val isRetryable: Boolean = true
    }

    data class InvalidResponse(
        val state: HandshakeState,
        val details: String
    ) : HandshakeFailure() {
        override val isRetryable: Boolean = true
    }

    data class DecryptFailure(val state: HandshakeState) : HandshakeFailure() {
        override val isRetryable: Boolean = true
    }

    object CryptoUnavailable : HandshakeFailure() {
        override val isRetryable: Boolean = false
    }

    data class InvalidKeyDerivation(val details: String) : HandshakeFailure() {
        override val isRetryable: Boolean = false
    }
}
