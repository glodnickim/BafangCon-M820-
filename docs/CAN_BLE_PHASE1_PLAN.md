# CAN BLE Phase 1 Plan

## Overview

Phase 1 validates the CAN BLE transport on real hardware. No UI, no telemetry decoding, no write commands. Pure data collection and debugging.

## What Is Already Implemented

| File | Status | Description |
|---|---|---|
| `canble/model/CanBleFrame.kt` | ✅ | Parsed CAN frame: sourceNode, destNode, op, func, index, payload |
| `canble/model/CanBleSessionState.kt` | ✅ | Session state: aU, dynamicKey, handshakeState, mtu |
| `canble/model/RawCanNotification.kt` | ✅ | Raw BLE notification: byte array + UUID + timestamp |
| `canble/model/RecordedCanNotification.kt` | ✅ | JSON-serializable recorded notification with encrypted/decrypted hex |
| `canble/model/RecordedCanSession.kt` | ✅ | Record/replay/save/load sessions |

## What Does Not Exist Yet

| Component | Priority | File | Dependencies |
|---|---|---|---|
| CryptoProvider interface | P0 | `canble/crypto/CryptoProvider.kt` | — |
| NativeCryptoProvider | P0 | `canble/crypto/NativeCryptoProvider.kt` | CryptoProvider |
| FallbackCryptoProvider | P0 | `canble/crypto/FallbackCryptoProvider.kt` | CryptoProvider |
| CanBleFrameExtractor | P0 | `canble/process/CanBleFrameExtractor.kt` | CanBleFrame |
| CanBleFrameFilter | P0 | `canble/process/CanBleFrameFilter.kt` | CanBleFrame |
| CanBleLogger | P0 | `canble/log/CanBleLogger.kt` | RawCanNotification, CanBleFrame |
| CanBleHandshake | P0 | `canble/handshake/CanBleHandshake.kt` | CryptoProvider, CanBleSessionState |
| CanBleTransport | P0 | `canble/transport/CanBleTransport.kt` | All of the above |
| BleRepository hooks | P0 | `BleRepository.kt` (+40 lines) | CanBleTransport |

## Implementation Order

```
STEP 1: Models (DONE)
─────────────────────
CanBleFrame.kt
CanBleSessionState.kt
RawCanNotification.kt
RecordedCanNotification.kt
RecordedCanSession.kt

STEP 2: Crypto
──────────────
CryptoProvider.kt          ← interface
NativeCryptoProvider.kt    ← prefers JNI
FallbackCryptoProvider.kt  ← pure Kotlin fallback

STEP 3: Frame Processing
─────────────────────────
CanBleFrameExtractor.kt    ← extracts CAN frames from decrypted bytes
CanBleFrameFilter.kt       ← filters frames (echo, dest, func rules)

STEP 4: Logging
───────────────
CanBleLogger.kt            ← structured logging + file dump + metrics

STEP 5: Handshake
─────────────────
CanBleHandshake.kt         ← RAND1→RAND2→key derivation→MTU→READY

STEP 6: Transport
─────────────────
CanBleTransport.kt         ← GATT operations, notification routing

STEP 7: Integration
───────────────────
BleRepository.kt changes   ← 3 hook points (+~40 lines)
```

## Phase 1 Scope

### DOZWOLONE (Allowed)

- CAN BLE service discovery on GATT
- Characteristic subscription (CCC descriptor write)
- CAN BLE handshake (RAND1, RAND2, key derivation, MTU)
- Notification reception and decryption
- CAN frame extraction from BLE notifications
- Frame filtering (echo rejection, destination validation)
- Structured logging to Logcat and file
- Metrics collection (notifications/sec, unique indexes)
- Session recording to JSON
- Recorded session replay (for offline testing)

### NIEDOZWOLONE (Not Allowed)

- ❌ UI changes (no new fragments, no ViewModel integration)
- ❌ Telemetry decoding (no speed/current/voltage parsing)
- ❌ CAN write commands
- ❌ CAN READ requests (get_base_info, get_battery_info)
- ❌ Parameter modification
- ❌ AA55/NUS code refactoring
- ❌ Changes to existing command queue
- ❌ Changes to existing authentication flow

## File Details

### CryptoProvider (interface)

```kotlin
interface CryptoProvider {
    fun supportsCanCrypto(): Boolean
    fun encrypt(data: ByteArray, context: Int): ByteArray?
    fun decrypt(data: ByteArray, context: Int): ByteArray?
    fun setDynamicKey(key: ByteArray)
}
```

- `context=1` — fixed key (CAN_CONTROL channel)
- `context=0` — dynamic key (CAN_TRANSMIT channel, after handshake)

### NativeCryptoProvider

- Uses `Class.forName("com.pairlink.lib.NativeHelper")` to detect native library
- Delegates to `bafangEncry()` / `bafangDecry()` if available
- Returns `supportsCanCrypto() = false` if native library not found

### FallbackCryptoProvider

**EXPERIMENTAL — assumptions not confirmed.**

```
Assumption: fixed key = ByteArray(16) { 0x01 }
Source: Go+ decompile, PlBleService.fill pattern
Status: NOT CONFIRMED. May differ between firmware versions.
```

- `AES/ECB/NoPadding` (confirmed from PlBleService)
- Pads input to 16 bytes with zeros before encrypt
- Trims trailing zeros after decrypt
- Sets dynamic key after handshake

### CanBleFrameExtractor

- Input: decrypted `ByteArray` from BLE notification
- Output: `List<CanBleFrame>`
- Algorithm: iterate with `step = data[i+4] + 5`
- Validates: min frame size 5, max frame size 13
- Multiple frames per notification are common

### CanBleFrameFilter

- Rules (from PlBleService `process_can_data` lines 2333–2412):
  1. `sourceNode == 19 || sourceNode == 5` → REJECT "self_echo"
  2. `func == 0x60 && index == 0x00` → REJECT "long_data_template"
  3. `op in 4..6 && destNode != 19` → REJECT "long_data_not_for_us"
  4. `op in 4..6 && destNode == 19` → ACCEPT
  5. `destNode != 31 && destNode != 19 && func != 99` → REJECT "wrong_dest"
  6. else → ACCEPT

### CanBleLogger

- `logNotification(raw)` — hex dump raw
- `logDecrypted(encryptedHex, decryptedHex)` — encrypted vs decrypted
- `logFrame(frame)` — structured CAN frame summary
- `logFiltered(frame, reason)` — rejected frame + reason
- `logSessionState(state)` — current aU, handshake state, mtu
- `logMetrics(metrics)` — periodic summary every 10s
- `startFileDump()` — creates `canble_debug_<timestamp>.log` in `filesDir/`
- `flushToFile()` — batch write every 5s
- `stopFileDump()` — flush + close

Metrics collected:
- notificationsPerSec
- totalNotifications
- totalFrames
- acceptedFrames / filteredFrames
- uniqueCanIndexes (set of `func:index`)
- uniqueSourceNodes / uniqueDestNodes
- handshakeDurationMs
- decryptFailures

### CanBleHandshake

- State machine: `IDLE → RAND1_SENT → RAND2_SENT → MTU_SENT → READY`
- Generates `appRand` (4 bytes via SecureRandom)
- Receives `deviceRand` (4 bytes) and `extra` (6 bytes)
- Derives 16-byte dynamic key:

```
for i in 0..3:
  key[i*3]   = appRand[i]
  key[i*3+1] = deviceRand[i]
  key[i*3+2] = extra[i]
key[12] = 0x55
key[13] = 0xAA
key[14] = extra[4]
key[15] = extra[5]
```

- Calls `crypto.setDynamicKey(key)` with derived key

### CanBleTransport

- GATT service discovery for CAN BLE UUIDs
- CCC descriptor write for RX and CTRL characteristics
- Notification routing (RX → data, CTRL → handshake)
- `sendControlRaw(data)` — encrypt fixed key, write CTRL
- `sendDataRaw(data)` — encrypt dynamic key (if aU>=2), write TX
- Forwards accepted frames via `onFrameReceived` callback
- Records all notifications via `RecordedCanSession`

### BleRepository Hooks

3 changes in `BleRepository.kt`:

1. **`onServicesDiscovered`** — after NUS service check, check for CAN BLE service
2. **`onCharacteristicChanged`** — before NUS check, route CAN characteristics to CanBleTransport
3. **`handleDisconnectOrFailure`** — call `canBleTransport?.disconnect()`

Total added: ~40 lines. No deletions. No structural changes.

## Success Criteria

- [x] All 5 model files compile and pass code review
- [ ] CryptoProvider interface is clean (3 implementations)
- [ ] FrameExtractor correctly extracts frames from test data
- [ ] FrameFilter correctly accepts/rejects based on rules
- [ ] Logger writes to file, reports metrics every 10s
- [ ] Handshake completes on real M820 hardware in <10s
- [ ] Transport subscribes to CAN BLE notifications
- [ ] AA55/NUS continues to work without changes
- [ ] `canble_debug_*.log` contains meaningful data
- [ ] `canble_session_*.json` captures session data
