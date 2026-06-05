# FT Bafang M820

Android app for Bafang M820 / CAN displays. The app connects over Bluetooth LE,
authenticates with the display, reads controller/display/battery data and allows
editing assist settings.

## Current Focus

- Bafang M820 with DP C245-30.CAN style BLE display.
- BLE connection through Nordic UART Service.
- AA55 protocol frames with AES authentication.
- Assist profile read/write.
- System tab for protocol data inspection.
- Ride logging to CSV for analysis.

## Main Features

- Scan and connect to nearby BLE devices.
- Authenticate before sending data commands.
- Read controller data A3, display data A5, battery data A4 and other available blocks.
- Edit assist profile values used by the controller.
- Show live/system values such as speed, current, voltage, SOC, cadence, gear and temperatures.
- Log ride telemetry to CSV.

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

- copied APK: `Bafang FT M820 v0.038.apk`
- `version.properties` after build: `0.039`
- published version: `v0.038`

## Project Files

- `app/src/main/java/com/test/bafangcon/BleRepository.kt` - BLE connection, auth, frame parsing, logging.
- `app/src/main/java/com/test/bafangcon/RideTelemetrySample.kt` - CSV telemetry sample model.
- `app/src/main/java/com/test/bafangcon/MainFragment.kt` - main UI, Log/Stop button, folder picker.
- `version.properties` - next version used by the build.
- `CHANGELOG.md` - user-visible history of releases.
- `docs/DOCUMENTATION_WORKFLOW.md` - how to document while coding.
- `docs/RELEASE_CHECKLIST.md` - checks before publishing APK.
- `docs/RELEASE_NOTES_TEMPLATE.md` - template for public release notes.

## Related Protocol Docs

The repository also contains protocol notes in the top-level `docs/` directory:

- `docs/komunikacja-dp-c245.md`
- `docs/bafang-protocol-logic-junior.md`
