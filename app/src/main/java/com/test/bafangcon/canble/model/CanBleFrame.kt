package com.test.bafangcon.canble.model

data class CanBleFrame(
    val sourceNode: Int,
    val destNode: Int,
    val op: Int,
    val func: Int,
    val index: Int,
    val payload: ByteArray,
    val payloadLen: Int,
    val rawHex: String
) {
    val canIndex: String
        get() = "${func.toString(16)}:${index.toString(16)}"

    val isLongData: Boolean
        get() = op in 4..6

    val isBroadcast: Boolean
        get() = destNode == 31

    val isAddressedToApp: Boolean
        get() = destNode == 19

    val isSelfEcho: Boolean
        get() = sourceNode == 19 || sourceNode == 5

    val isBatteryPercent: Boolean
        get() = func == 99

    val isLongDataTemplate: Boolean
        get() = func == 0x60 && index == 0x00

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanBleFrame) return false
        return sourceNode == other.sourceNode &&
                destNode == other.destNode &&
                op == other.op &&
                func == other.func &&
                index == other.index &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sourceNode
        result = 31 * result + destNode
        result = 31 * result + op
        result = 31 * result + func
        result = 31 * result + index
        result = 31 * result + payload.contentHashCode()
        return result
    }

    fun toShortString(): String {
        val payloadPreview = if (payload.isNotEmpty()) {
            payload.joinToString("") { "%02x".format(it) }.take(16)
        } else ""
        return "node=$sourceNode->$destNode op=$op idx=${canIndex} len=$payloadLen data=$payloadPreview"
    }

    companion object {
        private const val CAN_NODE_APP = 19
        private const val CAN_NODE_BROADCAST = 31
        private const val FUNC_BATTERY_PERCENT = 99
        private const val FUNC_LONG_DATA_TEMPLATE = 0x60
    }
}
