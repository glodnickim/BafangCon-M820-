# Roadmap

## v0.041 — Legacy

**Goal:** Stabilne AA55/NUS połączenie, edycja asyst, logowanie przejazdów.

**Status:** Stabilny. Wszystkie 7 typów danych odczytane. Zapis asyst, sterownika, wyświetlacza. CSV logowanie.

**Zakres:**
- BLE scan + auto-connect
- AA55 authentication + heartbeat
- Read/write wszystkich typów danych
- Ride logging do CSV
- Preset manager
- 5 fragmentów UI

---

## v0.042 — Current

**Goal:** CAN BLE models + release APK with verified compilation.

**Status:** Modele CAN BLE kompilują się. Release APK zbudowany. Implementacja transportu nie rozpoczęta.

**Zakres:**
- 5 modeli CAN BLE (`CanBleFrame`, `CanBleSessionState`, `RawCanNotification`, `RecordedCanNotification`, `RecordedCanSession`)
- 7 dokumentów w `docs/` (PROJECT_STATUS, ARCHITECTURE, ROADMAP, DECISIONS, PHASE1_PLAN, CONTRIBUTING, RELEASE_PROCESS)
- Release APK v0.042 (~2.7 MB)

---

## v0.043 — CAN BLE: Crypto + Frame Processing

**Goal:** Zaimplementować podstawowe komponenty CAN BLE bez transportu.

**Zakres:**
- `CryptoProvider` interface + implementacje
- `CanBleFrameExtractor` — wyodrębnianie ramek CAN z notyfikacji
- `CanBleFrameFilter` — filtrowanie ramek (echo, dest, op)
- `CanBleLogger` — logowanie + plik debug + metryki
- Testy jednostkowe ekstraktora i filtru (z replay)

**Ryzyka:**
- `FallbackCryptoProvider` fixed key (0x01) może być niepoprawny
- AES padding requirements dla ECB/NoPadding

**Zależności:**
- Modele CAN BLE już istnieją (v0.042)

---

## v0.044 — CAN BLE: Handshake + Transport

**Goal:** Połączyć się z CAN BLE service, wykonać handshake, odebrać notyfikacje.

**Zakres:**
- `CanBleHandshake` — RAND1→RAND2→key derivation→MTU→READY
- `CanBleTransport` — GATT service discovery, subscribe, write
- Hooki w `BleRepository` dla CAN BLE service discovery i notification dispatch
- `RecordedCanSession` — nagrywanie surowych notyfikacji
- Logowanie do pliku `canble_debug_<timestamp>.log`

**Ryzyka:**
- CAN BLE może nie być dostępne na niektórych firmware
- Handshake może różnić się między M820 a M600
- Konkurencja GATT write między AA55 a CAN BLE

**Kryteria sukcesu:**
- Handshake kompletny w <10s
- Notyfikacje CAN RX przychodzą
- AA55/NUS nadal działa

---

## v0.045 — CAN BLE: Read-Only Telemetry

**Goal:** Odtwarzać telemetrię z CAN BLE i wyświetlać w formie debug.

**Zakres:**
- Podstawowe parsowanie payloadu CAN (func/index → wartość)
- Merge z AA55 źródłem (priorytet CAN BLE)
- Debug dashboard z surowymi wartościami CAN
- Żadnych writable commands

**Ryzyka:**
- Format payloadu może różnić się między wersjami firmware
- Niektóre indeksy CAN mogą nie być broadcastowane

**Kryteria sukcesu:**
- Speed, current, voltage, SOC z CAN BLE zgadzają się z wyświetlaczem
- CAN BLE wartości zastępują AA55 w merger

---

## v0.046 — CAN BLE: Telemetry Merger

**Goal:** Formalny system łączenia danych z dwóch źródeł.

**Zakres:**
- `TelemetrySnapshot` z `TypedField<value, source>`
- Merge priority: CAN_BLE > AA55 > CALCULATED > UNKNOWN
- StateFlow dla telemetrii (jeden źródłowy flow dla UI)
- Wypchnięcie starego systemu (bezpośrednie StateFlow modele) do warstwy legacy

**Ryzyka:**
- Refaktoryzacja może wpłynąć na istniejące UI
- Konieczność zmiany `DeviceViewModel` i fragmentów

---

## v0.047 — CAN BLE: Telemetry Dashboard

**Goal:** UI dla CAN BLE telemetrii.

**Zakres:**
- Nowy ekran dashboard z żywymi danymi CAN
- Wykresy (speed, current, power w czasie)
- Wskaźniki SOC, temperatury, PAS
- Przełączanie między AA55 a CAN BLE źródłem

**Ryzyka:**
- Duża zmiana UI
- Kompatybilność z istniejącymi fragmentami

---

## Future Releases

### Expert Mode
- Raw CAN frame inspector (hex dump z indeksami)
- Filtrowanie po CAN index / source node
- Export surowych danych CAN
- Replay session z pliku

### CAN Write Support
- Light on/off przez CAN
- PAS level change przez CAN
- Parametry sterownika przez CAN

### Advanced Diagnostics
- Graphing over time (MPAndroidChart)
- CSV/JSON export
- BLE signal strength monitoring
- CAN bus load analysis

### Platform
- GitHub Actions CI
- Obfuscation (ProGuard/R8)
- Unit test coverage
- Integration tests with replay

---

## Version History

| Wersja | Data | Główna zmiana |
|---|---|---|
| v0.002–0.025 | 2026-05 | Initial development, AA55 protocol, auth |
| v0.026–0.034 | 2026-05 | UI fragments, data models, settings editing |
| v0.035–0.037 | 2026-06 | Ride logging, telemetry segment parsing |
| v0.038–0.040 | 2026-06 | CSV export, SAF integration, system info |
| **v0.041** | **2026-06-08** | **CAN BLE models + documentation** |
| **v0.042** | **2026-06-08** | **CAN BLE models (release APK), 7 docs** |
| v0.043 | plan | Crypto, extractor, filter, logger |
| v0.044 | plan | Handshake, transport, BleRepository hooks |
| v0.045 | plan | Read-only telemetry, debug display |
| v0.046 | plan | Telemetry merger |
| v0.047 | plan | Telemetry dashboard |
