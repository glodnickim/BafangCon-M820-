package com.test.bafangcon.utils

import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object AESUtils {
    private const val TAG = "AESUtils"
    private const val AES_KEY_STRING = "2CTDU40qNyCgTjb1"
    private const val ALGORITHM = "AES/ECB/NoPadding"

    private val secretKeySpec: SecretKeySpec by lazy {
        SecretKeySpec(AES_KEY_STRING.toByteArray(Charsets.US_ASCII), "AES")
    }

    fun encrypt(plaintext: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec)
            cipher.doFinal(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "AES encryption failed: ${e.message}", e)
            null
        }
    }
}
