package com.test.bafangcon.canble.model

data class RecordedCanNotification(
    val timestampMs: Long,
    val encryptedHex: String,
    val decryptedHex: String? = null,
    val sourceUuid: String,
    val aUMode: Int
) {
    val hasDecrypted: Boolean
        get() = decryptedHex != null

    fun toJsonLine(): String {
        val dec = decryptedHex?.let { "\"$it\"" } ?: "null"
        return """{"ts":$timestampMs,"enc":"$encryptedHex","dec":$dec,"uuid":"$sourceUuid","aU":$aUMode}"""
    }

    companion object {
        fun fromJsonLine(line: String): RecordedCanNotification? {
            return try {
                val json = line.trim()
                if (!json.startsWith("{") || !json.endsWith("}")) return null
                val ts = extractLong(json, "\"ts\":")
                val enc = extractString(json, "\"enc\":")
                val dec = extractNullableString(json, "\"dec\":")
                val uuid = extractString(json, "\"uuid\":")
                val aU = extractInt(json, "\"aU\":")
                if (ts == null || enc == null || uuid == null || aU == null) return null
                RecordedCanNotification(
                    timestampMs = ts,
                    encryptedHex = enc,
                    decryptedHex = dec,
                    sourceUuid = uuid,
                    aUMode = aU
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun extractLong(json: String, key: String): Long? {
            val start = json.indexOf(key)
            if (start == -1) return null
            val valueStart = start + key.length
            val end = json.indexOfAny(charArrayOf(',', '}'), valueStart)
            if (end == -1) return null
            return json.substring(valueStart, end).trim().toLongOrNull()
        }

        private fun extractInt(json: String, key: String): Int? {
            val start = json.indexOf(key)
            if (start == -1) return null
            val valueStart = start + key.length
            val end = json.indexOfAny(charArrayOf(',', '}'), valueStart)
            if (end == -1) return null
            return json.substring(valueStart, end).trim().toIntOrNull()
        }

        private fun extractString(json: String, key: String): String? {
            val start = json.indexOf(key)
            if (start == -1) return null
            val quoteStart = json.indexOf('"', start + key.length)
            if (quoteStart == -1) return null
            val quoteEnd = json.indexOf('"', quoteStart + 1)
            if (quoteEnd == -1) return null
            return json.substring(quoteStart + 1, quoteEnd)
        }

        private fun extractNullableString(json: String, key: String): String? {
            val start = json.indexOf(key)
            if (start == -1) return null
            val valueStart = start + key.length
            if (json.regionMatches(valueStart, "null", 0, 4)) return null
            val quoteStart = json.indexOf('"', valueStart)
            if (quoteStart == -1) return null
            val quoteEnd = json.indexOf('"', quoteStart + 1)
            if (quoteEnd == -1) return null
            return json.substring(quoteStart + 1, quoteEnd)
        }
    }
}
