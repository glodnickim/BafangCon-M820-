package com.test.bafangcon.canble.crypto

import android.util.Log

@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "FallbackCryptoProvider uses an UNCONFIRMED fixed encryption key (16x 0x01). " +
            "Do NOT use on real hardware until the key is verified against target firmware. " +
            "Using wrong key may cause device communication failures or require a display restart."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ExperimentalCanCrypto

@ExperimentalCanCrypto
class FallbackCryptoProvider : CryptoProvider {

    private var status: CryptoProviderStatus = CryptoProviderStatus.Experimental

    init {
        logW("╔══════════════════════════════════════════════════════════════╗")
        logW("║  FALLBACK CRYPTO \u2014 EXPERIMENTAL \u2014 DO NOT USE ON REAL HARDWARE ║")
        logW("║  Fixed encryption key (16x 0x01) is UNCONFIRMED.             ║")
        logW("║  Source: Go+ decompile fill pattern only.                    ║")
        logW("║  This provider returns null for all crypto operations.       ║")
        logW("╚══════════════════════════════════════════════════════════════╝")
    }

    fun getStatus(): CryptoProviderStatus = status

    override fun supportsCanCrypto(): Boolean = false

    override fun encrypt(data: ByteArray, context: Int): ByteArray? {
        logW("encrypt() called but not implemented \u2014 returns null")
        return null
    }

    override fun decrypt(data: ByteArray, context: Int): ByteArray? {
        logW("decrypt() called but not implemented \u2014 returns null")
        return null
    }

    override fun setDynamicKey(key: ByteArray) {
        logD("setDynamicKey() called with ${key.size}B key \u2014 no-op (key not confirmed)")
    }

    companion object {
        private const val TAG = "FallbackCryptoProvider"

        private fun logW(msg: String) {
            try { Log.w(TAG, msg) } catch (_: RuntimeException) { }
        }

        private fun logD(msg: String) {
            try { Log.d(TAG, msg) } catch (_: RuntimeException) { }
        }
    }
}
