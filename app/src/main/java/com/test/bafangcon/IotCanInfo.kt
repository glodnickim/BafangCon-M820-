package com.test.bafangcon

import android.util.Log
import com.test.bafangcon.utils.ByteBufferUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.BufferUnderflowException

data class IotCanInfo(
    var bleModel: Int = 0,
    var networkModel: Int = 0,
    var customizeData: String = "",
    var bleReportModel: Int = 0,
    var bleReportInterval: Long = 0L,
    var networkReportInterval: Long = 0L,
    var canFilters: List<String> = List(10) { "" },

    var rawData: ByteArray? = null
) {
    companion object {
        private const val TAG = "IotCanInfo"
        private const val TOTAL_SIZE = 97

        fun parseIotCanInfoPayload(payload: ByteArray): IotCanInfo? {
            if (payload.size < TOTAL_SIZE) {
                Log.w(TAG, "Payload too short: ${payload.size}, expected >= $TOTAL_SIZE")
                return null
            }
            val info = IotCanInfo()
            info.rawData = payload

            try {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

                info.bleModel = ByteBufferUtils.getU8(buffer)
                info.networkModel = ByteBufferUtils.getU8(buffer)

                val customBytes = ByteArray(16)
                if (buffer.remaining() >= 16) buffer.get(customBytes)
                info.customizeData = customBytes.joinToString("") { String.format("%02X", it) }

                if (buffer.remaining() >= 30) buffer.position(buffer.position() + 30)

                info.bleReportModel = ByteBufferUtils.getU8(buffer)
                info.bleReportInterval = ByteBufferUtils.getU32(buffer)
                info.networkReportInterval = ByteBufferUtils.getU32(buffer)

                val filters = mutableListOf<String>()
                for (i in 0 until 10) {
                    val fb = ByteArray(4)
                    if (buffer.remaining() >= 4) buffer.get(fb)
                    filters.add(fb.joinToString("") { String.format("%02X", it) })
                }
                info.canFilters = filters

                Log.d(TAG, "Parsed IotCanInfo successfully")
                return info
            } catch (e: BufferUnderflowException) {
                Log.e(TAG, "Error parsing IotCanInfo payload: ${e.message}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing IotCanInfo payload: ${e.message}", e)
                return null
            }
        }
    }
}
