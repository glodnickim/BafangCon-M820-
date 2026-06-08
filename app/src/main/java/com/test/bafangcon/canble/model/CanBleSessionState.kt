package com.test.bafangcon.canble.model

data class CanBleSessionState(
    val aU: Int = -1,
    val dynamicKey: ByteArray? = null,
    val handshakeState: HandshakeState = HandshakeState.IDLE,
    val mtu: Int = 23,
    val connectedSince: Long = 0L
) {
    val isReady: Boolean
        get() = aU != -1 && handshakeState == HandshakeState.READY

    val useRxDecryption: Boolean
        get() = aU == 1 || aU == 3

    val useTxEncryption: Boolean
        get() = aU == 2 || aU == 3

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanBleSessionState) return false
        return aU == other.aU &&
                dynamicKey.contentEquals(other.dynamicKey) &&
                handshakeState == other.handshakeState &&
                mtu == other.mtu &&
                connectedSince == other.connectedSince
    }

    override fun hashCode(): Int {
        var result = aU
        result = 31 * result + (dynamicKey?.contentHashCode() ?: 0)
        result = 31 * result + handshakeState.hashCode()
        result = 31 * result + mtu
        result = 31 * result + connectedSince.hashCode()
        return result
    }

    override fun toString(): String {
        val keyPreview = dynamicKey?.joinToString("") { "%02x".format(it) }?.take(16) ?: "null"
        return "aU=$aU key=$keyPreview state=$handshakeState mtu=$mtu"
    }
}

enum class HandshakeState {
    IDLE,
    RAND1_SENT,
    RAND2_SENT,
    MTU_SENT,
    READY,
    FAILED
}
