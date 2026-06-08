<<<<<<< HEAD
# FT Bafang M820

Android app for Bafang M820 / CAN displays running **Fake Taxi** firmware.
Connects over Bluetooth LE, authenticates with the display, reads controller/display/battery/sensor data,
and allows editing assist parameters. Works offline — no internet connection required.

The app has been tested with **DPC245 v3** display and **Fake Taxi 2026.05.22** firmware.

## Current Status — v0.042

- **AA55/NUS** — primary working transport. Stable BLE scan, connection, authentication, data read/write.
- **CAN BLE** — model classes created (`canble/model/`), transport not yet implemented.
- **Ride logging** — CSV via SAF folder picker with 28 telemetry fields.
- **Language** — Polish and English interface support.
- **Next:** CAN BLE crypto, frame extraction, filtering, and logging (Phase 1 Core).

## Current Focus

- Bafang M820 with DP C245-30.CAN style BLE display.
- BLE connection through Nordic UART Service (NUS) and AA55 protocol frames with AES authentication.
- CAN BLE service (passive read-only telemetry, in development).
- Assist profile read/write and preset save/load.
- System tab for protocol data inspection.
- Ride logging to CSV for analysis.
- Charts showing assist levels (MPAndroidChart + custom Canvas).

## Main Features

- BLE scan and auto-connect to nearby devices (DP prefix filter).
- AES-based authentication (challenge-response).
- Read all 7 data types: controller (A3), display (A5), battery (A4), sensor (A7), personalized assist (A9), IoT config (A1), IoT CAN config (A2).
- Edit assist profile values used by the controller.
- Write assist, controller, and meter settings.
- Show live values: speed, current, voltage, SOC, cadence, gear, temperatures.
- Acceleration and start angle adjustment.
- Assist presets — save and load custom user settings.
- Log ride telemetry to CSV.
- Charts: assist curve visualization, gear comparison.
- Polish and English language support.

## Ride Logs

Every time the user presses `Log`, Android asks for a folder. The app writes the
CSV file only after the user selects the target folder. There is no silent
default log directory.

CSV filename format:

```text
ride-YYYYMMDD-HHMMSS.csv
```

Logged values include:

- speed
- current
- voltage
- calculated power
- controller SOC
- assist level
- cadence
- torque raw value
- controller and motor temperature
- boost state
- speed limit
- wheel/crank counters
- last known battery SOC, voltage, current and temperature
- raw A3 telemetry bytes

Pressing `Stop` closes the current CSV file.

## Build

Always build release APK unless there is a clear reason to test debug.

From repository root:

```powershell
.\build-app.ps1 -Mode release
```

The script copies the APK to:

```text
BafangCon\Bafang FT M820 vX.XXX.apk
```

Important: after copying the APK, the script bumps `version.properties` to the
next version. Publish the APK version from the copied filename, not the bumped
next version.

Example:

- copied APK: `Bafang FT M820 v0.042.apk`
- `version.properties` after build: `0.043`
- published version: `v0.042`

## Project Files

- `app/src/main/java/com/test/bafangcon/BleRepository.kt` — BLE connection, auth, frame parsing, logging.
- `app/src/main/java/com/test/bafangcon/canble/` — CAN BLE model classes and future transport.
- `app/src/main/java/com/test/bafangcon/RideTelemetrySample.kt` — CSV telemetry sample model.
- `app/src/main/java/com/test/bafangcon/MainFragment.kt` — main UI, Log/Stop button, folder picker.
- `version.properties` — next version used by the build.
- `CHANGELOG.md` — user-visible history of releases.
- `build-app.ps1` — release build script (bumps version, copies APK).

## Documentation

The repository contains a `docs/` directory with project and protocol documentation:

| File | Content |
|---|---|
| `PROJECT_STATUS.md` | Current version, features, risks, build status |
| `CURRENT_ARCHITECTURE.md` | Full architecture diagram, BLE and AA55 flow, CAN BLE place |
| `ROADMAP.md` | Version roadmap (v0.041 → future) |
| `DECISIONS.md` | All architectural decisions with rationale |
| `CAN_BLE_PHASE1_PLAN.md` | Detailed Phase 1 implementation plan |
| `CONTRIBUTING.md` | Branch strategy, commit naming, PR workflow, code style |
| `RELEASE_PROCESS.md` | Step-by-step release workflow with version history |
| `DOCUMENTATION_WORKFLOW.md` | How to document while coding |
| `RELEASE_CHECKLIST.md` | Checks before publishing APK |
| `RELEASE_NOTES_TEMPLATE.md` | Template for public release notes |
| `CANABLE_LOG_ANALYSIS.md` | CAN bus log analysis notes |

### Protocol Docs

- `docs/komunikacja-dp-c245.md`
- `docs/bafang-protocol-logic-junior.md`
=======
`FT Bafang APP M820` is an Android app for BLE communication with a Bafang M820 motor running the custom `Fake Taxi` firmware. It allows the user to connect to the bike, read system information and configure basic assist parameters. The available functions are mapped and organized to make motor setup and tuning easier. The app works locally and does not require an internet connection.
Main features:
- Bluetooth LE scanning and connection,
- authentication compatible with the Fake Taxi protocol,
- reading controller, display, battery and sensor information,
- assist configuration: speed and current/power limits,
- charts showing assist levels,
- acceleration and start angle adjustment,
- presets with the option to save and load custom user settings,
- Polish and English language support.
The app is intended for `Bafang M820` with `Fake Taxi 2026.05.22` firmware. It has been tested only with the `DPC245 v3` display. 
>>>>>>> origin/master
