package com.test.bafangcon

import android.content.Context
import android.os.Environment
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class BleRawNotificationStats(
    val totalNotifications: Int = 0,
    val totalBytes: Long = 0,
    val uniqueUuids: Set<String> = emptySet(),
    val lastUuid: String = "",
    val lastLength: Int = 0,
    val lastRawHex: String = "",
    val fileWriteErrors: Int = 0,
    val currentLogFilePath: String = "",
    val loggingEnabled: Boolean = false
)

class BleRawNotificationLogger {

    private var totalNotifications = 0
    private var totalBytes = 0L
    private val seenUuids = mutableSetOf<String>()
    private var lastUuid = ""
    private var lastLength = 0
    private var lastRawHex = ""
    private var fileWriteErrors = 0
    private var fileWriter: BufferedWriter? = null
    var currentLogFilePath: String = ""
        private set
    var loggingEnabled: Boolean = false
        private set

    fun startLogging(context: Context) {
        stopLogging()
        fileWriteErrors = 0
        try {
            val dir = resolveLogDirectory(context) ?: return
            if (!dir.exists()) dir.mkdirs()
            if (!dir.exists()) return

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "ble_raw_$timestamp.log")
            val writer = BufferedWriter(FileWriter(file, true))
            writer.write("timestamp_iso,uuid,length,rawHex")
            writer.newLine()
            writer.flush()
            fileWriter = writer
            loggingEnabled = true
            currentLogFilePath = file.absolutePath
        } catch (e: Exception) {
            fileWriteErrors++
            loggingEnabled = false
            currentLogFilePath = ""
        }
    }

    fun stopLogging() {
        try { fileWriter?.close() } catch (_: Exception) { }
        fileWriter = null
        loggingEnabled = false
    }

    fun log(uuid: UUID, data: ByteArray?) {
        if (data == null) return
        totalNotifications++
        totalBytes += data.size
        seenUuids.add(uuid.toString())
        lastUuid = uuid.toString()
        lastLength = data.size
        lastRawHex = data.joinToString(separator = "") { String.format("%02X", it) }
        if (loggingEnabled) writeToFile(formatCsvLine(uuid, data))
    }

    fun snapshot(): BleRawNotificationStats {
        return BleRawNotificationStats(
            totalNotifications = totalNotifications,
            totalBytes = totalBytes,
            uniqueUuids = seenUuids.toSet(),
            lastUuid = lastUuid,
            lastLength = lastLength,
            lastRawHex = lastRawHex,
            fileWriteErrors = fileWriteErrors,
            currentLogFilePath = currentLogFilePath,
            loggingEnabled = loggingEnabled
        )
    }

    fun clear() {
        totalNotifications = 0
        totalBytes = 0L
        seenUuids.clear()
        lastUuid = ""
        lastLength = 0
        lastRawHex = ""
        fileWriteErrors = 0
    }

    private fun resolveLogDirectory(context: Context): File? {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (publicDir != null) {
            val candidate = File(publicDir, "BafangCon/ble_raw")
            try {
                if (candidate.exists() || candidate.mkdirs()) return candidate
            } catch (_: Exception) { }
        }
        val appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        if (appDir != null) {
            val candidate = File(appDir, "BafangCon/ble_raw")
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

    private fun formatCsvLine(uuid: UUID, data: ByteArray): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
        val hex = data.joinToString(separator = "") { String.format("%02X", it) }
        return "$iso,$uuid,${data.size},$hex"
    }
}
