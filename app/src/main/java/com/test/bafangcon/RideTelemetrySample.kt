package com.test.bafangcon

import java.util.Locale

data class RideLogState(
    val isLogging: Boolean = false,
    val filePath: String? = null,
    val sampleCount: Int = 0
)

data class RideTelemetrySample(
    val timestampMs: Long,
    val elapsedMs: Long,
    val source: String,
    val socPercent: Int,
    val singleMileageRaw: Int,
    val totalMileageRaw: Int,
    val remainingMileageRaw: Int,
    val cadenceRpm: Int,
    val torqueRaw: Int,
    val speedRaw: Int,
    val currentRaw: Int,
    val voltageRaw: Int,
    val controllerTempC: Int,
    val motorTempC: Int,
    val boostState: Int,
    val speedLimitRaw: Int,
    val wheelDiameterRaw: Int,
    val tireCircumferenceRaw: Int,
    val caloriesRaw: Int,
    val currentGear: Int,
    val totalGear: Int,
    val wheelSpeed: Int,
    val wheelCounter: Int,
    val lastSensorTime: Int,
    val crankPulseCounter: Int,
    val motorVariableSpeedMasterGear: Int,
    val motorSpeedCurrentGear: Int,
    val batterySocPercent: Int?,
    val batteryVoltageRaw: Int?,
    val batteryCurrentRaw: Int?,
    val batteryTempC: Int?,
    val rawHex: String
) {
    val speedKmh: Double get() = speedRaw * 0.01
    val currentA: Double get() = currentRaw * 0.01
    val voltageV: Double get() = voltageRaw * 0.01
    val powerW: Double get() = voltageV * currentA
    val speedLimitKmh: Double get() = speedLimitRaw * 0.01
    val batteryVoltageV: Double? get() = batteryVoltageRaw?.let { it * 0.01 }
    val batteryCurrentA: Double? get() = batteryCurrentRaw?.let { it * 0.01 }
    val batteryPowerW: Double? get() = batteryVoltageV?.let { voltage ->
        batteryCurrentA?.let { current -> voltage * current }
    }

    fun toCsvLine(): String = listOf(
        timestampMs.toString(),
        elapsedMs.toString(),
        csv(source),
        fmt(speedKmh),
        fmt(currentA),
        fmt(voltageV),
        fmt(powerW),
        socPercent.toString(),
        currentGear.toString(),
        totalGear.toString(),
        cadenceRpm.toString(),
        torqueRaw.toString(),
        controllerTempC.toString(),
        motorTempC.toString(),
        boostState.toString(),
        fmt(speedLimitKmh),
        wheelDiameterRaw.toString(),
        tireCircumferenceRaw.toString(),
        caloriesRaw.toString(),
        wheelSpeed.toString(),
        wheelCounter.toString(),
        lastSensorTime.toString(),
        crankPulseCounter.toString(),
        motorVariableSpeedMasterGear.toString(),
        motorSpeedCurrentGear.toString(),
        singleMileageRaw.toString(),
        totalMileageRaw.toString(),
        remainingMileageRaw.toString(),
        batterySocPercent?.toString().orEmpty(),
        batteryVoltageV?.let { fmt(it) }.orEmpty(),
        batteryCurrentA?.let { fmt(it) }.orEmpty(),
        batteryPowerW?.let { fmt(it) }.orEmpty(),
        batteryTempC?.toString().orEmpty(),
        csv(rawHex)
    ).joinToString(",")

    fun summary(): String =
        "Ride ${fmt(speedKmh)}km/h ${fmt(currentA)}A ${fmt(voltageV)}V ${fmt(powerW)}W " +
            "SOC=$socPercent% gear=$currentGear/$totalGear cad=$cadenceRpm"

    companion object {
        const val CONTROLLER_TELEMETRY_OFFSET = 160
        const val CONTROLLER_TELEMETRY_LENGTH = 48

        fun csvHeader(): String = listOf(
            "timestamp_ms",
            "elapsed_ms",
            "source",
            "speed_kmh",
            "current_a",
            "voltage_v",
            "power_w",
            "soc_percent",
            "assist_level",
            "total_gears",
            "cadence_rpm",
            "torque_raw",
            "controller_temp_c",
            "motor_temp_c",
            "boost_state",
            "speed_limit_kmh",
            "wheel_diameter_raw",
            "tire_circumference_raw",
            "calories_raw",
            "wheel_speed",
            "wheel_counter",
            "last_sensor_time",
            "crank_pulse_counter",
            "motor_variable_speed_master_gear",
            "motor_speed_current_gear",
            "single_mileage_raw",
            "total_mileage_raw",
            "remaining_mileage_raw",
            "battery_soc_percent",
            "battery_voltage_v",
            "battery_current_a",
            "battery_power_w",
            "battery_temp_c",
            "raw_hex"
        ).joinToString(",")

        fun fromControllerInfo(
            info: ControllerInfo,
            batteryInfo: BatteryInfo?,
            startedAtMs: Long?,
            source: String,
            rawHex: String = info.rawData?.copyOfRangeSafe(
                CONTROLLER_TELEMETRY_OFFSET,
                CONTROLLER_TELEMETRY_OFFSET + CONTROLLER_TELEMETRY_LENGTH
            )?.toHexStringNoPrefix().orEmpty()
        ): RideTelemetrySample {
            val timestamp = System.currentTimeMillis()
            return RideTelemetrySample(
                timestampMs = timestamp,
                elapsedMs = startedAtMs?.let { timestamp - it } ?: 0L,
                source = source,
                socPercent = info.soc,
                singleMileageRaw = info.singleMileage,
                totalMileageRaw = info.totalMileage,
                remainingMileageRaw = info.emainingMileage,
                cadenceRpm = info.cadence,
                torqueRaw = info.moment,
                speedRaw = info.speed,
                currentRaw = info.electricCurrent,
                voltageRaw = info.voltage,
                controllerTempC = info.controllerTemperature,
                motorTempC = info.motorTemperature,
                boostState = info.boostState,
                speedLimitRaw = info.speedLimit,
                wheelDiameterRaw = info.wheelDiameter,
                tireCircumferenceRaw = info.tireCircumference,
                caloriesRaw = info.calories,
                currentGear = info.currentGear,
                totalGear = info.totalGear,
                wheelSpeed = info.wheelSpeed,
                wheelCounter = info.wheelCounter,
                lastSensorTime = info.lastTestSenserTime,
                crankPulseCounter = info.crankCadencePulseCounter,
                motorVariableSpeedMasterGear = info.motorVariableSpeedMasterGear,
                motorSpeedCurrentGear = info.motorSpeedCurrentGear,
                batterySocPercent = batteryInfo?.relativeCapacityPercent,
                batteryVoltageRaw = batteryInfo?.totalVoltage,
                batteryCurrentRaw = batteryInfo?.electricCurrent,
                batteryTempC = batteryInfo?.temperature,
                rawHex = rawHex
            )
        }

        fun fromControllerSegment(
            payload: ByteArray,
            batteryInfo: BatteryInfo?,
            startedAtMs: Long?,
            source: String
        ): RideTelemetrySample? {
            if (payload.size < CONTROLLER_TELEMETRY_LENGTH) return null
            val timestamp = System.currentTimeMillis()
            val motorTempRawOffset = 20
            return RideTelemetrySample(
                timestampMs = timestamp,
                elapsedMs = startedAtMs?.let { timestamp - it } ?: 0L,
                source = source,
                socPercent = payload.u16At(0),
                singleMileageRaw = payload.u16At(2),
                totalMileageRaw = payload.u16At(4),
                remainingMileageRaw = payload.u16At(6),
                cadenceRpm = payload.u16At(8),
                torqueRaw = payload.u16At(10),
                speedRaw = payload.u16At(12),
                currentRaw = payload.u16At(14),
                voltageRaw = payload.u16At(16),
                controllerTempC = payload.u16At(18) - 40,
                motorTempC = payload.u16At(motorTempRawOffset).let { raw ->
                    if (raw == 65535 || raw == 255) 255 else raw - 40
                },
                boostState = payload.u16At(22),
                speedLimitRaw = payload.u16At(24),
                wheelDiameterRaw = payload.u16At(26),
                tireCircumferenceRaw = payload.u16At(28),
                caloriesRaw = payload.u16At(30),
                currentGear = payload.u16At(32),
                totalGear = payload.u16At(34),
                wheelSpeed = payload.u16At(36),
                wheelCounter = payload.u16At(38),
                lastSensorTime = payload.u16At(40),
                crankPulseCounter = payload.u16At(42),
                motorVariableSpeedMasterGear = payload.u16At(44),
                motorSpeedCurrentGear = payload.u16At(46),
                batterySocPercent = batteryInfo?.relativeCapacityPercent,
                batteryVoltageRaw = batteryInfo?.totalVoltage,
                batteryCurrentRaw = batteryInfo?.electricCurrent,
                batteryTempC = batteryInfo?.temperature,
                rawHex = payload.toHexStringNoPrefix()
            )
        }

        private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

        private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

        private fun ByteArray.u16At(index: Int): Int {
            return (this[index].toInt() and 0xFF) or ((this[index + 1].toInt() and 0xFF) shl 8)
        }

        private fun ByteArray.toHexStringNoPrefix(): String =
            joinToString(separator = " ") { String.format("%02X", it) }

        private fun ByteArray.copyOfRangeSafe(fromIndex: Int, toIndex: Int): ByteArray? {
            if (fromIndex < 0 || toIndex > size || fromIndex >= toIndex) return null
            return copyOfRange(fromIndex, toIndex)
        }
    }
}
