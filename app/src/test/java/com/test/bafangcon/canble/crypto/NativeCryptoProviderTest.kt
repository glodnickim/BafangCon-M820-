package com.test.bafangcon.canble.crypto

import org.junit.Assert.*
import org.junit.Test

class NativeCryptoProviderTest {

    private val provider = NativeCryptoProvider()

    @Test
    fun supportsCanCrypto_returnsFalse_withoutNativeLibrary() {
        assertFalse(provider.supportsCanCrypto())
    }

    @Test
    fun encrypt_returnsNull_withoutNativeLibrary() {
        assertNull(provider.encrypt(byteArrayOf(0x01), 0))
    }

    @Test
    fun decrypt_returnsNull_withoutNativeLibrary() {
        assertNull(provider.decrypt(byteArrayOf(0x01), 0))
    }

    @Test
    fun getStatus_returnsUnavailable_withoutNativeLibrary() {
        val status = provider.getStatus()
        assertTrue("Expected Unavailable, got $status", status is CryptoProviderStatus.Unavailable)
        val reason = (status as CryptoProviderStatus.Unavailable).reason
        assertNotNull(reason)
        assertTrue("Reason should mention NativeHelper", reason.isNotEmpty())
    }
}
