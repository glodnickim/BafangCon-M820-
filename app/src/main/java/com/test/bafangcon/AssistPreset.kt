package com.test.bafangcon

data class AssistPreset(
    val name: String,
    val gearSpeedLimit: List<Int>,
    val gearCurrentLimit: List<Int>,
    val motorStartingAngle: List<Int>,
    val accelerationSettings: List<Int>,
    val protocolVersion: Int
) {
    fun toPersonalizedInfo(): PersonalizedInfo {
        val info = PersonalizedInfo()
        info.controllerProtocolVersion = protocolVersion
        for (i in 0 until 10) {
            if (i < motorStartingAngle.size) {
                info.motorStartingAngle[i] = motorStartingAngle[i].toShort()
            }
            if (i < accelerationSettings.size) {
                info.accelerationSettings[i] = accelerationSettings[i].toByte()
            }
            if (i < gearSpeedLimit.size) {
                info.gearSpeedLimit[i] = gearSpeedLimit[i].toByte()
            }
            if (i < gearCurrentLimit.size) {
                info.gearCurrentLimit[i] = gearCurrentLimit[i].toByte()
            }
        }
        return info
    }

    companion object {
        fun fromPersonalizedInfo(name: String, info: PersonalizedInfo): AssistPreset {
            return AssistPreset(
                name = name,
                gearSpeedLimit = info.gearSpeedLimit.map { it.toInt() and 0xFF },
                gearCurrentLimit = info.gearCurrentLimit.map { it.toInt() and 0xFF },
                motorStartingAngle = info.motorStartingAngle.map { it.toInt() },
                accelerationSettings = info.accelerationSettings.map { it.toInt() and 0xFF },
                protocolVersion = info.controllerProtocolVersion
            )
        }
    }
}
