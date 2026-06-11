package com.test.bafangcon.canble.process

import com.test.bafangcon.canble.model.CanBleFrame

sealed class FilterResult {
    data class Accepted(val frame: CanBleFrame) : FilterResult()
    data class Rejected(val frame: CanBleFrame, val reason: String) : FilterResult()
}

object CanBleFrameFilter {

    fun apply(frame: CanBleFrame): FilterResult {
        if (frame.sourceNode == CAN_NODE_APP || frame.sourceNode == CAN_NODE_UNKNOWN) {
            return FilterResult.Rejected(frame, REASON_SELF_ECHO)
        }

        if (frame.func == FUNC_LONG_DATA_TEMPLATE && frame.index == 0) {
            return FilterResult.Rejected(frame, REASON_LONG_DATA_TEMPLATE)
        }

        if (frame.op in OP_LONG_DATA_RANGE && frame.destNode != CAN_NODE_APP) {
            return FilterResult.Rejected(frame, REASON_LONG_DATA_NOT_FOR_US)
        }

        if (frame.op in OP_LONG_DATA_RANGE && frame.destNode == CAN_NODE_APP) {
            return FilterResult.Accepted(frame)
        }

        if (frame.destNode != CAN_NODE_BROADCAST &&
            frame.destNode != CAN_NODE_APP &&
            frame.func != FUNC_BATTERY_PERCENT
        ) {
            return FilterResult.Rejected(frame, REASON_WRONG_DEST)
        }

        return FilterResult.Accepted(frame)
    }

    private const val CAN_NODE_APP = 19
    private const val CAN_NODE_UNKNOWN = 5
    private const val CAN_NODE_BROADCAST = 31
    private const val FUNC_LONG_DATA_TEMPLATE = 0x60
    private const val FUNC_BATTERY_PERCENT = 99
    private val OP_LONG_DATA_RANGE = 4..6

    private const val REASON_SELF_ECHO = "self_echo"
    private const val REASON_LONG_DATA_TEMPLATE = "long_data_template"
    private const val REASON_LONG_DATA_NOT_FOR_US = "long_data_not_for_us"
    private const val REASON_WRONG_DEST = "wrong_dest"
}
