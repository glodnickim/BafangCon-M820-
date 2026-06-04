package com.test.bafangcon

import android.util.Log
import com.test.bafangcon.utils.ByteBufferUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.BufferUnderflowException

data class BatteryInfo(
    var hardVersion: String = "",
    var softVersion: String = "",
    var model: String = "",
    var sn: String = "",
    var customerNo: String = "",
    var manufacturer: String = "",
    var totalCapacity: Int = 0,
    var residualCapacity: Int = 0,
    var relativeCapacityPercent: Int = 0,
    var absoluteCapacityPercent: Int = 0,
    var electricCurrent: Int = 0,
    var totalVoltage: Int = 0,
    var temperature: Int = 0,
    var heating: Int = 0,
    var charging: Int = 0,
    var using: Int = 0,
    var batteryConcatenateCount: Int = 0,
    var batteryParallelCount: Int = 0,
    var designCapacity: Int = 0,
    var cycles: Int = 0,
    var maxNoChargeTime: Int = 0,
    var currentNoChargeTime: Int = 0,
    var cellData: String = "",
    var maxChargeVoltage: Long = 0L,
    var maxChargeElectric: Long = 0L,

    var rawData: ByteArray? = null
) {
    companion object {
        private const val TAG = "BatteryInfo"
        private const val TOTAL_SIZE = 244

        fun parseBatteryInfoPayload(payload: ByteArray): BatteryInfo? {
            if (payload.size < TOTAL_SIZE) {
                Log.w(TAG, "Payload too short: ${payload.size}, expected >= $TOTAL_SIZE")
                return null
            }
            val info = BatteryInfo()
            info.rawData = payload

            try {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

                info.hardVersion = ByteBufferUtils.getAsciiString(buffer, 24)
                info.softVersion = ByteBufferUtils.getAsciiString(buffer, 24)
                info.model = ByteBufferUtils.getAsciiString(buffer, 24)
                info.sn = ByteBufferUtils.getAsciiString(buffer, 40)
                info.customerNo = ByteBufferUtils.getAsciiString(buffer, 16)
                info.manufacturer = ByteBufferUtils.getAsciiString(buffer, 16)

                info.totalCapacity = ByteBufferUtils.getU16(buffer)
                info.residualCapacity = ByteBufferUtils.getU16(buffer)
                info.relativeCapacityPercent = ByteBufferUtils.getU16(buffer)
                info.absoluteCapacityPercent = ByteBufferUtils.getU16(buffer)
                info.electricCurrent = buffer.short.toInt()
                info.totalVoltage = ByteBufferUtils.getU16(buffer)
                info.temperature = ByteBufferUtils.getU16(buffer) - 40
                info.heating = ByteBufferUtils.getU8(buffer)
                info.charging = ByteBufferUtils.getU8(buffer)
                info.using = ByteBufferUtils.getU8(buffer)

                if (buffer.remaining() >= 1) buffer.position(buffer.position() + 1)

                info.batteryConcatenateCount = ByteBufferUtils.getU8(buffer)
                info.batteryParallelCount = ByteBufferUtils.getU8(buffer)
                info.designCapacity = ByteBufferUtils.getU16(buffer)
                info.cycles = ByteBufferUtils.getU16(buffer)
                info.maxNoChargeTime = ByteBufferUtils.getU16(buffer)
                info.currentNoChargeTime = ByteBufferUtils.getU16(buffer)

                val cellBytes = ByteArray(64)
                if (buffer.remaining() >= 64) {
                    buffer.get(cellBytes)
                }
                info.cellData = cellBytes.joinToString("") { String.format("%02X", it) }

                info.maxChargeVoltage = ByteBufferUtils.getU32(buffer)
                info.maxChargeElectric = ByteBufferUtils.getU32(buffer)

                Log.d(TAG, "Parsed BatteryInfo successfully")
                return info
            } catch (e: BufferUnderflowException) {
                Log.e(TAG, "Error parsing BatteryInfo payload: ${e.message}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing BatteryInfo payload: ${e.message}", e)
                return null
            }
        }
    }
}
