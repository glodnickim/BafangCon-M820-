package com.test.bafangcon

import android.util.Log
import com.test.bafangcon.utils.ByteBufferUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.BufferUnderflowException

data class IotConfigInfo(
    var hardVersion: String = "",
    var softVersion: String = "",
    var model: String = "",
    var sn: String = "",
    var iccid: String = "",
    var imei: String = "",
    var server: String = "",
    var port: String = "",
    var apn: String = "",
    var apnUser: String = "",
    var apnPassword: String = "",
    var bleName: String = "",

    var rawData: ByteArray? = null
) {
    companion object {
        private const val TAG = "IotConfigInfo"
        private const val TOTAL_SIZE = 237

        fun parseIotConfigInfoPayload(payload: ByteArray): IotConfigInfo? {
            if (payload.size < TOTAL_SIZE) {
                Log.w(TAG, "Payload too short: ${payload.size}, expected >= $TOTAL_SIZE")
                return null
            }
            val info = IotConfigInfo()
            info.rawData = payload

            try {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

                info.hardVersion = ByteBufferUtils.getAsciiString(buffer, 4)
                info.softVersion = ByteBufferUtils.getAsciiString(buffer, 4)
                info.model = ByteBufferUtils.getAsciiString(buffer, 4)
                info.sn = ByteBufferUtils.getAsciiString(buffer, 4)

                if (buffer.remaining() >= 8) buffer.position(buffer.position() + 8)

                info.iccid = ByteBufferUtils.getAsciiString(buffer, 20)
                info.imei = ByteBufferUtils.getAsciiString(buffer, 15)
                info.server = ByteBufferUtils.getAsciiString(buffer, 64)
                info.port = ByteBufferUtils.getAsciiString(buffer, 8)
                info.apn = ByteBufferUtils.getAsciiString(buffer, 32)
                info.apnUser = ByteBufferUtils.getAsciiString(buffer, 32)
                info.apnPassword = ByteBufferUtils.getAsciiString(buffer, 32)
                info.bleName = ByteBufferUtils.getAsciiString(buffer, 10)

                Log.d(TAG, "Parsed IotConfigInfo successfully")
                return info
            } catch (e: BufferUnderflowException) {
                Log.e(TAG, "Error parsing IotConfigInfo payload: ${e.message}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing IotConfigInfo payload: ${e.message}", e)
                return null
            }
        }
    }
}
