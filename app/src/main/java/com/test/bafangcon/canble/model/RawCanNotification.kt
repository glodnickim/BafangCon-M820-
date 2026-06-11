package com.test.bafangcon.canble.model

import java.util.UUID

data class RawCanNotification(
    val rawData: ByteArray,
    val uuid: UUID,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val rawHex: String
        get() = rawData.joinToString("") { "%02x".format(it) }

    val length: Int
        get() = rawData.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawCanNotification) return false
        return rawData.contentEquals(other.rawData) &&
                uuid == other.uuid &&
                timestampMs == other.timestampMs
    }

    override fun hashCode(): Int {
        var result = rawData.contentHashCode()
        result = 31 * result + uuid.hashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}
