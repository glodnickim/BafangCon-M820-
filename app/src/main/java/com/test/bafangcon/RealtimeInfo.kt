package com.test.bafangcon

/**
 * Telemetria na żywo z ramki broadcast 0x06 (typ 0x10, op 0x06), nadawanej przez wyświetlacz
 * co ~0,7 s. Aplikacja wcześniej ją odrzucała ("Unknown Type") — patrz analiza w planie
 * `logs/aa55_raw_20260611_183841.log`.
 *
 * Mapa pól ustalona z logu (offsety liczone w payloadzie, payload[0] = data[7] ramki):
 *  - [5]    assistLevel / bieg (0–4)
 *  - [6]    liczba biegów (=5)
 *  - [7]    SOC % (kotwica: zgodne z A3)
 *  - [9–10] czujnik momentu (torque, 12-bit ADC 0–4095) — POTWIERDZONE (limit ~15A → za duże na prąd/moc)
 *  - [11–12] licznik całkowity / ODO
 *  - [15–16] licznik trip (nadawany też na [23–24])
 *  - [17–18] zasięg pozostały (0xFFFF = N/A na postoju)
 *  - [19–20] nieznane, wolno rosnące (~2978)
 *  - [21–22] licznik A (8-bit wrap)
 *  - [28–29] licznik B (monotoniczny)
 *  - [35]    bateryjne? malejące (~119)
 *
 * Pola nieustalone wystawiamy surowo (do analizy na ekranie SystemInfo podczas realnej jazdy).
 * Jednostki przeliczeń są przybliżone — patrz otwarte pytania w planie.
 */
data class RealtimeInfo(
    val assistLevel: Int,
    val totalGears: Int,
    val soc: Int,
    val torqueRaw: Int,
    val odometerRaw: Int,
    val tripRaw: Int,
    val remainingRangeRaw: Int,
    val field19Raw: Int,
    val counterA: Int,
    val counterB: Int,
    val battByte35: Int,
    val rawHex: String
) {
    val rangeAvailable: Boolean get() = remainingRangeRaw != 0xFFFF

    // Przeliczenia przybliżone (0.01 jedn.) — interpretacja km do potwierdzenia logiem z jazdy.
    val odometerKm: Double get() = odometerRaw * 0.01
    val tripKm: Double get() = tripRaw * 0.01
    val rangeKm: Double? get() = if (rangeAvailable) remainingRangeRaw * 0.01 else null

    companion object {
        const val MIN_PAYLOAD_SIZE = 39

        fun parse(payload: ByteArray): RealtimeInfo? {
            if (payload.size < MIN_PAYLOAD_SIZE) return null
            return RealtimeInfo(
                assistLevel = payload.u8(5),
                totalGears = payload.u8(6),
                soc = payload.u8(7),
                torqueRaw = payload.u16(9),
                odometerRaw = payload.u16(11),
                tripRaw = payload.u16(15),
                remainingRangeRaw = payload.u16(17),
                field19Raw = payload.u16(19),
                counterA = payload.u16(21),
                counterB = payload.u16(28),
                battByte35 = payload.u8(35),
                rawHex = payload.toHexNoPrefix()
            )
        }

        private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
        private fun ByteArray.u16(i: Int): Int =
            (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)

        private fun ByteArray.toHexNoPrefix(): String =
            joinToString(separator = " ") { String.format("%02X", it) }
    }
}
