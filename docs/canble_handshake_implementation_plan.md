# CanBleHandshake — Implementation Plan (Phase 1)

> **Branch:** `feature/canble-handshake`
> **Source verification:** `rand1_verification_report.md` (all RAND1/RAND2/MTU flows confirmed from PlBleService.java)

---

## 1. Lista plików

### Nowe pliki

| Plik | Lokalizacja | Odpowiedzialność |
|---|---|---|
| `HandshakeTimer.kt` | `canble/handshake/` | Interface dla abstrakcji timera (pure Java, ScheduledExecutorService) |
| `HandshakeRetryConfig.kt` | `canble/handshake/` | Data class dla konfiguracji retry |
| `HandshakeFailure.kt` | `canble/handshake/` | Sealed class dla typów błędów |
| `CanBleHandshake.kt` | `canble/handshake/` | Główna klasa — state machine + logika handshake |

### Modyfikowane pliki

| Plik | Zmiana |
|---|---|
| `CanBleSessionState.kt` | Aktualizacja `HandshakeState` enum — dodanie DISCOVERED, DEVICE_RANDOM_RECEIVED, DYNAMIC_KEY_DERIVED, MTU_REQUEST_SENT; usunięcie RAND2_SENT, MTU_SENT |

### Pliki testowe

| Plik | Testy |
|---|---|
| `CanBleHandshakeTest.kt` | Wszystkie testy jednostkowe + integracyjne state machine, timeout, retry, key derivation |
| (opcjonalnie) `FakeHandshakeTimer.kt` | Fake timer dla testów |

---

## 2. Public API

```kotlin
class CanBleHandshake(
    private val crypto: CryptoProvider,
    private val timer: HandshakeTimer,
    private val retryConfig: HandshakeRetryConfig = HandshakeRetryConfig()
) {
    // ── Stan ──────────────────────────────────────
    val sessionState: CanBleSessionState     // snapshot obecnego stanu
    val currentState: HandshakeState         // obecny stan maszyny

    // ── Lifecycle ─────────────────────────────────
    fun start()                              // IDLE → DISCOVERED → RAND1_SENT
    fun resetAndRetry()                      // FAILED → DISCOVERED → RAND1_SENT
    fun reset()                              // Any → IDLE (full clean)
    fun destroy()                            // Zwalnia zasoby, anuluje timery

    // ── Input od transportu ───────────────────────
    fun onControlNotificationReceived(encryptedData: ByteArray)
    // Wywoływane przez transport po otrzymaniu notyfikacji na UUID_CAN_CONTROL

    // ── Output callbacki do transportu ────────────
    var onWriteRequired: ((uuid: java.util.UUID, data: ByteArray) -> Unit)?
    // Handshake potrzebuje wysłać dane na GATT characteristic

    var onHandshakeComplete: ((CanBleSessionState) -> Unit)?
    // Handshake zakończony sukcesem — READY, aU=0

    var onHandshakeFailed: ((failure: HandshakeFailure, attempt: Int, maxRetries: Int) -> Unit)?
    // Handshake zakończony błędem — transport decyduje o retry lub disconnect
}
```

### Użycie przez transport

```kotlin
// 1. Transport konfiguruje callbacki
handshake.onWriteRequired = { uuid, data ->
    // write to GATT characteristic
    gatt.writeCharacteristic(uuid, data)
}
handshake.onHandshakeComplete = { state ->
    // rozpocznij frame processing pipeline
    logger.log("Handshake complete: $state")
}
handshake.onHandshakeFailed = { failure, attempt, maxRetries ->
    if (attempt < maxRetries) {
        // odczekaj backoff, potem handshake.resetAndRetry()
    } else {
        // disconnect lub powiadom użytkownika
    }
}

// 2. Gdy BLE ready (service + chars + notifications subscribed)
handshake.start()

// 3. Na każdą notyfikację CAN_CONTROL z BLE
handshake.onControlNotificationReceived(encryptedData)

// 4. Na disconnect
handshake.reset()
```

---

## 3. State machine

```
                         ╔══════════════════════════════════════════╗
                         ║            LEGENDA                      ║
                         ║  [akcja lokalna]                        ║
                         ║  {I/O → callback onWriteRequired}       ║
                         ╚══════════════════════════════════════════╝

  ┌──────┐   start()    ┌────────────┐   [generate dyncRand1]    ┌────────────┐
  │ IDLE │─────────────►│ DISCOVERED │───────────────────────────►│ RAND1_SENT │
  └──────┘              └────────────┘   {encrypt+write 0x01}    └─────┬──────┘
       ▲                                                              │
       │                                              onControlNotify │
       │                                              decrypt 0x02   │
       │                                                    ▼        │
       │                                              ┌───────────────┴───┐
       │                                              │ DEVICE_RANDOM_    │
       │                                              │ RECEIVED          │
       │                                              └───────┬───────────┘
       │                                                      │
       │                                              [derive key]
       │                                              [setDynamicKey]
       │                                                      ▼
       │                                              ┌───────────────┐
       │                                              │ DYNAMIC_KEY_  │
       │                                              │ DERIVED       │
       │                                              └───────┬───────┘
       │                                                      │
       │                                              {encrypt+write 0x08}
       │                                                      ▼
       │                                              ┌───────────────┐
       │                                              │ MTU_REQUEST_  │
       │                                              │ SENT          │
       │                                              └───────┬───────┘
       │                                                      │
       │                                              onControlNotify │
       │                                              decrypt 0x09   │
       │                                                      ▼
       │                                              ┌───────────────┐
       │                                              │    READY      │
       │                                              │   (aU = 0)    │
       │                                              └───────┬───────┘
       │                                                      │
       │                                              onHandshakeComplete
       │                                                      │
       │              ┌────────┐    timeout / decrypt null     │
       └──────────────│ FAILED │◄──────────────────────────────┘
                      └────┬───┘
                           │
                    resetAndRetry()  ──►  DISCOVERED
                           │
                    reset()  ──►  IDLE
```

### Szczegółowe przejścia

| # | From | To | Trigger | Akcje |
|---|---|---|---|---|
| 1 | `IDLE` | `DISCOVERED` | `start()` | Ustaw `sessionState = state.copy(aU=-1, handshakeState=DISCOVERED)`, timer DISCOVERED start |
| 2 | `DISCOVERED` | `RAND1_SENT` | Auto | `appRand = 4 random bytes`. Build `[0x01, appRand[0..3]]`. Fixed-key encrypt. `onWriteRequired(CAN_CONTROL, encrypted)`. Timer DISCOVERED stop, timer RAND1_SENT start |
| 3 | `RAND1_SENT` | `DEVICE_RANDOM_RECEIVED` | `onControlNotificationReceived(data)` | `if data.length % 16 != 0 → FAILED`. Fixed-key decrypt. `if decrypted[0] != 0x02 → FAILED`. `if decrypted.length < 11 → FAILED`. Extract `deviceRand = decrypted[1..4]`, `extra = decrypted[5..10]`. Timer stop |
| 4 | `DEVICE_RANDOM_RECEIVED` | `DYNAMIC_KEY_DERIVED` | Auto | Derive `key[16]` z `appRand + deviceRand + extra`. `crypto.setDynamicKey(key)`. `sessionState = sessionState.copy(dynamicKey=key)` |
| 5 | `DYNAMIC_KEY_DERIVED` | `MTU_REQUEST_SENT` | Auto | Build `[0x08]`. Fixed-key encrypt. `onWriteRequired(CAN_CONTROL, encrypted)`. Timer MTU_REQUEST_SENT start |
| 6 | `MTU_REQUEST_SENT` | `READY` | `onControlNotificationReceived(data)` | Fixed-key decrypt. `if decrypted[0] != 0x09 → FAILED`. `sessionState = sessionState.copy(aU=0, handshakeState=READY)`. Timer stop. `onHandshakeComplete(sessionState)` |
| 7 | Any active | `FAILED` | Timeout/decrypt null/invalid response | `onHandshakeFailed(reason, attempt, maxRetries)`. Timer stop |
| 8 | `FAILED` | `DISCOVERED` | `resetAndRetry()` (if attempts remain) | `appRand = new random`. Reset key. IDLE→DISCOVERED→RAND1_SENT flow z nowym randomem |
| 9 | `FAILED` | `IDLE` | `reset()` | Anuluj timery. `sessionState = fresh` |
| 10 | Any | `IDLE` | `reset()` | Anuluj timery. `sessionState = fresh` |

### Notatka o DISCOVERED

Stan `DISCOVERED` jest bardzo krótkotrwały w normalnym przepływie — `start()` natychmiast przechodzi do `RAND1_SENT`. Jest to jednak osobny stan z dwóch powodów:
- Umożliwia testowanie, że handshake czeka na "gotowość" przed wysłaniem
- Przy retry: transport może odczekać przed wywołaniem `resetAndRetry()`, a DISCOVERED sygnalizuje "gotowy ale jeszcze nie wysłano"

---

## 4. Callback API transportu

### Output (handshake → transport)

```kotlin
// Handshake potrzebuje wysłać dane BLE
var onWriteRequired: ((uuid: UUID, data: ByteArray) -> Unit)?

// Handshake zakończony sukcesem
var onHandshakeComplete: ((sessionState: CanBleSessionState) -> Unit)?

// Handshake zakończony błędem
var onHandshakeFailed: ((failure: HandshakeFailure, attempt: Int, maxRetries: Int) -> Unit)?
```

### Input (transport → handshake)

```kotlin
// Transport otrzymał notyfikację na UUID_CAN_CONTROL
fun onControlNotificationReceived(encryptedData: ByteArray)
```

### Przepływ danych

```
  ┌─────────────────────────────────────────────────────────────────┐
  │                        TRANSPORT                                │
  │                                                                 │
  │  1. GATT onServicesDiscovered                                   │
  │     → sprawdza CAN BLE service                                  │
  │     → subskrybuje notyfikacje CAN_CONTROL, CAN_RX              │
  │                                                                 │
  │  2. Wszystkie CCCD zapisane                                    │
  │     → handshake.start()                                        │
  │                                                                 │
  │  3. onCharacteristicChanged(CAN_CONTROL, data)                  │
  │     → handshake.onControlNotificationReceived(data)            │
  │                                                                 │
  │  4. callback handshake: onWriteRequired(uuid, data)             │
  │     → gatt.writeCharacteristic(uuid, data)                     │
  │                                                                 │
  │  5. callback handshake: onHandshakeComplete(state)              │
  │     → zaczyna przetwarzać CAN_RX notyfikacje                   │
  │     → opcjonalnie: FW read + encry channel                     │
  │                                                                 │
  │  6. callback handshake: onHandshakeFailed(...)                  │
  │     → retry lub disconnect                                     │
  └─────────────────────────────────────────────────────────────────┘
```

### Konwencje

- Callbacki są wywoływane na tym samym wątku co `onControlNotificationReceived` lub na wątku timera
- Implementacja transportu musi zapewnić thread safety (BLE callbacki przychodzą z różnych wątków)
- Preferowane: `HandshakeTimer` używa `ScheduledExecutorService` z pojedynczym wątkiem

---

## 5. Timeout handling

### 5.1 HandshakeTimer interface

```kotlin
interface TimerHandle {
    val isActive: Boolean
    fun cancel(): Boolean
}

interface HandshakeTimer {
    fun schedule(delayMs: Long, callback: () -> Unit): TimerHandle
    fun cancel(handle: TimerHandle)
    fun currentTimeMs(): Long
}
```

### 5.2 Per-state timeout values

| Stan | Domyślny timeout | Uzasadnienie |
|---|---|---|---|
| `RAND1_SENT` | 5_000 ms | Urządzenie powinno szybko odpowiedzieć na RAND1 |
| `DEVICE_RANDOM_RECEIVED` | 1_000 ms | Tylko lokalne obliczenia |
| `DYNAMIC_KEY_DERIVED` | 1_000 ms | Tylko lokalne obliczenia + write |
| `MTU_REQUEST_SENT` | 5_000 ms | Urządzenie powinno szybko odpowiedzieć na GET_MTU |

### 5.3 Mechanizm

- Timer jest uruchamiany przy wejściu do każdego stanu I/O (DISCOVERED, RAND1_SENT, MTU_REQUEST_SENT)
- Przy udanym przejściu: timer anulowany, nowy timer dla nowego stanu
- Przy timeout: `onHandshakeFailed(Timeout(currentState), attempt, maxRetries)`
- Produkcja: `ScheduledExecutorService.schedule()` → `ScheduledFuture<?>` jako TimerHandle
- Test: fake timer z `advanceTimeBy(delayMs)` → natychmiastowe wywołanie callbacka

---

## 6. Retry handling

### 6.1 HandshakeRetryConfig

```kotlin
data class HandshakeRetryConfig(
    val maxRetries: Int = 2,
    val baseDelayMs: Long = 1000L,
    val maxDelayMs: Long = 4000L
) {
    fun delayForAttempt(attempt: Int): Long =
        (baseDelayMs * attempt).coerceAtMost(maxDelayMs)
}
```

### 6.2 Retry policy

| Error type | Retry? | Opis |
|---|---|---|
| Timeout (RAND1_SENT) | Tak | Do maxRetries. Może być przejściowy. |
| Timeout (MTU_REQUEST_SENT) | Tak | Do maxRetries. |
| Invalid response (wrong cmd byte) | Tak | Do maxRetries. |
| Invalid response (short payload) | Tak | Do maxRetries. |
| Decrypt failure (handshake) | Tak | **1 retry**, potem FAILED. PlBleService nie ma retry, ale dodajemy 1 dla odporności. |
| Crypto unavailable | **Nie** | Terminal — brak możliwości szyfrowania CAN_CONTROL. |
| Invalid key derivation (bad deviceRand/extra size) | **Nie** | Terminal — urządzenie wysłało złe dane. |

### 6.3 Retry flow

```kotlin
// W callback onHandshakeFailed:
if (failure.isRetryable() && attempt < maxRetries) {
    val delay = retryConfig.delayForAttempt(attempt)
    timer.schedule(delay) {
        resetAndRetry()  // → DISCOVERED → nowy appRand → RAND1_SENT
    }
} else {
    // Terminal FAILED — transport decyduje o disconnect
}
```

### 6.4 resetAndRetry() flow

1. Sprawdź czy `attempt < maxRetries` — jeśli nie, pozostaw w FAILED
2. Anuluj wszystkie aktywne timery
3. Zwiększ licznik attempt
4. Wygeneruj nowy appRand (4 losowe bajty)
5. `sessionState = sessionState.copy(dynamicKey = null, handshakeState = DISCOVERED)`
6. Natychmiast przejdź do RAND1_SENT (wyślij RAND1 z nowym randomem)
---



## 7. Test strategy

### 7.1 Test infrastructure

| Komponent | Fake | Odpowiedzialność |
|---|---|---|
| `CryptoProvider` | `FakeCryptoProvider` | Konfigurowalne wyniki encrypt/decrypt. Track `setDynamicKey` calls. |
| `HandshakeTimer` | `FakeHandshakeTimer` | Manualna kontrola czasu. `advanceTimeBy(delayMs)` → callback. |
| Transport callbacks | `CallbackRecorder` | Rejestruje wywołania `onWriteRequired`, `onHandshakeComplete`, `onHandshakeFailed`. Może injectować notyfikacje. |

### 7.2 Structure of FakeHandshakeTimer

```kotlin
class FakeHandshakeTimer : HandshakeTimer {
    private val scheduled = PriorityQueue<ScheduledCallback>()
    var currentTimeMs: Long = 0L

    fun advanceTimeBy(delayMs: Long) {
        currentTimeMs += delayMs
        // execute all callbacks whose scheduled time <= currentTimeMs
    }

    override fun schedule(delayMs: Long, callback: () -> Unit): TimerHandle {
        val handle = FakeTimerHandle(...)
        scheduled.add(ScheduledCallback(at = currentTimeMs + delayMs, callback, handle))
        return handle
    }
}
```

### 7.3 Test cases

#### State machine (7 tests)

| # | Test | Scenariusz | Asercje |
|---|---|---|---|
| T1 | Happy path | `start()` → check RAND1 sent → inject device response(0x02) → check key derived → check MTU sent → inject MTU response(0x09) → READY | `onWriteRequired` × 2, `onHandshakeComplete` z aU=0 |
| T2 | IDLE → DISCOVERED → RAND1_SENT | `start()` | Stan = RAND1_SENT, `onWriteRequired` z [0x01 + 4 random bytes], encrypted |
| T3 | RAND1_SENT → DEVICE_RANDOM_RECEIVED | Inject poprawną odpowiedź 0x02 | Stan = DEVICE_RANDOM_RECEIVED, deviceRand+extra poprawne |
| T4 | DEVICE_RANDOM_RECEIVED → DYNAMIC_KEY_DERIVED | Automatyczne przejście | `setDynamicKey` wywołane z poprawnym kluczem |
| T5 | DYNAMIC_KEY_DERIVED → MTU_REQUEST_SENT | Automatyczne przejście | `onWriteRequired` z [0x08] encrypted |
| T6 | MTU_REQUEST_SENT → READY | Inject poprawną odpowiedź 0x09 | Stan = READY, aU=0, `onHandshakeComplete` |
| T7 | Pełny happy path end-to-end | Wszystkie kroki po kolei | Finalnie READY |

#### Timeout (4 tests)

| # | Test | Scenariusz | Asercje |
|---|---|---|---|
| T8 | RAND1_SENT timeout | `start()` → `advanceTimeBy(5000)` | `onHandshakeFailed(Timeout(RAND1_SENT), 1, 2)` |
| T9 | MTU_REQUEST_SENT timeout | Happy path do MTU_REQUEST_SENT → `advanceTimeBy(5000)` | `onHandshakeFailed(Timeout(MTU_REQUEST_SENT), 1, 2)` |
| T10 | DISCOVERED timeout | `start()` bez natychmiastowego przejścia (mock) → `advanceTimeBy(10000)` | `onHandshakeFailed(Timeout(DISCOVERED), ...)` |
| T10 | Brak timeoutu po udanym przejściu | RAND1_SENT → inject response → timer nie wywołany | Brak fail callbacka |

#### Invalid responses (5 tests)

| # | Test | Scenariusz | Asercje |
|---|---|---|---|
| T12 | Wrong command byte | RAND1_SENT → inject 0x08 zamiast 0x02 | FAILED(InvalidResponse) |
| T13 | Short payload | RAND1_SENT → inject 5 bajtów zamiast 11 | FAILED(InvalidResponse) |
| T14 | Non-16-byte notification | RAND1_SENT → inject 15 bajtów (not % 16) | FAILED(InvalidResponse) |
| T15 | Wrong command byte MTU | MTU_REQUEST_SENT → inject 0x02 zamiast 0x09 | FAILED(InvalidResponse) |
| T16 | Empty payload | RAND1_SENT → inject 0 bajtów | FAILED(InvalidResponse) |

#### Decrypt failures (4 tests)

| # | Test | Scenariusz | Asercje |
|---|---|---|---|
| T17 | Decrypt fail once, retry succeeds | RAND1_SENT → decrypt null → retry → decrypt succeeds → READY | `onHandshakeComplete` po retry |
| T18 | Decrypt fail twice | RAND1_SENT → decrypt null → retry → decrypt null | Terminal FAILED |
| T19 | Decrypt fail on MTU response | MTU_REQUEST_SENT → decrypt null → retry → decrypt succeeds → READY | `onHandshakeComplete` |
| T20 | Post-handshake decrypt ignored | READY → inject data → decrypt null | Stan wciąż READY (brak callbacka) |

Wait, T20 is about post-handshake behavior. In our design, post-handshake CAN_CONTROL notifications are handled by the transport, not by the handshake. The handshake's `onControlNotificationReceived` should return immediately if in READY state. Let me adjust.

Actually, the handshake should still process CAN_CONTROL notifications in READY state for the encryption channel response (0x13). But decrypt failures in READY should be ignored.

Let me reconsider: in PlBleService, CAN_CONTROL notifications are ALWAYS processed (even after READY) for encryption channel negotiation. But our design says encryption channel is handled by the transport, not by handshake.

So the handshake has two modes:
1. **Active handshake** (pre-READY): notifications drive state machine, failures trigger retry
2. **Post-handshake** (READY): notifications are ignored by the handshake, transport handles them directly

This means `onControlNotificationReceived` should:
- If NOT READY: process as part of state machine
- If READY: silently return (transport handles directly with its own crypto calls)

So T20 doesn't apply to handshake — the handshake simply ignores post-READY notifications.

#### Retry (5 tests)

| # | Test | Scenariusz | Asercje |
|---|---|---|---|
| T21 | Single retry succeeds | RAND1_SENT → timeout → retry → RAND1_SENT → inject response → READY | `onHandshakeComplete` po retry |
| T22 | Max retries exhausted | RAND1_SENT → timeout × 3 | Terminal FAILED(MaxRetriesExceeded) |
| T23 | Crypto unavailable on start | `supportsCanCrypto() = false` → `start()` | FAILED(CryptoUnavailable) |

#### Key derivation (2 tests)

| # | Test | Scenariusz | Asercje |
|---|---|---|---|
| T25 | Known inputs produce known key | appRand=A, deviceRand=B, extra=C → key = expected | `setDynamicKey` z poprawnym kluczem |
| T26 | Random bytes change each attempt | `start()` × 2 → różne appRand | Różne payloady w RAND1 |

#### App random generation (1 test)

| # | Test | Scenariusz | Asercje |
|---|---|---|---|
| T27 | appRand ma 4 bajty | `start()` → sprawdź payload RAND1 | `onWriteRequired` data[1..4] ma 4 bajty |

#### Reset (2 tests)

| # | Test | Scenariusz | Asercje |
|---|---|---|---|
| T28 | `reset()` w trakcie handshake | RAND1_SENT → `reset()` | Stan = IDLE, timery anulowane |
| T29 | `destroy()` czyści wszystko | RAND1_SENT → `destroy()` | Stan = IDLE, timery anulowane, callbacki null |

### 7.4 Podsumowanie testów

Łącznie: **27 testów** (7 state machine + 3 timeout + 5 invalid responses + 4 decrypt failures + 4 retry + 2 key derivation + 1 appRand + 2 reset)

Wszystkie testy JVM, bez Android dependencies.

---

## 8. Dependency graph

```
canble/model/
  CanBleFrame.kt                    ← brak zależności
  CanBleSessionState.kt             ← brak zależności (modyfikacja HandshakeState enum)
  RawCanNotification.kt             ← brak zależności
  RecordedCanNotification.kt        ← brak zależności
  RecordedCanSession.kt             ← RecordedCanNotification

canble/crypto/
  CryptoProvider.kt                 ← brak zależności (interface)
  CryptoProviderStatus.kt           ← brak zależności
  NativeCryptoProvider.kt           ← CryptoProvider, CryptoProviderStatus
  FallbackCryptoProvider.kt         ← CryptoProvider, CryptoProviderStatus

canble/handshake/                   ← NOWY PAKIET
  HandshakeTimer.kt                 ← brak zależności (interface)
  HandshakeRetryConfig.kt           ← brak zależności
  HandshakeFailure.kt               ← brak zależności
  CanBleHandshake.kt                ← CryptoProvider, HandshakeTimer, HandshakeRetryConfig,
                                     HandshakeFailure, CanBleSessionState
                                     UUID (java.util)

canble/process/
  CanBleFrameExtractor.kt           ← CanBleFrame
  CanBleFrameFilter.kt              ← CanBleFrame
  CanBleLogger.kt                   ← CanBleFrame, RawCanNotification, CanBleSessionState

canble/replay/
  CanBleReplayEngine.kt             ← RecordedCanSession, RecordedCanNotification, CanBleFrame
                                     CanBleFrameFilter, CanBleFrameExtractor, CanBleMetricsCollector

canble/ (planned)
  CanBleTransport.kt                ← ALL (future Phase 2)
```

### Zależności CanBleHandshake

```
CanBleHandshake
  ├── CryptoProvider          (interface — encrypt/decrypt z fixed key, setDynamicKey)
  ├── HandshakeTimer          (interface — schedule/cancel timeout)
  ├── HandshakeRetryConfig    (data class — maxRetries, backoff)
  ├── HandshakeFailure        (sealed class — typy błędów)
  └── CanBleSessionState      (data class — aU, dynamicKey, handshakeState, mtu)
       └── HandshakeState     (enum — stany maszyny)
```

### Co NIE jest zależnością

- `android.bluetooth.BluetoothGatt` ❌
- `android.content.Context` ❌
- `android.os.Handler` ❌
- `java.util.Timer`/`TimerTask` ❌ (używamy ScheduledExecutorService)
- `BleRepository` ❌
- `CanBleFrameExtractor` ❌
- `CanBleFrameFilter` ❌
- `CanBleLogger` ❌
- `CanBleReplayEngine` ❌
- `RecordedCanSession` ❌

---

## Appendix A: Sequence flow (kompletny)

```
Krok  Transport                   CanBleHandshake              CryptoProvider
────  ──────────────────────────  ──────────────────────────  ─────────────────
1.    GATT: services discovered
2.    GATT: enable notifications
3.    handshake.start() ────────► state = DISCOVERED
                                 generate appRand(4B)
                                 state = RAND1_SENT
                                 build [0x01, appRand]
                                 encrypt([0x01, appRand]) ──► bafangEncry(data, 5, 1)
                                 ◄── encrypted(16B) ────────
    ◄── onWriteRequired ◄────────
4.    GATT: write CAN_CONTROL
5.    GATT: notif CAN_CONTROL
      onControlNotification ────► decrypt(encrypted, 1) ────► bafangDecry(data, 16, 1)
                                 ◄── [0x02, devRand(4),     ◄─── decrypted(11B-16B)
                                 │          extra(6)]
                                 state = DEVICE_RANDOM_RECEIVED
                                 extract devRand + extra
                                 state = DYNAMIC_KEY_DERIVED
                                 derive key(16B)
                                 setDynamicKey(key) ─────────► native setDynamicKey
                                 state = MTU_REQUEST_SENT
                                 build [0x08]
                                 encrypt([0x08]) ───────────► bafangEncry(data, 1, 1)
                                 ◄── encrypted(16B) ────────
    ◄── onWriteRequired ◄────────
6.    GATT: write CAN_CONTROL
7.    GATT: notif CAN_CONTROL
      onControlNotification ────► decrypt(encrypted, 1) ────► bafangDecry(data, 16, 1)
                                 ◄── [0x09] ◄────────────────
                                 state = READY, aU=0
    ◄── onHandshakeComplete ◄────
```

## Appendix B: Zmiany w CanBleSessionState.HandshakeState

```kotlin
// STARY enum:
enum class HandshakeState {
    IDLE,
    RAND1_SENT,
    RAND2_SENT,       // ← do usunięcia (nie używany, device wysyła 0x02, nie app)
    MTU_SENT,         // ← do usunięcia (zastąpiony przez MTU_REQUEST_SENT)
    READY,
    FAILED
}

// NOWY enum:
enum class HandshakeState {
    IDLE,
    DISCOVERED,               // ← NOWY
    RAND1_SENT,
    DEVICE_RANDOM_RECEIVED,   // ← NOWY
    DYNAMIC_KEY_DERIVED,      // ← NOWY
    MTU_REQUEST_SENT,         // ← NOWY (zastępuje MTU_SENT)
    READY,
    FAILED
}
```

## Appendix C: Key derivation (do CanBleHandshake.kt)

```kotlin
private fun deriveKey(
    appRand: ByteArray,
    deviceRand: ByteArray,
    extra: ByteArray
): ByteArray {
    val key = ByteArray(16)
    for (i in 0 until 4) {
        key[i * 3]     = appRand[i]
        key[i * 3 + 1] = deviceRand[i]
        key[i * 3 + 2] = extra[i]
    }
    key[12] = 0x55
    key[13] = 0xAA.toByte()
    key[14] = extra[4]
    key[15] = extra[5]
    return key
}
```
