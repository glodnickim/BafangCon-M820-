# Current Architecture — BafangCon v0.042

## High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        BLE Device (M820 Display)                    │
│  ┌────────────────┐  ┌────────────────┐  ┌──────────────────────┐  │
│  │ Nordic UART    │  │ CAN bus        │  │ BMS / Sensor / IoT   │  │
│  │ Service (NUS)  │  │ (internal)     │  │ modules on CAN bus   │  │
│  │ 6E40000x       │  │                │  │ (node 2, 4, 7, etc)  │  │
│  └───────┬────────┘  └────────────────┘  └──────────────────────┘  │
│          │                                                          │
│    BLE   │ AA55 frames over NUS                                     │
└──────────┼──────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        BafangCon App                                │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  BleRepository.kt  (1937 lines)                              │  │
│  │                                                              │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │  │
│  │  │ BLE Scan     │  │ GATT Connect │  │ Service Discov.  │   │  │
│  │  └──────────────┘  └──────────────┘  └──────────────────┘   │  │
│  │                                                              │  │
│  │  ┌──────────────────────────────────────────────────────┐   │  │
│  │  │  GATT Callback                                       │   │  │
│  │  │  ┌───────────┐ ┌──────────┐ ┌──────────────────┐    │   │  │
│  │  │  │onStateChg │ │onSvcDisc │ │onCharChanged     │    │   │  │
│  │  │  └───────────┘ └──────────┘ └──────────────────┘    │   │  │
│  │  │  ┌───────────┐ ┌──────────┐ ┌──────────────────┐    │   │  │
│  │  │  │onCharWrite│ │onDescWr  │ │onMtuChanged      │    │   │  │
│  │  │  └───────────┘ └──────────┘ └──────────────────┘    │   │  │
│  │  └──────────────────────────────────────────────────────┘   │  │
│  │                                                              │  │
│  │  ┌──────────────────────┐  ┌─────────────────────────────┐  │  │
│  │  │  Command Queue       │  │  Fragmentation Assembler    │  │  │
│  │  │  (LinkedList)        │  │  (ByteArrayOutputStream)   │  │  │
│  │  │  + AtomicBoolean     │  │  + while(true) loop        │  │  │
│  │  └──────────────────────┘  └─────────────────────────────┘  │  │
│  │                                                              │  │
│  │  ┌──────────────────────┐  ┌─────────────────────────────┐  │  │
│  │  │  AA55 Protocol       │  │  ParseCompleteFrame         │  │  │
│  │  │  55 AA <len> <echo>  │  │  → checksum validation     │  │  │
│  │  │  <target> <op> <off> │  │  → type dispatch           │  │  │
│  │  │  [data] <cs>         │  │  → full/partial update     │  │  │
│  │  └──────────────────────┘  └─────────────────────────────┘  │  │
│  │                                                              │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐   │  │
│  │  │ Auth     │ │ Heart-   │ │ Ride     │ │ CAN BLE     │   │  │
│  │  │ (AES-128)│ │ beat     │ │ Logger   │ │ Transport   │   │  │
│  │  │          │ │ (0x21)   │ │ (CSV)    │ │ (NOT YET)   │   │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────────┘   │  │
│  └──────────────────────────────────────────────────────────────┘  │
│           │                │            │              │           │
│           ▼                ▼            ▼              ▼           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Data Models                            StateFlows           │  │
│  │  ┌──────────────┐ ┌──────────────┐     controllerInfo        │  │
│  │  │ControllerInfo│ │ MeterInfo    │     meterInfo              │  │
│  │  │ (237 bytes)  │ │ (198 bytes)  │     personalizedInfo      │  │
│  │  ├──────────────┤ ├──────────────┤     batteryInfo            │  │
│  │  │ BatteryInfo  │ │ SensorInfo   │     sensorInfo             │  │
│  │  │ (244 bytes)  │ │ (164 bytes)  │     iotConfigInfo         │  │
│  │  ├──────────────┤ ├──────────────┤     iotCanInfo             │  │
│  │  │Personalized  │ │ IotConfig    │     authState             │  │
│  │  │ (115 bytes)  │ │ (237 bytes)  │     connectionState       │  │
│  │  ├──────────────┤ └──────────────┘     rideLogState           │  │
│  │  │ IotCanInfo   │                     bleLogs                │  │
│  │  │ (97 bytes)   │                     connectionError        │  │
│  │  └──────────────┘                                            │  │
│  └──────────────────────────────────────────────────────────────┘  │
│           │                                                        │
│           ▼                                                        │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  DeviceViewModel.kt  (324 lines)                            │  │
│  │  - passthrough StateFlows                                    │  │
│  │  - command methods (connect, read, write)                    │  │
│  │  - write + delay(500) + re-read pattern                     │  │
│  └──────────────────────────────────────────────────────────────┘  │
│           │                                                        │
│           ▼                                                        │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Fragments (XML-based)                                       │  │
│  │  ┌──────────────┐ ┌────────────────┐ ┌──────────────────┐   │  │
│  │  │ ScanFragment │ │ MainFragment   │ │ ControllerInfo   │   │  │
│  │  │ (358 lines)  │ │ (1058 lines)   │ │ Fragment         │   │  │
│  │  │              │ │                │ │ (250 lines)      │   │  │
│  │  │ BLE scan     │ │ Assist editor  │ │                  │   │  │
│  │  │ auto-connect │ │ chart, presets │ │ Controller       │   │  │
│  │  │              │ │ ride logging   │ │ settings editor  │   │  │
│  │  ├──────────────┤ ├────────────────┤ ├──────────────────┤   │  │
│  │  │ MeterInfo    │ │ GearsInfo      │ │ SystemInfo       │   │  │
│  │  │ Fragment     │ │ Fragment       │ │ Fragment         │   │  │
│  │  │ (327 lines)  │ │ (287 lines)    │ │ (248 lines)      │   │  │
│  │  │              │ │                │ │                  │   │  │
│  │  │ Meter/display│ │ Per-gear       │ │ All data in     │   │  │
│  │  │ settings     │ │ assist values  │ │ grouped sections│   │  │
│  │  └──────────────┘ └────────────────┘ └──────────────────┘   │  │
│  │                                                                  │
│  │  RootActivity.kt (77 lines) — fragment navigation host       │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Utilities                         Other                     │  │
│  │  ┌──────────┐ ┌───────────────┐    ┌───────────┐            │  │
│  │  │ AESUtils │ │ ByteBuffer    │    │ Preset    │            │  │
│  │  │          │ │ Utils         │    │ Manager   │            │  │
│  │  ├──────────┤ ├───────────────┤    ├───────────┤            │  │
│  │  │ Packet   │ │               │    │ Assist    │            │  │
│  │  │Validator │ │               │    │ Preset    │            │  │
│  │  └──────────┘ └───────────────┘    └───────────┘            │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

## BLE Flow (NUS/AA55)

```
1. SCAN
   └→ startScan() → 10s BluetoothLeScanner scan
      └→ filter devices with "DP" prefix
         └→ auto-connect if saved device found

2. CONNECT
   └→ connectDevice(address) → BluetoothDevice.connectGatt()
      └→ onConnectionStateChange(CONNECTED)
         └→ 600ms delay → discoverServices()

3. SERVICE DISCOVERY
   └→ onServicesDiscovered()
      └→ find NUS service (6E400001-B5A3-F393-E0A9-E50E24DCCA9E)
         └→ get TX (6E400002) and RX (6E400003) characteristics
            └→ setCharacteristicNotification(RX, true)
               └→ write CCCD descriptor (0x2902) = 01 00

4. CCCD WRITTEN
   └→ onDescriptorWrite()
      └→ connectionState = CONNECTED
         └→ requestMtu(250)
            └→ start 1.5s fallback timer

5. MTU NEGOTIATION
   └→ onMtuChanged()
      └→ negotiatedMtu = mtu
         └→ startAuthentication()

6. AUTHENTICATION
   └→ send read request for target 0x10 offset 0 length 4
      └→ device responds with 4 random bytes (challenge)
         └→ handleAuthChallenge()
            └→ pad 4 bytes → 16 bytes with trailing zeros
               └→ AESUtils.encrypt() with key "2CTDU40qNyCgTjb1"
                  └→ send certification frame (0x20)
                     └→ device responds with result byte
                        └→ if result == 0: AUTHENTICATED
                           └→ else: AUTH_FAILED

7. DATA PREFETCH (on authentication success)
   └→ request all 7 data types in sequence:
      Controller (A3) → Meter (A5) → Personalized (A9)
      → Battery (A4) → Sensor (A7) → IoT Config (A1) → IoT CAN (A2)
   └→ start heartbeat (0x21) every 1 second

8. TELEMETRY STREAM
   └→ device sends A3 notifications (243 bytes, startPos=0)
      └→ parseCompleteFrame() → ControllerInfo.parseControllerInfoPayload()
      └→ if startPos=160: applyControllerTelemetrySegment() → 48 bytes of live telemetry
         → updates RideTelemetrySample → CSV log

9. WRITE OPERATION (example: light on/off)
   └→ sendShortUpdate(targetType=0xA5, offset=0xA6, value)
      └→ commandQueue.add(frame)
         └→ processNextCommand()
            └→ gatt.writeCharacteristic(TX)
               └→ onCharacteristicWrite → processNextCommand()
```

## AA55 Protocol Frame Format

```
Request (App → Device):
┌────┬────┬──────┬────┬──────┬──┬─────┬──────┬──────┬──────┐
│ 55 │ AA │ Len  │ 11 │ Targ │Op │ Addr│ Data │ CS_L │ CS_H │
└────┴────┴──────┴────┴──────┴──┴─────┴──────┴──────┴──────┘
  0    1     2     3     4    5    6     7..    n-2    n-1

Response (Device → App):
┌────┬────┬──────┬────┬──────┬──┬─────┬──────┬──────┬──────┐
│ 55 │ AA │ Len  │ 11 │RespID│Op │Start│ Data │ CS_L │ CS_H │
└────┴────┴──────┴────┴──────┴──┴─────┴──────┴──────┴──────┘

Target IDs (command):       Response ID (same byte):
  0x10 = AUTH               0xA3 = Controller
  0xA1 = IoT Config         0xA5 = Meter
  0xA2 = IoT CAN            0xA9 = Personalized
  0xA3 = Controller          0xA4 = Battery
  0xA4 = Battery             0xA7 = Sensor
  0xA5 = Meter               0xA1 = IoT Config
  0xA7 = Sensor              0xA2 = IoT CAN
  0xA9 = Personalized

Op codes:
  0x01 = Read
  0x02 = Write
  0x20 = Certification (AES encrypted)
  0x21 = Event Notification (heartbeat)

Checksum:
  Format A (A1, A4, A7): 1-byte sum checksum + 0xFE terminator
  Format B (A3, A5, A9): 2-byte CRC-16-Kermit LE
```

## BleRepository Architecture

BleRepository is a single 1937-line class that handles the entire BLE lifecycle:

- **Scanning**: BluetoothLeScanner with SCAN_MODE_LOW_LATENCY
- **Connection**: BluetoothGatt with TRANSPORT_LE, PHY_LE_1M_MASK
- **GATT Callback**: Anonymous `BluetoothGattCallback()` with 6 overrides
- **Command Queue**: `Queue<ByteArray>` + `AtomicBoolean` for flow control
- **Fragment Assembly**: `ByteArrayOutputStream` + while loop looking for `55 AA` markers
- **Frame Parsing**: `parseCompleteFrame()` → checksum validation → type dispatch → partial/full update
- **Authentication**: AES-128-ECB challenge-response
- **Heartbeat**: Timer-based 0x21 event notification every 1 second
- **Ride Logging**: CSV via SAF DocumentFile, 28 telemetry fields at 1-second intervals
- **StateFlows**: 12 StateFlows exposed, updated when data arrives

Key constants:
- `MTU_AUTH_FALLBACK_DELAY = 1500L` (ms)
- `SCAN_PERIOD_MS = 10000L`
- `CONNECTION_TIMEOUT_MS = 10000L`
- `COMMAND_TIMEOUT_MS = 2000L`
- `HEARTBEAT_INTERVAL = 1000L`

## DeviceViewModel

- 324 lines, extends `AndroidViewModel`
- Creates `BleRepository` as private field
- Exposes all 12 StateFlows as read-only
- All write methods follow: `launch { repo.write(); delay(500); repo.read() }`
- `onCleared()` calls `repo.cleanup()`

## CAN BLE — Current Place

CAN BLE exists only as model classes under `canble/model/`:

```
canble/
  model/
    CanBleFrame.kt          ✓
    CanBleSessionState.kt   ✓
    RawCanNotification.kt   ✓
    RecordedCanNotification.kt  ✓
    RecordedCanSession.kt   ✓
  crypto/                   (not yet created)
  process/                  (not yet created)
  handshake/                (not yet created)
  transport/                (not yet created)
  log/                      (not yet created)
```

CAN BLE is **not wired into BleRepository, DeviceViewModel, or any UI**. There is no transport, no handshake, no crypto, no parser. The models define the data structures for Phase 1 implementation.

## Data Flow Summary

```
BLE Notification
  → processBleNotificationData()
    → fragment assembly (55 AA detection)
      → parseCompleteFrame()
        → checksum validation
          → type routing (A3/A5/A9/A4/A7/A1/A2)
            → full parse (startPos=0) or partial update (startPos>0)
              → update StateFlow
                → ViewModel passthrough
                  → Fragment observation → UI update
                    → RideLogger (if active) → CSV file
```
