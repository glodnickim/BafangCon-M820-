# CANable Log Analysis

Source logs:

```text
C:\snapshot\bafang_canable_pro\logs
```

Analysed samples:

- `log-2026-06-05-18-30-31-n0.log`
- `log-2026-05-24-15-38-56-n0.log`
- `log-2025-08-21-16-39-09.log`

## Important Finding

The Android app currently talks to the DP C245 display over BLE/NUS using AA55
frames. CANable logs show CAN frames on the bike bus after the display/controller
translation layer.

So these are related, but not the same frame format:

- App request: `55 AA ... A3/A4/A5/A2 ...`
- CANable bus frame: extended CAN IDs like `83116000`, `821D0000`, `83106301`

Do not directly replace AA55 frames with CANable IDs. First confirm whether the
display exposes raw CAN transmit over the current NUS/AA55 protocol. At the
moment the app only has parsed AA55 targets such as `A3`, `A4`, `A5`, `A1`,
`A2`, not a generic raw-CAN bridge.

## System Data Objects Seen In CAN Logs

The logs show a stable segmented read pattern:

```text
831160xx -> request object length
821C60xx -> length response
831260xx -> request object data
821D0000 / 821D0001 / ... -> data chunks
821E.... -> final data chunk
```

Confirmed decoded objects:

| Object | Length | ASCII value | Notes |
|---|---:|---|---|
| `6000` | 18 | `CR X30P.250.FC 2.1` | likely controller firmware/hardware version |
| `6001` | 20 | `FAKE TAXI 20260522w1` | custom firmware string |
| `6002` | 14 | `CR X30P.250.FC` | model/base identifier |
| `6003` | 22 | `MMG532.250.CF3YA120681` | serial/model string |

These values match the kind of data already available in `ControllerInfo` A3:

- `hardVersion`
- `softVersion`
- `model`
- `sn`
- `customerNo`
- `manufacturer`

Current `SystemInfoFragment` does not show all of these A3 string fields, so the
first implementation step should be to display them before adding a new CAN
transport.

## Param Blocks Seen In CAN Logs

Objects `6011` and `6012` are 64-byte binary blocks.

Observed `6011` examples:

```text
24 0F 2F B8 0B E4 0C 10 27 00 19 0F 2F 00 00 30
02 1C 01 07 01 01 FC 0D 00 00 00 00 00 00 00 00
00 00 0E 20 01 0A 04 02 14 19 1E 23 2A 32 3C 46
64 28 1E 32 28 32 32 32 31 32 01 00 00 00 FF 8C
```

The existing protocol notes identify CAN `Param1` as:

| Byte range | Meaning |
|---:|---|
| `0..39` | preserve unknown parameters |
| `40..48` | `current_limit[0..8]` |
| `49..57` | `speed_limit[0..8]` |
| `62` | `0xFF` |
| `63` | checksum over bytes `0..62` |

The logs confirm recognizable 9-slot percentage arrays near the end of `6011`.
Example values include:

```text
14 19 1E 23 2A 32 3C 46 64
```

which is decimal:

```text
20, 25, 30, 35, 42, 50, 60, 70, 100
```

This is strong evidence that `6011` is useful for assist/profile inspection.
Mapping should still be treated carefully because the current DP C245 AA55 path
uses A3 offsets `214..223` and `224..233` with 10 slots, while CAN Param1 uses
9 slots.

## Live Frames Seen In CAN Logs

Common live IDs:

| CAN ID | Example data | Notes |
|---|---|---|
| `83106300` | `05 0B 01 01` | frequent small state frame; byte 2 changes often |
| `83106301` | `79 0B 00 70 2A 00 E7 03` | likely live telemetry payload |
| `83106302` | `8F 01 70 2A 00` | likely companion telemetry payload |
| `83106303` | `0A` | single-byte state |
| `83106304` | `05 03 04 04` | state/mode frame |
| `82F83000` | `1A 00 00 00` | periodic counter/heartbeat |
| `82FF1200` | `00` | repeated status/heartbeat |

These should not be mapped blindly. Some fields look plausible as speed/current
or voltage, but scales are not fully confirmed. Use them for comparison against
the app CSV recorded at the same time as CANable logs.

## Why System Tab Looks Empty Or Dead

Current system tab mostly shows selected fields from:

- `A3` ControllerInfo
- `A5` MeterInfo
- `A4` BatteryInfo
- `A7` SensorInfo
- `A1/A2` IoT data

Problems:

1. `SystemInfoFragment` omits many A3 identity fields that are already parsed.
2. Some sections depend on A1/A2/A7 being present, but those targets may be empty
   or irrelevant on this DP C245 setup.
3. Live values are refreshed by full A3 reads every 3 seconds, while ride logging
   uses the smaller A3 segment `160..207`.
4. CANable logs reveal extra object-style CAN reads, but the current app does not
   yet implement raw CAN object read commands over BLE.

## Recommended Implementation Plan

### Step 1 - Low Risk, Use Existing A3/A4/A5 Data

Status: implemented in v0.040.

Update `SystemInfoFragment` to show more fields already parsed by the app:

- A3 hardware version
- A3 software version
- A3 model
- A3 serial number
- A3 customer number
- A3 manufacturer
- A3 raw telemetry values already used by CSV:
  - wheel speed
  - wheel counter
  - last sensor time
  - crank pulse counter
  - motor variable speed master gear
  - motor speed current gear

Verification:

- Open System tab after connection.
- Confirm values match A3 full read and CANable object strings where possible.

### Step 2 - Add Raw/Diagnostic System Section

Add a diagnostic section with raw hex snippets:

- A3 `160..207`
- A3 `188..233`
- A3 full identity string fields
- A4 battery raw summary

Verification:

- Compare raw values with CSV ride logs and CANable logs.

### Step 3 - Offline CANable Decoder Tool

Add a local parser script for CANable logs:

- input: `*.log`
- output: table of object reads `6000..6003`, `6011`, `6012`
- output: unique live frames `831063xx`

This does not touch the Android app. It helps validate mappings before sending
new commands to the bike.

### Step 4 - Investigate Raw CAN Over Current BLE Display

Only after Step 1-3:

- Search logs/app/decompiled code for how `831160xx` requests are sent through
  BLE on this display.
- Test if AA55 target `A2` is only IoT CAN info or can carry raw CAN requests.
- If a raw-CAN bridge exists, add it behind an experimental flag.

Verification:

- Send read-only object request for `6000`.
- Expect segmented response that decodes to `CR X30P.250.FC 2.1`.
- Do not implement writes until read-only object reads are proven stable.

### Step 5 - Map Param1 Carefully

If object `6011` can be read from the Android app:

- parse 64-byte raw block
- display 9-slot current/speed limit arrays
- compare with A3 10-slot arrays
- do not write anything yet

Verification:

- Values should match CANable logs and current assist UI.
- Check checksum byte 63 equals sum of bytes `0..62`.

## Do Not Do Yet

- Do not send `6011`/Param1 write commands from the Android app yet.
- Do not replace the existing A3 46-byte write flow.
- Do not assume 9-slot CAN Param1 maps 1:1 to A3 10-slot arrays.
- Do not infer current/voltage scales from CAN live frames without a simultaneous
  app CSV + CANable log comparison.
