package com.test.bafangcon.canble.crypto

import android.util.Log
import java.lang.reflect.Method

class NativeCryptoProvider : CryptoProvider {

    private var supportsNative: Boolean = false
    private var bafangEncry: Method? = null
    private var bafangDecry: Method? = null
    private var status: CryptoProviderStatus = CryptoProviderStatus.Unavailable("Not initialized")

    init {
        try {
            val clazz = Class.forName("com.pairlink.lib.NativeHelper")
            logI("NativeHelper found via Class.forName")

            bafangEncry = try {
                val m = clazz.getMethod("bafangEncry", ByteArray::class.java, Int::class.java)
                logI("Method bafangEncry(ByteArray, Int) resolved")
                m
            } catch (e: NoSuchMethodException) {
                logW("Method bafangEncry missing: ${e.message}")
                null
            }

            bafangDecry = try {
                val m = clazz.getMethod("bafangDecry", ByteArray::class.java, Int::class.java)
                logI("Method bafangDecry(ByteArray, Int) resolved")
                m
            } catch (e: NoSuchMethodException) {
                logW("Method bafangDecry missing: ${e.message}")
                null
            }

            supportsNative = bafangEncry != null && bafangDecry != null
            if (supportsNative) {
                status = CryptoProviderStatus.Available
                logI("NativeCryptoProvider initialized successfully")
            } else {
                status = CryptoProviderStatus.Unavailable("Required methods not found")
                logW("NativeCryptoProvider: required methods not found")
            }
        } catch (e: ClassNotFoundException) {
            status = CryptoProviderStatus.Unavailable("NativeHelper not found")
            logW("NativeHelper not found \u2014 native CAN crypto unavailable")
        } catch (e: Exception) {
            status = CryptoProviderStatus.Unavailable("Reflection failed: ${e.message}")
            logE("NativeHelper reflection failed: ${e.message}", e)
        }
    }

    fun getStatus(): CryptoProviderStatus = status

    override fun supportsCanCrypto(): Boolean = supportsNative

    override fun encrypt(data: ByteArray, context: Int): ByteArray? {
        if (!supportsNative) return null
        return try {
            val result = bafangEncry!!.invoke(null, data, context) as ByteArray
            logI("Encrypt OK: ${data.size}B -> ${result.size}B context=$context")
            result
        } catch (e: Exception) {
            logE("Encryption failed: ${e.message}", e)
            null
        }
    }

    override fun decrypt(data: ByteArray, context: Int): ByteArray? {
        if (!supportsNative) return null
        return try {
            val result = bafangDecry!!.invoke(null, data, context) as ByteArray
            logI("Decrypt OK: ${data.size}B -> ${result.size}B context=$context")
            result
        } catch (e: Exception) {
            logE("Decryption failed: ${e.message}", e)
            null
        }
    }

    override fun setDynamicKey(key: ByteArray) {
        logD("setDynamicKey ignored \u2014 native library manages its own key")
    }

    companion object {
        private const val TAG = "NativeCryptoProvider"

        private fun logI(msg: String) {
            try { Log.i(TAG, msg) } catch (_: RuntimeException) { }
        }

        private fun logW(msg: String) {
            try { Log.w(TAG, msg) } catch (_: RuntimeException) { }
        }

        private fun logE(msg: String, t: Throwable? = null) {
            try { Log.e(TAG, msg, t) } catch (_: RuntimeException) { }
        }

        private fun logD(msg: String) {
            try { Log.d(TAG, msg) } catch (_: RuntimeException) { }
        }
    }
}
