package com.test.bafangcon // Adjust package name

import java.util.UUID

object BleConstants {
    // Nordic UART Service (NUS) UUID
    val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    // NUS RX Characteristic: Client writes commands TO the peripheral HERE
    val UART_WRITE_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    // NUS TX Characteristic: Client receives notifications FROM the peripheral HERE
    val UART_NOTIFY_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    // Client Characteristic Configuration Descriptor (Standard UUID, used for TX characteristic)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Consider adding device name prefixes if reliable, e.g.
    // val TARGET_DEVICE_NAME_PREFIX = "YourBikeName"
}

// --- BleConnectionState and DiscoveredBluetoothDevice remain the same ---
enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    FAILED
}

enum class BleAuthState {
    NOT_AUTHENTICATED,
    AUTHENTICATING,
    AUTHENTICATED,
    AUTH_FAILED
}

data class DiscoveredBluetoothDevice(
    val name: String?,
    val address: String,
    val rssi: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DiscoveredBluetoothDevice
        return address == other.address
    }
    override fun hashCode(): Int = address.hashCode()
}