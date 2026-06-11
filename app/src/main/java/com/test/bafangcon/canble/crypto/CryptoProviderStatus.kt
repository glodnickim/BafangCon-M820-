package com.test.bafangcon.canble.crypto

sealed class CryptoProviderStatus {
    object Available : CryptoProviderStatus() {
        override fun toString(): String = "Available"
    }

    data class Unavailable(val reason: String) : CryptoProviderStatus() {
        override fun toString(): String = "Unavailable: $reason"
    }

    object Experimental : CryptoProviderStatus() {
        override fun toString(): String = "Experimental \u2014 use with caution"
    }
}
