package com.test.bafangcon

data class Aa55RawFrame(
    val timestampMs: Long,
    val direction: String,
    val length: Int,
    val rawHex: String,
    val commandGuess: String,
    val knownCommand: Boolean
)
