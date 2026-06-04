package com.test.bafangcon

import android.util.Log
import com.test.bafangcon.utils.ByteBufferUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.BufferUnderflowException

// Based on BfPersonalizedInfo structure
data class PersonalizedInfo(
    // ... (keep existing fields)
    var controllerProtocolVersion: Int = 0,
    var motorStartingAngle: ShortArray = ShortArray(10),
    var accelerationSettings: ByteArray = ByteArray(10),
    var gearSpeedLimit: ByteArray = ByteArray(10),
    var gearCurrentLimit: ByteArray = ByteArray(10),
    var rawData: ByteArray? = null
    // ... (keep existing equals, hashCode, toString, and helper extension)
) {
    private fun ByteArray.toHexString(): String =
        joinToString(separator = " ") { String.format("%02X", it) }

    data class FieldInfo(
        val name: String,
        val offset: Int,
        val size: Int,
        val parser: (ByteBuffer) -> Any,
        val updater: (PersonalizedInfo, Any) -> Unit
    )

    fun updatePartial(partialPayload: ByteArray, startOffset: Int): Boolean {
        val fieldInfo = fieldOffsetMap[startOffset]
            ?: run {
                Log.w(TAG, "updatePartial: No field definition found for offset $startOffset")
                return false
            }

        if (partialPayload.size != fieldInfo.size) {
            Log.w(TAG, "updatePartial: Size mismatch for field '${fieldInfo.name}' at offset $startOffset. Expected ${fieldInfo.size}, got ${partialPayload.size}.")
            return false
        }

        return try {
            val buffer = ByteBuffer.wrap(partialPayload).order(ByteOrder.LITTLE_ENDIAN)
            val newValue = fieldInfo.parser(buffer)
            fieldInfo.updater(this, newValue)
            Log.d(TAG, "updatePartial: Updated field '${fieldInfo.name}' (offset $startOffset)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "updatePartial: Error parsing/updating field '${fieldInfo.name}' at offset $startOffset: ${e.message}", e)
            false
        }
    }

    companion object {
        private const val TAG = "PersonalizedInfo"

        fun parsePersonalizedInfoPayload(payload: ByteArray): PersonalizedInfo? {
            if (payload.size < BfMeterConfig.BfPersonalizedInfo_Total_Size) {
                Log.w(TAG, "Personalized payload too short: ${payload.size}, expected >= ${BfMeterConfig.BfPersonalizedInfo_Total_Size}")
                return null
            }
            val info = PersonalizedInfo()
            info.rawData = payload

            try {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

                val bytesToSkip = 64
                if (buffer.remaining() >= bytesToSkip) {
                    buffer.position(buffer.position() + bytesToSkip)
                } else {
                    Log.w(TAG, "Not enough data to skip initial $bytesToSkip bytes.")
                    return null
                }

                info.controllerProtocolVersion = ByteBufferUtils.getU8(buffer)

                val motorAngleBytes = ByteArray(20)
                if (buffer.remaining() >= motorAngleBytes.size) {
                    buffer.get(motorAngleBytes)
                    val angleBuffer = ByteBuffer.wrap(motorAngleBytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until 10) {
                        if (angleBuffer.remaining() >= 2) {
                            info.motorStartingAngle[i] = angleBuffer.short
                        } else {
                            Log.w(TAG, "Buffer underflow while reading motor angle index $i")
                            break
                        }
                    }
                } else { Log.w(TAG, "Not enough data for motorStartingAngle array.") }

                if (buffer.remaining() >= info.accelerationSettings.size) {
                    buffer.get(info.accelerationSettings)
                } else { Log.w(TAG, "Not enough data for accelerationSettings array.") }

                if (buffer.remaining() >= info.gearSpeedLimit.size) {
                    buffer.get(info.gearSpeedLimit)
                } else { Log.w(TAG, "Not enough data for gearSpeedLimit array.") }

                if (buffer.remaining() >= info.gearCurrentLimit.size) {
                    buffer.get(info.gearCurrentLimit)
                } else { Log.w(TAG, "Not enough data for gearCurrentLimit array.") }

                Log.d(TAG, "Successfully parsed PersonalizedInfo. Remaining buffer: ${buffer.remaining()}")
                return info

            } catch (e: BufferUnderflowException) {
                Log.e(TAG, "Error parsing PersonalizedInfo payload (BufferUnderflow): ${e.message}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing PersonalizedInfo payload: ${e.message}", e)
                return null
            }
        }

        val fieldOffsetMap: Map<Int, FieldInfo> = mapOf(
            64 to FieldInfo("controllerProtocolVersion", 64, 1,
                { bb -> ByteBufferUtils.getU8(bb) },
                { info, v -> info.controllerProtocolVersion = v as Int }),
            65 to FieldInfo("motorStartingAngle", 65, 20,
                { bb ->
                    val raw = ByteArray(20).also { bb.get(it) }
                    ShortArray(10) { i -> ((raw[i * 2].toInt() and 0xFF) or ((raw[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort() }
                },
                { info, v -> info.motorStartingAngle = v as ShortArray }),
            85 to FieldInfo("accelerationSettings", 85, 10,
                { bb -> ByteArray(10).also { bb.get(it) } },
                { info, v -> info.accelerationSettings = v as ByteArray }),
            95 to FieldInfo("gearSpeedLimit", 95, 10,
                { bb -> ByteArray(10).also { bb.get(it) } },
                { info, v -> info.gearSpeedLimit = v as ByteArray }),
            105 to FieldInfo("gearCurrentLimit", 105, 10,
                { bb -> ByteArray(10).also { bb.get(it) } },
                { info, v -> info.gearCurrentLimit = v as ByteArray })
        )
    }
}