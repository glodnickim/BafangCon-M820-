package com.test.bafangcon

import android.util.Log
import com.test.bafangcon.utils.ByteBufferUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.BufferUnderflowException

data class SensorInfo(
    var hardVersion: String = "",
    var softVersion: String = "",
    var model: String = "",
    var sn: String = "",
    var customerNo: String = "",
    var manufacturer: String = "",
    var voltageSignal: Int = 0,
    var cadence: Int = 0,

    var rawData: ByteArray? = null
) {
    companion object {
        private const val TAG = "SensorInfo"
        private const val TOTAL_SIZE = 164

        fun parseSensorInfoPayload(payload: ByteArray): SensorInfo? {
            if (payload.size < TOTAL_SIZE) {
                Log.w(TAG, "Payload too short: ${payload.size}, expected >= $TOTAL_SIZE")
                return null
            }
            val info = SensorInfo()
            info.rawData = payload

            try {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

                info.hardVersion = ByteBufferUtils.getAsciiString(buffer, 24)
                info.softVersion = ByteBufferUtils.getAsciiString(buffer, 24)
                info.model = ByteBufferUtils.getAsciiString(buffer, 24)
                info.sn = ByteBufferUtils.getAsciiString(buffer, 40)
                info.customerNo = ByteBufferUtils.getAsciiString(buffer, 16)
                info.manufacturer = ByteBufferUtils.getAsciiString(buffer, 16)

                if (buffer.remaining() >= 16) buffer.position(buffer.position() + 16)

                info.voltageSignal = ByteBufferUtils.getU16(buffer)
                info.cadence = ByteBufferUtils.getU16(buffer)

                Log.d(TAG, "Parsed SensorInfo successfully")
                return info
            } catch (e: BufferUnderflowException) {
                Log.e(TAG, "Error parsing SensorInfo payload: ${e.message}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing SensorInfo payload: ${e.message}", e)
                return null
            }
        }
    }
}
