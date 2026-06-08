package com.test.bafangcon.canble.model

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordedCanSession(private val sessionId: String = defaultSessionId()) {

    private val notifications = mutableListOf<RecordedCanNotification>()
    private var isRecording = false

    val notificationCount: Int
        get() = notifications.size

    val isEmpty: Boolean
        get() = notifications.isEmpty()

    fun startRecording() {
        isRecording = true
    }

    fun stopRecording() {
        isRecording = false
    }

    fun record(
        notification: RawCanNotification,
        aUMode: Int,
        decryptedHex: String? = null
    ) {
        if (!isRecording) return
        notifications.add(
            RecordedCanNotification(
                timestampMs = notification.timestampMs,
                encryptedHex = notification.rawHex,
                decryptedHex = decryptedHex,
                sourceUuid = notification.uuid.toString(),
                aUMode = aUMode
            )
        )
    }

    fun recordDecrypted(
        encryptedHex: String,
        decryptedHex: String,
        uuid: java.util.UUID,
        aUMode: Int
    ) {
        if (!isRecording) return
        notifications.add(
            RecordedCanNotification(
                timestampMs = System.currentTimeMillis(),
                encryptedHex = encryptedHex,
                decryptedHex = decryptedHex,
                sourceUuid = uuid.toString(),
                aUMode = aUMode
            )
        )
    }

    fun replay(): List<RecordedCanNotification> {
        return notifications.toList()
    }

    fun clear() {
        notifications.clear()
    }

    fun saveToFile(context: Context): String {
        val dir = File(context.filesDir, SESSION_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "canble_session_$sessionId.json")
        val content = notifications.joinToString("\n") { it.toJsonLine() }
        file.writeText(content)
        return file.absolutePath
    }

    fun loadFromFile(context: Context, filePath: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false
        val lines = file.readLines()
        val loaded = mutableListOf<RecordedCanNotification>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val notification = RecordedCanNotification.fromJsonLine(trimmed)
            if (notification != null) {
                loaded.add(notification)
            }
        }
        notifications.clear()
        notifications.addAll(loaded)
        return true
    }

    fun exportToDump(context: Context): Uri? {
        val path = saveToFile(context)
        return Uri.fromFile(File(path))
    }

    override fun toString(): String {
        return "RecordedCanSession(id=$sessionId, notifications=$notificationCount, recording=$isRecording)"
    }

    companion object {
        private const val SESSION_DIR = "canble-sessions"

        private fun defaultSessionId(): String {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            return sdf.format(Date())
        }

        fun listSavedSessions(context: Context): List<String> {
            val dir = File(context.filesDir, SESSION_DIR)
            if (!dir.exists()) return emptyList()
            return dir.listFiles()
                ?.filter { it.extension == "json" }
                ?.map { it.absolutePath }
                ?: emptyList()
        }

        fun deleteSession(context: Context, filePath: String): Boolean {
            return File(filePath).delete()
        }
    }
}
