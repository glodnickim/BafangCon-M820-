package com.test.bafangcon

import android.content.Context
import android.os.Environment
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Aa55RawStats(
    val rxFrames: Int = 0,
    val txFrames: Int = 0,
    val unknownFrames: Int = 0,
    val lastRxCommand: String = "",
    val lastTxCommand: String = "",
    val lastFrameHex: String = "",
    val isLogging: Boolean = false,
    val currentLogFilePath: String = "",
    val fileWriteErrors: Int = 0
)

class Aa55RawLogger {

    private val frames = mutableListOf<Aa55RawFrame>()
    private var rxFrames = 0
    private var txFrames = 0
    private var unknownFrames = 0
    private var lastRxCommand = ""
    private var lastTxCommand = ""
    private var lastFrameHex = ""
    private var fileWriteErrors = 0

    private var fileWriter: BufferedWriter? = null
    var isLogging: Boolean = false
        private set
    var currentLogFilePath: String = ""
        private set

    private val knownCommands = mapOf(
        0x10 to "AUTH",
        0xA1 to "IOT_CONFIG",
        0xA2 to "IOT_CAN",
        0xA3 to "CONTROLLER",
        0xA4 to "BATTERY",
        0xA5 to "METER",
        0xA7 to "SENSOR",
        0xA9 to "PERSONALIZED"
    )

    fun startFileLogging(context: Context) {
        stopFileLogging()
        fileWriteErrors = 0
        try {
            val dir = resolveLogDirectory(context) ?: return
            if (!dir.exists()) dir.mkdirs()
            if (!dir.exists()) return

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "aa55_raw_$timestamp.log")
            val writer = BufferedWriter(FileWriter(file, true))
            writer.write("timestamp_iso,direction,length,commandGuess,knownCommand,rawHex")
            writer.newLine()
            writer.flush()
            fileWriter = writer
            isLogging = true
            currentLogFilePath = file.absolutePath
        } catch (e: Exception) {
            fileWriteErrors++
            isLogging = false
            currentLogFilePath = ""
        }
    }

    fun stopFileLogging() {
        try {
            fileWriter?.close()
        } catch (_: IOException) { }
        fileWriter = null
        isLogging = false
    }

    fun logTx(data: ByteArray) {
        val (name, known) = guessCommand(data, isTx = true)
        if (!known) unknownFrames++
        txFrames++
        lastTxCommand = name
        lastFrameHex = data.toHexString()
        frames.add(
            Aa55RawFrame(
                timestampMs = System.currentTimeMillis(),
                direction = "TX",
                length = data.size,
                rawHex = data.toHexString(),
                commandGuess = name,
                knownCommand = known
            )
        )
        if (isLogging) writeToFile(formatCsvLine("TX", data, name, known))
    }

    fun logRx(data: ByteArray) {
        val (name, known) = guessCommand(data, isTx = false)
        if (!known) unknownFrames++
        rxFrames++
        lastRxCommand = name
        lastFrameHex = data.toHexString()
        frames.add(
            Aa55RawFrame(
                timestampMs = System.currentTimeMillis(),
                direction = "RX",
                length = data.size,
                rawHex = data.toHexString(),
                commandGuess = name,
                knownCommand = known
            )
        )
        if (isLogging) writeToFile(formatCsvLine("RX", data, name, known))
    }

    fun snapshot(): Aa55RawStats {
        return Aa55RawStats(
            rxFrames = rxFrames,
            txFrames = txFrames,
            unknownFrames = unknownFrames,
            lastRxCommand = lastRxCommand,
            lastTxCommand = lastTxCommand,
            lastFrameHex = lastFrameHex,
            isLogging = isLogging,
            currentLogFilePath = currentLogFilePath,
            fileWriteErrors = fileWriteErrors
        )
    }

    fun clear() {
        frames.clear()
        rxFrames = 0
        txFrames = 0
        unknownFrames = 0
        lastName = ""
        lastRxCommand = ""
        lastTxCommand = ""
        lastFrameHex = ""
        fileWriteErrors = 0
    }

    private fun resolveLogDirectory(context: Context): File? {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (publicDir != null) {
            val candidate = File(publicDir, "BafangCon/aa55_raw")
            try {
                if (candidate.exists() || candidate.mkdirs()) return candidate
            } catch (_: Exception) { }
        }
        val appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        if (appDir != null) {
            val candidate = File(appDir, "BafangCon/aa55_raw")
            try {
                if (candidate.exists() || candidate.mkdirs()) return candidate
            } catch (_: Exception) { }
        }
        return null
    }

    private fun writeToFile(line: String) {
        try {
            fileWriter?.apply {
                write(line)
                newLine()
                flush()
            }
        } catch (e: Exception) {
            fileWriteErrors++
        }
    }

    private fun formatCsvLine(direction: String, data: ByteArray, commandGuess: String, knownCommand: Boolean): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
        val hex = data.joinToString(separator = "") { String.format("%02X", it) }
        return "$iso,$direction,${data.size},$commandGuess,$knownCommand,$hex"
    }

    private var lastName = ""

    private fun guessCommand(data: ByteArray, isTx: Boolean): Pair<String, Boolean> {
        if (data.size < 5) return "N/A" to false
        val cmdIndex = if (isTx) 4 else 3
        val cmd = data[cmdIndex].toInt() and 0xFF
        val name = knownCommands[cmd]
        val result = name ?: "0x${String.format("%02X", cmd)}"
        lastName = result
        return result to (name != null)
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = " ", prefix = "") { String.format("%02X", it) }
}
