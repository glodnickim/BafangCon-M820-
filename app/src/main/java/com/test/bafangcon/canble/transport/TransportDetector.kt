package com.test.bafangcon.canble.transport

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.util.Log
import java.util.UUID

object TransportDetector {

    private const val TAG = "CanBleLogger"

    val CAN_SERVICE_UUID: UUID = UUID.fromString("49d554a6-76b1-11e9-8f9e-2a86e4085a59")
    val CAN_CONTROL_UUID: UUID = UUID.fromString("49d55e56-76b1-11e9-8f9e-2a86e4085a59")
    val CAN_RX_UUID: UUID = UUID.fromString("49d5571d-76b1-11e9-8f9e-2a86e4085a59")
    val CAN_TX_UUID: UUID = UUID.fromString("49d5571c-76b1-11e9-8f9e-2a86e4085a59")
    val CAN_FW_UUID: UUID = UUID.fromString("49d55ce4-76b1-11e9-8f9e-2a86e4085a59")

    data class CanServiceInfo(
        val controlCharacteristic: BluetoothGattCharacteristic,
        val rxCharacteristic: BluetoothGattCharacteristic,
        val txCharacteristic: BluetoothGattCharacteristic?
    )

    fun detect(services: List<BluetoothGattService>?): CanServiceInfo? {
        if (services == null) {
            safeLog("[CANBLE] service not found — no services discovered")
            return null
        }

        val canService = services.firstOrNull { it.uuid == CAN_SERVICE_UUID }
        if (canService == null) {
            safeLog("[CANBLE] service not found")
            return null
        }

        val control = canService.getCharacteristic(CAN_CONTROL_UUID)
        val rx = canService.getCharacteristic(CAN_RX_UUID)
        val tx = canService.getCharacteristic(CAN_TX_UUID)

        if (control == null || rx == null) {
            safeLog("[CANBLE] service found but missing required characteristics")
            return null
        }

        safeLog("[CANBLE] service found")
        return CanServiceInfo(
            controlCharacteristic = control,
            rxCharacteristic = rx,
            txCharacteristic = tx
        )
    }

    private fun safeLog(msg: String) {
        try { Log.i(TAG, msg) } catch (_: RuntimeException) { }
    }
}
