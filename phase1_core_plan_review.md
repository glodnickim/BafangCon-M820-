# Phase 1 Core Plan Review

**Plan reviewed:** `phase1_core_implementation_plan.md`
**Branch:** `feature/canble-core`
**Review date:** 2026-06-08
**Status:** Corrections applied — awaiting approval

---

## 1. Zaakceptowane elementy (Accepted)

| Element | Uzasadnienie |
|---|---|
| CryptoProvider interface z `encrypt`/`decrypt`/`setDynamicKey`/`supportsCanCrypto` | Czysty kontrakt, separacja implementacji |
| Podział na 3 providery (interface + Native + Fallback) | Pozwala na graceful degradation |
| CanBleFrameExtractor jako osobna klasa | Łatwe testowanie, brak side effects |
| CanBleFrameFilter jako `object` z `sealed class FilterResult` | Czyste API, brak wyjątków w sygnaturze |
| CanBleLogger z osobnym `CanBleMetrics` data class | Agregacja oddzielona od I/O |
| Struktura katalogów: `crypto/`, `process/`, `log/`, `model/` | Zgodna z istniejącym modelem |
| 3 pakiety testowe: `canble/crypto/`, `canble/process/`, `canble/log/` | Testy unitowe na JVM, bez Androida |
| Kolejność implementacji: interface → implementacje → testy | Zgodna z contract-first |
| `payloadLen` maskowanie `and 0xFF` | Eliminuje signed byte bug |
| Filter rule order: Rule 4 przed Rule 3 | Zgodne z PlBleService — Rule 4 to podprzypadek Rule 3 |
| Brak dependency na BleRepository / UI | Zgodne z zakresem Phase 1 Core |
| `supportsCanCrypto()` jako pierwsze wywołanie przed innymi metodami | Pozwala na graceful degradation przed handshake |

---

## 2. Odrzucone / zmienione elementy (Rejected / Modified)

| Element w planie | Decyzja | Powód |
|---|---|---|
| FallbackCryptoProvider implementuje prawdziwy AES | **ODRZUCONE** | Fixed key 16x `0x01` niepotwierdzony — ryzyko wysłania złych danych na CAN bus |
| FallbackCryptoProvider zwraca `cipher.doFinal(padded)` | **ODRZUCONE** | Zastąpione przez `null` / `NotImplementedError` |
| FrameExtractor `payloadLen > 8 → break` | **ODRZUCONE** | Swift ma long data (op 4..6) z payloadLen > 8; break powoduje utratę danych |
| FrameExtractor traktuje `payloadLen > 8` jako invalid/malformed | **ODRZUCONE** | Zmienione na suspicious + log warning + kontynuacja |
| NativeCryptoProvider ciche init (brak logów) | **ODRZUCONE** | Dodano pełne logowanie każdego kroku refleksji |

---

## 3. Wprowadzone korekty (Corrections)

### 3.1 FallbackCryptoProvider — EXPERIMENTAL, brak rzeczywistego AES

**Stan przed:**
```kotlin
// FallbackCryptoProvider implementował AES/ECB/NoPadding z cipher.doFinal()
// Fixed key: ByteArray(16) { 0x01 }
```

**Stan po:**
```kotlin
@RequiresOptIn
annotation class ExperimentalCanCrypto

@ExperimentalCanCrypto
class FallbackCryptoProvider : CryptoProvider {

    // WARNING: Fixed key 16x 0x01 is UNCONFIRMED.
    // Do NOT use on real hardware until verified against target firmware.
    // This provider returns null for all encrypt/decrypt operations
    // until the key is confirmed.

    override fun supportsCanCrypto(): Boolean = false
    override fun encrypt(data: ByteArray, context: Int): ByteArray? = null
    override fun decrypt(data: ByteArray, context: Int): ByteArray? = null
    override fun setDynamicKey(key: ByteArray) { /* no-op */ }
}
```

**Dokumentacja ryzyka (dodana do kodu i planu):**
```
Ryzyko R1 (CRITICAL): Fixed key 16x 0x01 jest niepotwierdzony.
- Źródło: Go+ decompile fill pattern, PlBleService.fill().
- Status: NOT CONFIRMED. Może różnić się między wersjami firmware.
- Skutek: Wysłanie zaszyfrowanych danych z błędnym kluczem może:
  a) spowodować brak odpowiedzi urządzenia
  b) uszkodzić wewnętrzny stan CAN bus w wyświetlaczu
  c) uniemożliwić dalszą komunikację bez restartu wyświetlacza
- Decyzja: FallbackCryptoProvider zwraca null dla encrypt/decrypt.
  Prawdziwa implementacja AES zostanie dodana dopiero po potwierdzeniu klucza
  (np. przez inżynierię wsteczną firmware lub dokumentację Bafang).
```

**Wpływ na testy:**
- `FallbackCryptoProviderTest` redukcja z 5 do 2 przypadków:
  1) `supportsCanCrypto()` zwraca `false`
  2) `encrypt()` zwraca `null`
  3) `decrypt()` zwraca `null`

### 3.2 NativeCryptoProvider — pełne logowanie

**Stan przed:**
```
Brak logowania — cicha inicjalizacja przez refleksję.
```

**Stan po:**
```kotlin
class NativeCryptoProvider : CryptoProvider {
    private val tag = "NativeCryptoProvider"

    init {
        try {
            val clazz = Class.forName("com.pairlink.lib.NativeHelper")
            Log.i(tag, "NativeHelper found via Class.forName")
            bafangEncry = clazz.getMethod("bafangEncry", ByteArray::class.java, Int::class.java)
            Log.i(tag, "Method bafangEncry(ByteArray, Int) resolved")
            bafangDecry = clazz.getMethod("bafangDecry", ByteArray::class.java, Int::class.java)
            Log.i(tag, "Method bafangDecry(ByteArray, Int) resolved")
            supportsNative = true
            Log.i(tag, "NativeCryptoProvider initialized successfully")
        } catch (e: ClassNotFoundException) {
            Log.w(tag, "NativeHelper not found — native CAN crypto unavailable")
        } catch (e: NoSuchMethodException) {
            Log.w(tag, "NativeHelper method missing: ${e.message}")
        } catch (e: Exception) {
            Log.w(tag, "NativeHelper reflection failed: ${e.message}")
        }
    }

    override fun encrypt(data: ByteArray, context: Int): ByteArray? {
        return try {
            val result = bafangEncry!!.invoke(null, data, context) as ByteArray
            Log.i(tag, "Encrypt OK: ${data.size}B → ${result.size}B context=$context")
            result
        } catch (e: Exception) {
            Log.e(tag, "Encryption failed: ${e.message}", e)
            null
        }
    }

    override fun decrypt(data: ByteArray, context: Int): ByteArray? {
        return try {
            val result = bafangDecry!!.invoke(null, data, context) as ByteArray
            Log.i(tag, "Decrypt OK: ${data.size}B → ${result.size}B context=$context")
            result
        } catch (e: Exception) {
            Log.e(tag, "Decryption failed: ${e.message}", e)
            null
        }
    }
}
```

**6 zdarzeń logowanych:**
| Zdarzenie | Poziom | Komunikat |
|---|---|---|
| NativeHelper found | INFO | `NativeHelper found via Class.forName` |
| NativeHelper missing | WARN | `NativeHelper not found — native CAN crypto unavailable` |
| Method resolved | INFO | `Method bafangEncry(ByteArray, Int) resolved` |
| Method missing | WARN | `NativeHelper method missing: ...` |
| Encryption failed | ERROR | `Encryption failed: ...` |
| Decryption failed | ERROR | `Decryption failed: ...` |

### 3.3 CanBleFrameExtractor — payloadLen > 8 jako suspicious, nie error

**Stan przed:**
```kotlin
if (payloadLen > 8) break  // invalid (CAN max 8 bytes)
```

**Stan po:**
```kotlin
if (payloadLen > 8) {
    // Suspicious but not necessarily invalid.
    // Swift PlBleService supports long data frames (op 4..6)
    // where payload can exceed standard CAN 8-byte limit.
    // Extract the frame and let the caller decide.
    Log.w("CanBleFrameExtractor",
        "Suspicious payloadLen=$payloadLen at offset=$i (op=${raw[i+1].toInt() and 0x07})")
    // continue processing — do NOT break
}
```

**Uzasadnienie:**
- Swift PlBleService posiada obsługę `long data` (op kody 4, 5, 6) gdzie payload może przekraczać 8 bajtów.
- `init_cmd_list` również używa większych payloadów.
- Break przy payloadLen > 8 powoduje utratę wszystkich pozostałych ramek w notyfikacji.
- Zmiana: log warn + kontynuacja, a nie break.

**Wpływ na testy:**
- Test case 6 zmienia nazwę z `"payloadLen > 8 → stop"` na `"payloadLen > 8 → suspicious, extract anyway"`.
- Dodano test case 9: `"payloadLen > 8 with op=4 (long data) extracts correctly"`.

---

## 4. Końcowa rekomendacja

Plan `phase1_core_implementation_plan.md` **może zostać zatwierdzony** po wprowadzeniu powyższych 3 korekt.

### Checklista przed implementacją

- [x] FallbackCryptoProvider: brak AES, `@ExperimentalCanCrypto`, zwraca `null`
- [x] NativeCryptoProvider: pełne logowanie 6 zdarzeń
- [x] CanBleFrameExtractor: payloadLen > 8 → suspicious + warning, nie break
- [ ] Zaktualizować `phase1_core_implementation_plan.md` o powyższe korekty
- [ ] Zredukować `FallbackCryptoProviderTest` z 5 do 2 przypadków
- [ ] Dodać test case 9 w `CanBleFrameExtractorTest` (long data frame)
- [ ] Przejść do implementacji kodu Kotlin

### Zmiany w testach vs oryginalny plan

| Test class | Planowane przypadki | Po korektach |
|---|---|---|
| `FallbackCryptoProviderTest` | 5 | 2 (`supportsCanCrypto`=false, `encrypt`=null, `decrypt`=null) |
| `CanBleFrameExtractorTest` | 8 | 9 (+1: long data z payloadLen > 8) |
| `CanBleFrameFilterTest` | 7 | 7 (bez zmian) |
| `CanBleLoggerTest` | 3 | 3 (bez zmian) |

### Sekwencja po zatwierdzeniu

```
1. Zaaktualizuj phase1_core_implementation_plan.md korektami
2. Wygeneruj kod Kotlin (6 plików źródłowych)
3. Wygeneruj testy (4 pliki testowe)
4. .\gradlew.bat clean assembleDebug test
5. Weryfikacja: BUILD SUCCESSFUL + wszystkie testy przechodzą
```
