package com.test.bafangcon.canble.crypto

import org.junit.Assert.*
import org.junit.Test

class FallbackCryptoProviderTest {

    private val provider = FallbackCryptoProvider()

    @Test
    fun supportsCanCrypto_returnsFalse() {
        assertFalse(provider.supportsCanCrypto())
    }

    @Test
    fun encrypt_returnsNull() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertNull(provider.encrypt(data, 1))
        assertNull(provider.encrypt(data, 0))
    }

    @Test
    fun decrypt_returnsNull() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertNull(provider.decrypt(data, 1))
        assertNull(provider.decrypt(data, 0))
    }

    @Test
    fun getStatus_returnsExperimental() {
        val status = provider.getStatus()
        assertTrue("Expected Experimental, got $status", status is CryptoProviderStatus.Experimental)
    }
}
