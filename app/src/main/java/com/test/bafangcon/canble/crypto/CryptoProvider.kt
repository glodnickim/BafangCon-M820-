package com.test.bafangcon.canble.crypto

interface CryptoProvider {
    fun supportsCanCrypto(): Boolean
    fun encrypt(data: ByteArray, context: Int): ByteArray?
    fun decrypt(data: ByteArray, context: Int): ByteArray?
    fun setDynamicKey(key: ByteArray)
}
