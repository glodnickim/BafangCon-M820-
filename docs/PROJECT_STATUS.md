# Project Status — BafangCon

## Current Version

- **version.properties:** 0.063 (versionCode=63)
- **Last released APK:** 0.063 (Bafang FT M820 v0.063.apk)
- **Last Build:** 2026-06-14 (assembleRelease)
- **Build Type:** Release APK (signed, split by ABI + universal)

### Recent highlights (v0.063)

- Realtime `0x06` broadcast telemetry parsed and shown in the System tab.
- Resilient connect: patient authentication (retries up to 45 s) and assist-data
  loading that waits out the display's boot (it returns zeros until ready).
- Release-stability fixes: hardened R8 keep rules (startup crash), clean-build
  guidance for the `NoSuchFieldError` view-binding crash, write-ACK queue watchdog.
- On-screen crash reporter (temporary diagnostic aid).

## Stable Features

The following features are considered stable and working correctly:

- BLE scanning with device filtering (DP prefix auto-connect)
- Connection and reconnection with 2-attempt retry
- Nordic UART Service (NUS) AA55 protocol transport
- AES-based authentication (challenge-response with key `2CTDU40qNyCgTjb1`)
- Heartbeat keepalive (1-second event notification)
- Read all 7 data types:
  - Controller info (A3, 237 bytes)
  - Meter/display info (A5, 198 bytes)
  - Personalized assist settings (A9, 115 bytes)
  - Battery info (A4, 244 bytes)
  - Torque sensor info (A7, 164 bytes)
  - IoT config (A1, 237 bytes)
  - IoT CAN config (A2, 97 bytes)
- Write assist settings (50-byte personalized block)
- Write controller settings (full block + partial updates)
- Write meter settings (light, gears, units, shutdown)
- Ride logging to CSV via SAF folder picker
- Partial A3 telemetry segment update (offset 160, 48 bytes)
- Assist preset save/load/delete (SharedPreferences + Gson)
- MPAndroidChart assist curve visualization
- Custom Canvas chart for gear comparison
- Dark theme UI with in-line editing

## Experimental Features

The following features exist but are not yet fully integrated or tested:

- **CAN BLE models** — Five model classes exist under `canble.model.*` but are not connected to any transport or UI. No crypto, handshake, or parser has been implemented yet.
- **Jetpack Compose theme** — Files under `ui/theme/` define Material3 colors, typography, and theme but are unused by the current XML-based fragments.

## In Progress

- **CAN BLE transport** — Implementation pending. Models compile, docs complete. Next: CryptoProvider, FrameExtractor, FrameFilter, Logger, Handshake, Transport.
- **Documentation** — 7 files complete in `docs/`. Version references updated to v0.042.

## Not Implemented Yet

- CAN BLE handshake and transport
- Dual-transport telemetry merger
- CAN BLE telemetry dashboard
- Expert mode with raw CAN inspection
- CAN write commands (e.g., light on/off via CAN)
- Advanced diagnostics (graphing, data export)
- Ride distance / elevation data
- Bluetooth LE reconnection after signal loss
- Multi-device support
- Unit tests and integration tests

## Known Risks

| Risk | Severity | Notes |
|---|---|---|
| AA55 authentication depends on hardcoded AES key | Low | Key `2CTDU40qNyCgTjb1` is embedded in AESUtils.kt; extracted from decompiled official app. |
| MTU negotiation fallback timer (1.5s) may race with auth | Low | If MTU response arrives late, auth starts early and may fail. |
| CAN BLE fixed encryption key (0x01) is assumed | Medium | Used in FallbackCryptoProvider; not confirmed from source. May differ between firmware versions. |
| Fragment navigation uses raw FragmentManager | Medium | No Navigation Component; backstack management is manual. |
| Ride CSV export uses SAF | Low | Works but requires user to pick folder every time. |
| No BLE reconnection after disconnect | Low | User must manually re-scan and reconnect. |

## Last Verified Build

- **Date:** 2026-06-08
- **Version:** 0.042 (version.properties)
- **Command:** `.\build-app.ps1 -Mode release`
- **Result:** BUILD SUCCESSFUL in 39s
- **APK size:** ~2.7 MB (release, Bafang FT M820 v0.042.apk)
- **Note:** v0.042 adds CAN BLE models and documentation. Models compile cleanly. Next: CryptoProvider, FrameExtractor, FrameFilter, Logger.

## Current CAN BLE Status

**Phase: Documentation + Models complete. Implementation not started.**

| Component | Status | File |
|---|---|---|
| CanBleFrame | ✅ Done | `canble/model/CanBleFrame.kt` |
| CanBleSessionState | ✅ Done | `canble/model/CanBleSessionState.kt` |
| RawCanNotification | ✅ Done | `canble/model/RawCanNotification.kt` |
| RecordedCanNotification | ✅ Done | `canble/model/RecordedCanNotification.kt` |
| RecordedCanSession | ✅ Done | `canble/model/RecordedCanSession.kt` |
| CryptoProvider interface | ❌ Not started | — |
| NativeCryptoProvider | ❌ Not started | — |
| FallbackCryptoProvider | ❌ Not started | — |
| CanBleFrameExtractor | ❌ Not started | — |
| CanBleFrameFilter | ❌ Not started | — |
| CanBleLogger | ❌ Not started | — |
| CanBleHandshake | ❌ Not started | — |
| CanBleTransport | ❌ Not started | — |
| BleRepository integration | ❌ Not started | — |
