package com.test.bafangcon.canble.transport

enum class CanBleTransportState {
    IDLE,
    DETECTING,
    SERVICE_FOUND,
    SERVICE_NOT_FOUND,
    SUBSCRIBING,
    SUBSCRIBED,
    HANDSHAKE_IN_PROGRESS,
    READY,
    FAILED
}
