# Changelog

All notable user-visible changes should be written here.

Use this format:

```md
## vX.XXX - YYYY-MM-DD

### Added
- ...

### Changed
- ...

### Fixed
- ...

### Known Issues
- ...
```

## Unreleased

### Added
- Nothing yet.

### Changed
- Nothing yet.

### Fixed
- Nothing yet.

## v0.063 - 2026-06-14

### Added
- Realtime telemetry from the display's `0x06` broadcast frame (~0.7 s cadence):
  torque sensor, SOC, assist level, remaining range, odometer/trip. Shown in the
  System tab under "Realtime (0x06)", with raw fields exposed for ongoing
  reverse-engineering.
- System tab: rows that stay static during a ride ("dead" blocks) are hidden by
  default, with a "Show inactive blocks" toggle.
- On-screen crash reporter: uncaught exceptions are displayed full-screen with the
  stack trace (diagnostic aid for field testing without adb).
- Build: `-Mode clean-release` in `build-app.ps1` for a clean, signed release build.

### Changed
- Authentication retries the challenge read for up to 45 s (every 2.5 s), so
  connecting before the display has finished booting still succeeds.
- Assist data loading polls the controller (A3) and personalized (A9) blocks until
  real, non-zero data arrives instead of giving up — the display answers with all
  zeros while it is still booting.

### Fixed
- Startup crash in release builds caused by R8 stripping (hardened ProGuard keep rules).
- Crash when opening the main screen (`NoSuchFieldError: presetNavButton`) caused by a
  stale incremental build; resolved with a clean release build.
- App hanging on "Loading…" when connecting during display boot — assist data now loads
  automatically once the display responds (previously needed a manual log-view toggle).
- BLE command queue could stall on a write that never ACKed; added a write-ACK timeout
  watchdog that unblocks the queue.

### Known Issues
- The on-screen crash reporter is a temporary diagnostic aid and will be removed once
  the build is confirmed stable.
- Battery/sensor/IoT blocks are read once after authentication; if read during display
  boot they may show zeros until the next manual refresh.

## v0.040 - 2026-06-05

### Added
- Expanded the System tab with A3 controller identity, live telemetry, counters
  and settings already parsed by the app.

## v0.039 - 2026-06-05

### Changed
- Pressing `Log` now always opens Android folder picker before starting a new
  CSV log.
- Removed automatic reuse of a previously selected log folder so the user always
  chooses where the next file will be saved.

## v0.038 - 2026-06-05

### Added
- Added ride logging to CSV.
- Added Android folder picker before starting ride logging.
- Saved the selected log folder permission for later rides.
- CSV log now includes speed, current, voltage, calculated power, controller SOC,
  assist level, cadence, torque raw value, temperatures, boost state, speed limit,
  wheel/crank counters and raw A3 telemetry bytes.
- CSV log also includes last known battery SOC, voltage, current, power and
  temperature from A4 when available.

### Changed
- Ride logs are no longer written to a default app directory.
- Pressing `Log` without a saved folder opens the system folder selection window.
- A3 telemetry segment `160..207` is parsed as a live telemetry block and updates
  controller data in the app.

### Fixed
- Partial A3 telemetry frames are no longer rejected by the old single-field
  partial update parser.

### Known Issues
- Battery A4 values in the CSV are the last known battery readout, not guaranteed
  to be refreshed every second.

## v0.037 - 2026-06-05

### Added
- Added first version of ride telemetry logging.
- Added CSV sample model for A3 telemetry.
- Added Log/Stop button in the main bottom bar.

### Changed
- Full A3 controller reads can be converted to telemetry samples.

## v0.036 - 2026-06-05

### Fixed
- Improved connection/authentication flow for Android 12 devices.
- Main screen now waits for authenticated connection before requesting data.
- Added authentication timeout and MTU/auth fallback handling.

## 2026-05-29

### Added
- Added AES/ECB/NoPadding authentication with key `2CTDU40qNyCgTjb1`.
- Added `BleAuthState`.
- Added MTU request after enabling notifications.
- Added visible authentication state in the main flow.
- Added mapping for 3, 4, 5 and 9 assist levels.

### Changed
- Updated AA55 read/write checksum handling.
- Reset authentication state on disconnect.
