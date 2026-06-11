package com.test.bafangcon.canble.process

import com.test.bafangcon.canble.model.CanBleFrame

object CanBleFrameExtractor {

    fun extract(data: ByteArray): List<CanBleFrame> {
        if (data.isEmpty()) return emptyList()

        val frames = mutableListOf<CanBleFrame>()
        var i = 0

        while (i < data.size) {
            if (i + HEADER_SIZE > data.size) break

            val sourceNode = data[i].toInt() and 0xFF
            val destNode = (data[i + 1].toInt() shr 3) and 0x1F
            val op = data[i + 1].toInt() and 0x07
            val func = data[i + 2].toInt() and 0xFF
            val index = data[i + 3].toInt() and 0xFF
            val payloadLen = data[i + 4].toInt() and 0xFF

            val frameSize = HEADER_SIZE + payloadLen
            if (i + frameSize > data.size) break

            val end = i + frameSize
            val payload = data.copyOfRange(i + HEADER_SIZE, end)
            val rawHex = data.copyOfRange(i, end).joinToString("") { "%02x".format(it) }

            frames.add(
                CanBleFrame(
                    sourceNode = sourceNode,
                    destNode = destNode,
                    op = op,
                    func = func,
                    index = index,
                    payload = payload,
                    payloadLen = payloadLen,
                    rawHex = rawHex
                )
            )

            i = end
        }

        return frames
    }

    private const val HEADER_SIZE = 5
}
