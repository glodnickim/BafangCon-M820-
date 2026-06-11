package com.test.bafangcon

data class BleRawNotification(
    val timestampMs: Long,
    val uuid: String,
    val length: Int,
    val rawHex: String
)
