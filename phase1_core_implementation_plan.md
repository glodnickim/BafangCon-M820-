# Phase 1 Core Implementation Plan — CAN BLE

**Branch:** `feature/canble-core`
**Version:** 0.042
**Date:** 2026-06-08
**Status:** Plan — no code written yet

---

## 1. Scope

Implement CAN BLE core components that have **no dependency on BLE GATT transport**.
These components handle encryption, frame extraction, frame filtering, and logging.
They can be fully compiled and unit-tested on host JVM (with one Android-dependent
exception: CanBleLogger file dump).

### In Scope

| Component | Why here | Testable on host? |
|---|---|---|
| CryptoProvider (interface) | Contract for all crypto providers | Yes |
| NativeCryptoProvider | JNI bridge to NativeHelper (reflection) | Yes (`supportsCanCrypto()` = false) |
| FallbackCryptoProvider | Pure Kotlin AES fallback | Yes |
| CanBleFrameExtractor | Decrypted bytes → `List<CanBleFrame>` | Yes |
| CanBleFrameFilter | Filter rules from PlBleService | Yes |
| CanBleLogger | Structured logging + metrics + file dump | Partial (metrics yes, file no) |

### Out of Scope (deferred)

- CanBleHandshake (requires BLE writes)
- CanBleTransport (requires BLE GATT)
- BleRepository integration hooks
- Telemetry payload decoding
- Any UI changes

---

## 2. Directory Structure

```
app/src/main/java/com/test/bafangcon/canble/
├── crypto/
│   ├── CryptoProvider.kt              # interface
│   ├── NativeCryptoProvider.kt        # JNI via Class.forName
│   └── FallbackCryptoProvider.kt      # AES/ECB/NoPadding
├── process/
│   ├── CanBleFrameExtractor.kt        # decrypted bytes → frames
│   └── CanBleFrameFilter.kt           # filter rules
├── log/
│   └── CanBleLogger.kt               # structured logging, metrics, file dump
└── model/                             # ALREADY EXISTS — 5 files, not modified

app/src/test/java/com/test/bafangcon/canble/
├── crypto/
│   └── FallbackCryptoProviderTest.kt  # 5+ test cases
├── process/
│   ├── CanBleFrameExtractorTest.kt    # 8 test cases
│   └── CanBleFrameFilterTest.kt       # 7 test cases
└── log/
    └── CanBleLoggerTest.kt            # 3 test cases (metrics only)
```

---

## 3. File Details

### 3.1 `CryptoProvider.kt` — Interface

| Field | Value |
|---|---|
| Package | `com.test.bafangcon.canble.crypto` |
| Dependencies | none |
| Extends | nothing |

```kotlin
interface CryptoProvider {
    fun supportsCanCrypto(): Boolean
    fun encrypt(data: ByteArray, context: Int): ByteArray?
    fun decrypt(data: ByteArray, context: Int): ByteArray?
    fun setDynamicKey(key: ByteArray)
}
```

**Context convention:**
- `context == 1` — fixed key (used for CAN_CONTROL channel, handshake commands)
- `context == 0` — dynamic key (used for CAN_TRANSMIT channel, telemetry data after handshake)

**Contract:**
- `encrypt`/`decrypt` return `null` on failure (caller must handle gracefully).
- `setDynamicKey` may be called from handshake thread; `encrypt`/`decrypt` from notification thread.
- `supportsCanCrypto()` must be callable before any other method.

### 3.2 `NativeCryptoProvider.kt` — JNI via Reflection

| Field | Value |
|---|---|
| Package | `com.test.bafangcon.canble.crypto` |
| Dependencies | `CryptoProvider`, `CryptoProviderStatus`, `com.pairlink.lib.NativeHelper` (reflection only, no compile dep) |

**Added method:**
```kotlin
fun getStatus(): CryptoProviderStatus
```

**Behavior:**
1. In `init`, try `Class.forName("com.pairlink.lib.NativeHelper")`.
2. If found, reflectively resolve `bafangEncry(ByteArray, Int)` and `bafangDecry(ByteArray, Int)`.
3. `supportsCanCrypto()` returns `true` only if both methods were resolved.
4. `encrypt(data, context)` invokes `bafangEncry.invoke(null, data, context)`.
5. `decrypt(data, context)` invokes `bafangDecry.invoke(null, data, context)`.
6. `getStatus()` returns `CryptoProviderStatus.Available`, `.Unavailable(reason)`, or `.Experimental`.
7. `setDynamicKey(key)` is no-op — native library manages its own key state (**assumption**).
8. If reflection fails at any point, `supportsCanCrypto()` returns `false` and all encrypt/decrypt calls return `null`.

**Full logging (6 events):**

| Event | Level | Message |
|---|---|---|
| NativeHelper found | INFO | `NativeHelper found via Class.forName` |
| Method resolved | INFO | `Method bafangEncry(ByteArray, Int) resolved` |
| Method missing | WARN | `Method bafangEncry missing: ...` |
| NativeHelper missing | WARN | `NativeHelper not found — native CAN crypto unavailable` |
| Encryption failed | ERROR | `Encryption failed: ...` |
| Decryption failed | ERROR | `Decryption failed: ...` |

**Assumptions:**
- NativeHelper is in the package `com.pairlink.lib` (from PlBleService decompile).
- The native library is not bundled in BafangCon — NativeCryptoProvider will always fall back.
- Dynamic key management is internal to the native library.

### 3.3 `FallbackCryptoProvider.kt` — EXPERIMENTAL, no real AES

| Field | Value |
|---|---|
| Package | `com.test.bafangcon.canble.crypto` |
| Dependencies | `CryptoProvider`, `CryptoProviderStatus` |
| Annotation | `@ExperimentalCanCrypto` (custom `@RequiresOptIn`) |

**Decision: FallbackCryptoProvider does NOT implement real AES encryption.**

The fixed key `ByteArray(16) { 0x01 }` is sourced from Go+ decompile fill pattern but is **NOT CONFIRMED** against real hardware. Using a wrong key could:
- Cause the display to ignore encrypted commands
- Corrupt the internal CAN bus state
- Require a display power cycle to recover

**Behavior:**
```kotlin
init {
    Log.w(TAG, "╔══════════════════════════════════════════════════╗")
    Log.w(TAG, "║  FALLBACK CRYPTO — EXPERIMENTAL                  ║")
    Log.w(TAG, "║  Fixed enc key (16x 0x01) is UNCONFIRMED         ║")
    Log.w(TAG, "╚══════════════════════════════════════════════════╝")
}

fun getStatus(): CryptoProviderStatus = CryptoProviderStatus.Experimental
override fun supportsCanCrypto(): Boolean = false
override fun encrypt(...): ByteArray? { Log.w(...); return null }
override fun decrypt(...): ByteArray? { Log.w(...); return null }
override fun setDynamicKey(key) { Log.d(...); /* no-op */ }
```

**Documentacja ryzyka (w kodzie):**
```
Ryzyko R1 (CRITICAL): Fixed key 16x 0x01 jest niepotwierdzony.
- Źródło: Go+ decompile fill pattern, PlBleService.fill().
- Status: NOT CONFIRMED. Może różnić się między wersjami firmware.
- Skutek: Wysłanie zaszyfrowanych danych z błędnym kluczem może:
  a) spowodować brak odpowiedzi urządzenia
  b) uszkodzić wewnętrzny stan CAN bus w wyświetlaczu
  c) uniemożliwić dalszą komunikację bez restartu wyświetlacza
- Decyzja: FallbackCryptoProvider zwraca null dla encrypt/decrypt.
  Prawdziwa implementacja AES zostanie dodana dopiero po potwierdzeniu klucza.
```

### 3.4 `CanBleFrameExtractor.kt` — Frame Extraction

| Field | Value |
|---|---|
| Package | `com.test.bafangcon.canble.process` |
| Dependencies | `com.test.bafangcon.canble.model.CanBleFrame` |

**Algorithm** (reverse-engineered from PlBleService):

Decrypted BLE notification contains one or more CAN frames packed sequentially.

```
Byte layout per frame:
┌──────┬──────────────┬──────┬───────┬──────────────┬──────────────┐
│ src  │ dst<<3 | op  │ func │ index │ payload_len  │ payload      │
│ 1 B  │    1 B       │ 1 B  │  1 B  │   1 B        │ payload_len  │
└──────┴──────────────┴──────┴───────┴──────────────┴──────────────┘
```

```kotlin
fun extract(raw: ByteArray): List<CanBleFrame> {
    val frames = mutableListOf<CanBleFrame>()
    var i = 0
    while (i < raw.size) {
        if (i + 5 > raw.size) break          // incomplete header
        val payloadLen = raw[i + 4].toInt() and 0xFF
        val frameSize = payloadLen + 5
        if (i + frameSize > raw.size) break   // incomplete frame
        if (payloadLen > 8) {
            Log.w("CanBleFrameExtractor", "Suspicious payloadLen=$payloadLen at offset=$i")
            // do NOT break — long data frames (op 4..6) can exceed 8 bytes
        }
        val sourceNode  = raw[i].toInt() and 0xFF
        val destNode    = (raw[i+1].toInt() shr 3) and 0x1F
        val op          = raw[i+1].toInt() and 0x07
        val func        = raw[i+2].toInt() and 0xFF
        val index       = raw[i+3].toInt() and 0xFF
        val payload     = raw.copyOfRange(i + 5, i + frameSize)
        frames.add(CanBleFrame(
            sourceNode, destNode, op, func, index, payload,
            payloadLen = payloadLen,
            rawHex = raw.copyOfRange(i, i + frameSize).joinToString("") { "%02x".format(it) }
        ))
        i += frameSize
    }
    return frames
}
```

**Edge cases:**
- Empty data → empty list.
- Single minimal frame (payloadLen=0) → 5-byte frame.
- Multiple frames packed end-to-end → all extracted.
- Truncated frame at end → stop silently, log nothing (transport layer handles errors).
- payloadLen > 8 → **suspicious, log warning, CONTINUE processing** (do NOT break). Swift PlBleService supports long data frames (op 4..6) where payload can exceed standard CAN 8-byte limit.
- Signed byte misinterpretation → always mask with `and 0xFF`.

### 3.5 `CanBleFrameFilter.kt` — Frame Filtering

| Field | Value |
|---|---|
| Package | `com.test.bafangcon.canble.process` |
| Dependencies | `com.test.bafangcon.canble.model.CanBleFrame` |

**Filter rules** (from PlBleService `process_can_data`, lines 2333–2412):

Evaluated in order. First match wins.

| Priority | Rule | Result | Reason string |
|---|---|---|---|
| 1 | `sourceNode == 19` or `sourceNode == 5` | REJECT | `self_echo` |
| 2 | `func == 0x60 && index == 0x00` | REJECT | `long_data_template` |
| 3 | `op in 4..6 && destNode != 19` | REJECT | `long_data_not_for_us` |
| 4 | `op in 4..6 && destNode == 19` | ACCEPT | — |
| 5 | `destNode != 31 && destNode != 19 && func != 99` | REJECT | `wrong_dest` |
| 6 | else | ACCEPT | — |

**API:**
```kotlin
sealed class FilterResult {
    data class Accepted(val frame: CanBleFrame) : FilterResult()
    data class Rejected(val frame: CanBleFrame, val reason: String) : FilterResult()
}

object CanBleFrameFilter {
    fun apply(frame: CanBleFrame): FilterResult
}
```

**Note on node constants:**
- Node 19 = App (the phone/display running BafangCon)
- Node 5 = Unknown (treated as self-echo in PlBleService)
- Node 31 = Broadcast (all nodes on CAN bus)

### 3.6 `CanBleLogger.kt` — Structured Logging + Metrics + File Dump

| Field | Value |
|---|---|
| Package | `com.test.bafangcon.canble.log` |
| Dependencies | `RawCanNotification`, `CanBleFrame`, `CanBleSessionState`, `android.util.Log`, `android.content.Context` |

**API:**
```kotlin
class CanBleLogger(private val context: Context) {

    // File dump lifecycle
    fun startFileDump()              // creates canble_debug_<ts>.log
    fun stopFileDump()               // flush + close
    fun flushToFile()                // batch write buffer

    // Logging methods (all write to logcat + internal buffer)
    fun logNotification(raw: RawCanNotification)
    fun logDecrypted(encryptedHex: String, decryptedHex: String)
    fun logFrame(frame: CanBleFrame)
    fun logFiltered(frame: CanBleFrame, reason: String)
    fun logSessionState(state: CanBleSessionState)
    fun logError(message: String, throwable: Throwable? = null)

    // Metrics
    fun logMetrics()                 // writes current metric snapshot
    fun getMetrics(): CanBleMetrics  // returns snapshot
}

data class CanBleMetrics(
    val notificationsPerSec: Double,
    val totalNotifications: Int,
    val totalFrames: Int,
    val acceptedFrames: Int,
    val filteredFrames: Int,
    val uniqueCanIndexes: Set<String>,
    val uniqueSourceNodes: Set<Int>,
    val uniqueDestNodes: Set<Int>,
    val decryptFailures: Int,
    val handshakeDurationMs: Long?
)
```

**Design decisions:**
- **Metrics thread-safety:** Use `AtomicInteger` for counters, `ConcurrentHashMap.newKeySet()` for sets.
- **File dump:** Write to `context.filesDir/canble-logs/canble_debug_<timestamp>.log`. Buffer in `StringBuilder`, flush every 5s (via coroutine or Timer).
- **Metrics period:** Rolling 10-second window for `notificationsPerSec`.
- **Log format:**
  ```
  [CANBLE] [HH:mm:ss.SSS] NOTIFICATION  len=N raw=hex...
  [CANBLE] [HH:mm:ss.SSS] DECRYPTED     enc=hex... dec=hex...
  [CANBLE] [HH:mm:ss.SSS] FRAME         src->dst op=X idx=XX:XX len=N data=hex...
  [CANBLE] [HH:mm:ss.SSS] FILTERED      src->dst op=X idx=XX:XX reason=why
  [CANBLE] [HH:mm:ss.SSS] METRICS       notif/s=N.N total=N frames=N acc=N flt=N
  ```
- **Testing:** Metrics aggregation logic extracted to allow pure-Kotlin testing. File dump requires Android context and is tested manually on device.

---

## 4. Dependencies Graph

```
                     ┌──────────────────┐
                     │  CryptoProvider   │  (interface)
                     └──────┬───────┬───┘
                            │       │
              ┌─────────────┘       └──────────────┐
              ▼                                      ▼
   ┌─────────────────────┐              ┌───────────────────────┐
   │ NativeCryptoProvider│              │ FallbackCryptoProvider │
   │ (Class.forName)     │              │ (javax.crypto.Cipher) │
   └─────────────────────┘              └───────────────────────┘

   ┌──────────────────────────┐     ┌───────────────────────────┐
   │  CanBleFrameExtractor    │────→│     CanBleFrame (model)   │
   └──────────────────────────┘     └───────────────────────────┘
         depends on

   ┌──────────────────────────┐     ┌───────────────────────────┐
   │  CanBleFrameFilter       │────→│     CanBleFrame (model)   │
   └──────────────────────────┘     └───────────────────────────┘
         depends on

   ┌──────────────────────────┐
   │     CanBleLogger         │────→ RawCanNotification (model)
   │                          │────→ CanBleFrame (model)
   │                          │────→ CanBleSessionState (model)
   │                          │────→ android.content.Context
   └──────────────────────────┘
```

No circular dependencies. No dependency on BleRepository, DeviceViewModel, or Android UI.

---

## 5. Test Strategy

### 5.1 Framework

- JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`) — already configured in `build.gradle.kts`.
- No Mockito, no Robolectric, no Android dependencies in test code.
- Tests run on host JVM via `./gradlew test`.

### 5.2 Test Cases

| Test class | Cases | What it verifies |
|---|---|---|
| `FallbackCryptoProviderTest` | 4 | 1) `supportsCanCrypto()` returns false; 2) `encrypt()` returns null; 3) `decrypt()` returns null; 4) `getStatus()` returns `Experimental` |
| `NativeCryptoProviderTest` | 4 | 1) `supportsCanCrypto()` returns false (no native lib); 2) `encrypt()` returns null; 3) `decrypt()` returns null; 4) `getStatus()` returns `Unavailable` |
| `CanBleFrameExtractorTest` | 9 | 1) empty array → empty list; 2) single minimal frame (payloadLen=0); 3) single frame with 8-byte payload; 4) two frames packed; 5) truncated frame at end; 6) payloadLen > 8 → suspicious, extract anyway; 7) payloadLen as signed byte (value > 127); 8) frame with all fields at boundary values; 9) long data frame with op=4 and payloadLen > 8 |
| `CanBleFrameFilterTest` | 7 | 1) rule 1: sourceNode=19; 2) rule 1: sourceNode=5; 3) rule 2: func=0x60, index=0; 4) rule 3: op=4, destNode=2; 5) rule 4: op=4, destNode=19; 6) rule 5: destNode=2, func=50; 7) rule 6: destNode=31 (broadcast) |
| `CanBleLoggerTest` | 3 | 1) metric counters increment correctly; 2) metrics snapshot format; 3) no crash with file dump disabled |

### 5.3 Test Data Strategy

- Byte arrays constructed inline with `byteArrayOf(...)` — no external data files.
- Realistic CAN frame patterns derived from PlBleService decompile.
- No Android `Context` needed in extractor/filter/crypto tests.
- Logger file dump tests: skip on host, verify on device only.

---

## 6. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | FallbackCryptoProvider fixed key (16x `0x01`) is wrong for target firmware | Medium | High | Mark EXPERIMENTAL in code; `NativeCryptoProvider` preferred; log warning on every encrypt/decrypt; document as unconfirmed |
| R2 | Zero-padding destroys data that naturally ends in `0x00` | Low | Medium | Document limitation; only affects payloads with trailing zero bytes in plaintext |
| R3 | `setDynamicKey` thread safety (called from handshake, used from notification) | Low | Medium | Synchronized access on key field; handshake completes before notifications arrive |
| R4 | FrameExtractor treats payloadLen > 8 as error, dropping long data frames (op 4..6) | Medium | High | Changed behavior: log warning + continue. Long data frames are extracted despite exceeding standard CAN 8-byte limit |
| R5 | Filter rule semantics differ between decompile versions | Medium | Low | Rules match PlBleService v1.3.4 exactly; update if new decompile shows different logic |
| R6 | NativeCryptoProvider reflection fails on different ART versions | Low | Low | Graceful fallback — `supportsCanCrypto()` returns false, no crash |
| R7 | CanBleLogger file I/O on main thread blocks UI | Low | Medium | Use coroutine or Timer for periodic flush; `startFileDump` only creates file (fast path) |

---

## 7. Implementation Order

```
Step  1: crypto/CryptoProvider.kt           ← interface contract
──────────────────────────────────────────────────────────────────
Step  2: crypto/FallbackCryptoProvider.kt   ← implementable immediately
Step  3: crypto/FallbackCryptoProviderTest.kt ← verify encrypt/decrypt/padding
──────────────────────────────────────────────────────────────────
Step  4: crypto/NativeCryptoProvider.kt     ← reflection-based
──────────────────────────────────────────────────────────────────
Step  5: process/CanBleFrameExtractor.kt    ← pure logic
Step  6: process/CanBleFrameExtractorTest.kt ← 8 test cases
──────────────────────────────────────────────────────────────────
Step  7: process/CanBleFrameFilter.kt       ← rule table
Step  8: process/CanBleFrameFilterTest.kt   ← 7 test cases
──────────────────────────────────────────────────────────────────
Step  9: log/CanBleLogger.kt                ← metrics + file dump
Step 10: log/CanBleLoggerTest.kt            ← metrics only
──────────────────────────────────────────────────────────────────
Step 11: .\gradlew.bat clean assembleDebug test  ← full verification
```

---

## 8. Verification

```bash
# Full build + tests
.\gradlew.bat clean assembleDebug test

# Expected results:
# - BUILD SUCCESSFUL
# - All tests pass (X tests, 0 failures)
# - No compilation errors
# - No new warnings (if avoidable)
```

### Success Criteria

- [ ] CryptoProvider interface compiles and is usable.
- [ ] CryptoProviderStatus sealed class with Available, Unavailable, Experimental states.
- [ ] FallbackCryptoProvider marked @ExperimentalCanCrypto, returns null for all crypto.
- [ ] FallbackCryptoProvider.getStatus() returns Experimental.
- [ ] NativeCryptoProvider detects missing native library and falls back.
- [ ] NativeCryptoProvider.getStatus() returns Unavailable with reason string.
- [ ] NativeCryptoProvider logs all 6 events (found, missing, resolved, encrypt fail, decrypt fail).
- [ ] CanBleFrameExtractor correctly extracts 0, 1, 2+ frames from test byte arrays.
- [ ] CanBleFrameExtractor handles truncated and malformed data without crashing.
- [ ] CanBleFrameFilter correctly accepts/rejects each rule case.
- [ ] CanBleLogger metric aggregation works correctly over multiple calls.
- [ ] All unit tests pass on host JVM.
- [ ] Existing AA55/NUS functionality is unaffected (build still succeeds).
