# Technical Decisions

## 2026-06-06 — CAN BLE additive architecture

**Decision:** CAN BLE will be implemented as a completely separate, additive subsystem. It will live in its own `canble/` package, use its own GATT characteristics, and have its own command queue. It will not modify or interoperate with the existing AA55/NUS code path.

**Reason:**
- The existing AA55/NUS code (BleRepository.kt, 1937 lines) is complex and fragile.
- CAN BLE uses different UUIDs, different frame formats, and different encryption.
- Merging the two would create high risk of breaking the working AA55 path.
- Additive architecture allows independent testing and rollout.

**Consequence:**
- BleRepository needs minimal hooks (service discovery, notification dispatch) but no refactoring.
- CAN BLE code is independently testable.
- Some duplication of GATT operations is inevitable.
- Future telemetry merger will need to read both data sources.

---

## 2026-06-06 — AA55 remains primary transport

**Decision:** AA55/NUS will remain the primary data transport indefinitely. CAN BLE is an additional data source, not a replacement.

**Reason:**
- AA55 works reliably for all read and write operations (7 data types, authentication, settings).
- CAN BLE telemetry is broadcast-only (read-only until we implement CAN write).
- The app has invested heavily in AA55 write support (assist settings, controller parameters, meter settings).
- Most users only need AA55; CAN BLE adds value only for real-time telemetry.

**Consequence:**
- CAN BLE implementation focuses on read-only telemetry first.
- Write operations remain exclusively AA55.
- UI will eventually show data from both sources with source indicators.
- CAN BLE transport is optional; app works without it.

---

## 2026-06-06 — Read-only first approach

**Decision:** CAN BLE implementation will begin with read-only telemetry reception. No CAN write commands will be implemented in Phase 1.

**Reason:**
- Read-only is safer — no risk of accidentally sending malformed CAN commands to the bike.
- Read-only allows validation of the entire CAN BLE stack (handshake, encryption, parsing) before writable commands are added.
- The decompiled official app code (PlBleService.java) shows CAN WRITE commands, but we need to understand the exact format and encryption before implementing writes.

**Consequence:**
- Phase 1: handshake + notification reception + frame extraction + logging.
- Phase 2: telemetry parsing + merging.
- Phase 3+: CAN write commands (light, PAS, parameters).
- `CanBleTransport` will have `sendDataRaw()` for future use but Phase 1 won't call it.

---

## 2026-06-06 — Separate command queues

**Decision:** CAN BLE will use its own command queue, separate from the AA55 command queue in BleRepository.

**Reason:**
- CAN BLE uses different GATT characteristics (UUID_CAN_TRANSMIT_TX vs UUID_UART_WRITE).
- CAN BLE has a separate control channel (UUID_CAN_CONTROL) for handshake/encryption commands.
- AA55 queue is tightly coupled to NUS write characteristic.
- Sharing a queue would require significant refactoring.

**Consequence:**
- Minimal BleRepository changes (just service discovery and notification routing).
- CanBleTransport implements its own simple command sending.
- No risk of AA55 commands blocking CAN BLE and vice versa.
- Two independent GATT write flows — the Android BLE stack serializes writes per connection.

---

## 2026-06-06 — Telemetry before writes

**Decision:** CAN BLE read telemetry will be implemented before any CAN write commands.

**Reason:**
- Telemetry is the primary value of CAN BLE (real-time speed, current, voltage, SOC, PAS).
- CAN BLE writes (light, PAS, parameters) duplicate existing AA55 functionality.
- Read-only validation proves the transport works end-to-end.
- Writing CAN commands without understanding the exact protocol risks damaging bike settings.

**Consequence:**
- Phase 1 & 2 focus entirely on receiving and parsing telemetry.
- CAN write support is deferred indefinitely.
- The telemetry merger (Phase 3+) prioritizes CAN BLE data over AA55 for live values.

---

## 2026-06-06 — No UI changes in Phase 1

**Decision:** Phase 1 CAN BLE implementation will produce no user interface changes. Data will be logged to files and logcat only.

**Reason:**
- Phase 1 is purely validation — verify handshake, encryption, frame extraction work on real hardware.
- UI changes are premature until we know what data is available and at what rate.
- Existing UI is complex (1058-line MainFragment) and should not be touched during validation.

**Consequence:**
- Phase 1 output: debug log files (`canble_debug_*.log`) and recorded sessions (`canble_session_*.json`).
- No new fragments, no StateFlow integration, no ViewModel changes.
- UI integration starts in Phase 3 (telemetry merger) or Phase 4 (dashboard).

---

## 2026-06-08 — Models before transport

**Decision:** CAN BLE data models are implemented before any transport, crypto, or processing code.

**Reason:**
- Defining data structures first forces clarity about what CAN BLE frames look like.
- Models can be compiled and tested independently.
- Transport code can reference stable model classes.
- Follows "contract-first" development pattern.

**Consequence:**
- Five model classes exist: `CanBleFrame`, `CanBleSessionState`, `RawCanNotification`, `RecordedCanNotification`, `RecordedCanSession`.
- All are immutable data classes with proper equals/hashCode.
- `RecordedCanSession` enables replay testing without hardware.
- Implementation order is fixed: models → crypto → extractor → filter → logger → handshake → transport.

---

## 2026-06-08 — Replay support

**Decision:** CAN BLE notifications will be recorded to JSON files for replay testing.

**Reason:**
- Real bikes are not always available for testing.
- Handshake and frame parsing can be debugged offline with recorded data.
- Reproducing bugs is easier with saved sessions.
- Regression testing after crypto changes.

**Consequence:**
- `RecordedCanNotification` with custom JSON serializer (no external dependencies).
- `RecordedCanSession` with save/load/export/list/delete.
- Replay can feed frames to the same parser pipeline as live data.
- Session files stored in `filesDir/canble-sessions/`.

---

## 2026-06-08 — Documentation before implementation

**Decision:** Complete architecture and implementation documentation will be written before any CAN BLE transport, crypto, or processing code.

**Reason:**
- Provides a single source of truth for all architectural decisions.
- Ensures all team members understand the plan before coding starts.
- Documentation captures rationale that would otherwise be lost.
- Implementation can proceed in well-defined phases without ambiguity.

**Consequence:**
- 7 documentation files created: PROJECT_STATUS, ARCHITECTURE, ROADMAP, DECISIONS, PHASE1_PLAN, CONTRIBUTING, RELEASE_PROCESS.
- Implementation follows documented order exactly.
- All future changes should update documentation first.

---

## 2026-06-07 — Crypto as interface with multiple providers

**Decision:** CAN BLE encryption will use a `CryptoProvider` interface with `NativeCryptoProvider` (preferred, JNI) and `FallbackCryptoProvider` (experimental, pure Kotlin).

**Reason:**
- BafangCon does not contain the NativeHelper library; NativeCryptoProvider will likely fail.
- The exact fixed encryption key is unknown; FallbackCryptoProvider assumptions are marked.
- Interface allows swapping implementations without changing transport code.
- NativeCryptoProvider can be implemented in the future if the native library becomes available.

**Consequence:**
- `CryptoProvider` interface with `encrypt()`, `decrypt()`, `setDynamicKey()`, `supportsCanCrypto()`.
- `NativeCryptoProvider` tries Class.forName("com.pairlink.lib.NativeHelper") and returns false on failure.
- `FallbackCryptoProvider` uses AES/ECB/NoPadding with assumed key = 16x 0x01 (EXPERIMENTAL, not confirmed).
- Transport auto-selects NativeCryptoProvider first, falls back to FallbackCryptoProvider.
- `supportsCanCrypto()` allows graceful degradation if no provider works.

---

## 2026-06-07 — Session state is owned by handshake, read by processors

**Decision:** `CanBleSessionState` is created and updated by `CanBleHandshake`. Other components (`CanBleNotificationProcessor`, `CanBleTransport`) only read it.

**Reason:**
- Single source of truth for session state (aU, dynamicKey, handshake state).
- Prevents race conditions where multiple components modify session state.
- Handshake is the only component that understands state transitions.
- Read-only access prevents accidental state corruption.

**Consequence:**
- `CanBleSessionState` is a Kotlin data class (immutable).
- `CanBleHandshake` owns the current state and emits copies on change.
- `CanBleNotificationProcessor` takes `(aU, dynamicKey)` as parameters from transport.
- `CanBleTransport` bridges between handshake state and notification processor.
