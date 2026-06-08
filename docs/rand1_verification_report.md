# RAND1 Flow Verification Report

> **Source:** `PlBleService.java` (5349 lines), `NativeHelper.java`
> **Date:** 2026-06-08
> **Status:** All questions answered with line-level evidence

---

## Answers

### Q1: Jaki dokładnie payload jest wysyłany dla CAN_RAND1 (0x01)?

**Payload before encryption:**

```
[0x01, dyncRand1[0], dyncRand1[1], dyncRand1[2], dyncRand1[3]]
```

5 bytes total. Command byte `0x01` followed by 4 random bytes from `dyncRand1`.

**After encryption:**

The 5-byte payload is encrypted with the fixed key via `bafangEncry(payload, 5, 1)` (context=1). The resulting ciphertext is 16 bytes (AES block size, encrypted output is round up to block boundary). Written to `UUID_CAN_CONTROL` via `sendPacket`.

### Q2: Czy appRand jest częścią wiadomości 0x01?

**TAK.** `dyncRand1` (4 bytes losowe) jest dołączany do bajtu komendy `0x01`.

Dowód — linie 483 i 676 w `PlBleService.java`:

```java
// onServicesDiscovered (line 483):
PlBleService.send_data_encry(
    PlBleService.this.gen_cmd_encry((byte) 1, PlBleService.dyncRand1),
    PlBleService.UUID_CAN_CONTROL,
    false
);

// onDescriptorWrite (line 676): identyczne wywołanie
```

### Q3: Które bajty i w jakiej kolejności?

```
Offset  Bajt        Źródło
──────  ─────────── ─────────────────────────────
[0]     0x01        CAN_RAND1 (command byte)
[1]     dyncRand1[0] (byte)(random.nextInt())        ← LSB
[2]     dyncRand1[1] (byte)(random.nextInt() >> 8)
[3]     dyncRand1[2] (byte)(random.nextInt() >> 16)
[4]     dyncRand1[3] (byte)(random.nextInt() >> 24)  ← MSB
```

`dyncRand1` jest wypełniany z `java.util.Random().nextInt()` — zapis little-endian (LSB first).

### Q4: Skąd urządzenie zna appRand użyty do key derivation?

Urządzenie otrzymuje `dyncRand1` jako payload wiadomości `0x01`. Następnie urządzenie odpowiada wiadomością `0x02` zawierającą własne 4 bajty losowe + 6 bajtów extra. Aplikacja łączy oba zestawy w klucz dynamiczny:

```
dyncRand[i*3]     = dyncRand1[i]         ← app random (4B)
dyncRand[i*3 + 1] = bArr2[i]             ← device random (4B)
dyncRand[i*3 + 2] = bArr3[i]             ← channel data (4B)
dyncRand[12]      = 0x55
dyncRand[13]      = 0xAA
dyncRand[14]      = bArr3[4]             ← extra[4]
dyncRand[15]      = bArr3[5]             ← extra[5]
```

Urządzenie po swojej stronie wykonuje identyczne wyprowadzenie — obie strony mają oba zestawy losowych danych po zakończeniu wymiany `0x01` ↔ `0x02`.

### Q5: Czy dyncRand1 jest generowany przed wysłaniem RAND1?

**TAK.** Funkcja `access$3600()` jest wywoływana **bezpośrednio przed** `gen_cmd_encry(1, dyncRand1)` w obu ścieżkach wywołania.

Linie 482-483 (`onServicesDiscovered`):
```java
482:  PlBleService.access$3600(PlBleService.this);                  // ← RANDOMIZES dyncRand1
483:  PlBleService.send_data_encry(
          PlBleService.this.gen_cmd_encry((byte) 1, PlBleService.dyncRand1),
          PlBleService.UUID_CAN_CONTROL, false);
```

Linie 675-676 (`onDescriptorWrite`):
```java
675:  PlBleService.access$3600(PlBleService.this);                  // ← RANDOMIZES dyncRand1
676:  PlBleService.send_data_encry(
          PlBleService.this.gen_cmd_encry((byte) 1, PlBleService.dyncRand1),
          PlBleService.UUID_CAN_CONTROL, false);
```

Początkowa wartość `dyncRand1 = {68, 51, 17, 17}` (linia 133) **NIGDY nie jest używana** w handshake — jest nadpisywana przez `access$3600()` przed pierwszym wysłaniem.

Statyczny inicjalizator (linia 133):
```java
public static byte[] dyncRand1 = {68, 51, CAN_ACK_NAME, CAN_ACK_NAME};
// CAN_ACK_NAME = 17 → {68, 51, 17, 17}
```

Funkcja `access$3600()` (linie 5164-5171):
```java
static /* synthetic */ void access$3600(PlBleService plBleService) {
    Arrays.fill(dyncRand, (byte) 0);
    int iNextInt = new Random().nextInt();
    byte[] bArr = dyncRand1;
    bArr[0] = (byte) iNextInt;           // LSB
    bArr[1] = (byte) (iNextInt >> 8);
    bArr[2] = (byte) (iNextInt >> 16);
    bArr[3] = iNextInt >> 24;            // MSB
    can_node_list.clear();
    aU = (byte) -1;
    // ... resetuje stan CAN ...
}
```

### Q6: Dokładne linie źródłowe

| Opis | Plik | Linie |
|---|---|---|
| Deklaracja `dyncRand1` (static) | PlBleService.java | 133 |
| Stała `CAN_RAND1 = 1` (zadeklarowana, ale NIGDY nie użyta po nazwie — wszędzie literal `(byte)1`) | PlBleService.java | 56 |
| Stała `CAN_RAND2 = 2` (zadeklarowana, ale NIGDY nie użyta) | PlBleService.java | 57 |
| `access$3600()` — randomizacja `dyncRand1` + reset stanu CAN | PlBleService.java | 5164–5190 |
| Wywołanie `access$3600()` + wysłanie RAND1 po `onServicesDiscovered` | PlBleService.java | 482–484 |
| Wywołanie `access$3600()` + wysłanie RAND1 po `onDescriptorWrite` | PlBleService.java | 675–677 |
| `gen_cmd_encry(byte cmd, byte[] payload)` — składa `[cmd, payload...]` | PlBleService.java | 3193–3201 |
| `send_data_encry()` — szyfruje fixed key (context=1) dla CAN_CONTROL, potem `sendPacket` | PlBleService.java | 2288–2308 |
| `process_can_pdu` — deszyfruje CAN_CONTROL (fixed key) i woła `process_can_control` | PlBleService.java | 1971–1985 |
| `process_can_control` — obsługa komendy `0x02`: ekstrakcja deviceRandom + extra, key derivation, `setDynamicKey`, wysłanie GET_MTU | PlBleService.java | 2169–2188 |
| `reset_rand1()` — identyczna randomizacja `dyncRand1` (dla reconnect) | PlBleService.java | 3165–3172 |
| `NativeHelper.setDynamicKey(byte[])` — natywna deklaracja | NativeHelper.java | 47 |
| `BAFANG_KEY_TYPE_DYNAMIC = 0` — kontekst dla klucza dynamicznego | NativeHelper.java | 8 |
| `BAFANG_KEY_TYPE_FIXED = 1` — kontekst dla klucza stałego | NativeHelper.java | 9 |

---

## Complete Handshake Data Flow

```
Krok   Kierunek   UUID           Payload (przed szyfrowaniem)          Szyfrowanie
────   ────────   ────────────   ────────────────────────────────────  ─────────────────
1      App→Dev    CAN_CONTROL    [0x01, dyncRand1[0..3]] (5B)         fixed key (ctx=1)
2      Dev→App    CAN_CONTROL    [0x02, devRand[0..3], extra[0..5]]   fixed key (ctx=1)
                                  (11B decryptowanych)
3      App→Dev    CAN_CONTROL    [0x08] (1B)                           fixed key (ctx=1)
4      Dev→App    CAN_CONTROL    [0x09] (1B)                           fixed key (ctx=1)
                                 → aU = 0, READY
[opcjonalnie po kroku 4, jeśli firmware > 1.7:]
5      App→Dev    CAN_CONTROL    [0x12] (1B)                           fixed key (ctx=1)
6      Dev→App    CAN_CONTROL    [0x13, aU] (2B)                       fixed key (ctx=1)
                                 → aU = payload[1] (0|1|2|3)
```

### Kluczowe obserwacje

1. **`dyncRand1` jest ZAWSZE randomizowany `access$3600()` tuż przed wysłaniem** — wartość domyślna `{68, 51, 17, 17}` NIGDY nie trafia na wire.

2. **CAN_CONTROL jest ZAWSZE szyfrowany fixed key** (context=1), niezależnie od aU.

3. **`send_data_encry` dla CAN_CONTROL wywołuje `bafangEncry(data, len, 1)`** — drugi parametr to długość danych wejściowych, trzeci to key type.

4. **DeviceResponse (krok 2) wymaga minimum 11 bajtów** po deszyfrowaniu:
   - `bArr[0]` == `0x02` (command)
   - `bArr[1..4]` = device random (4B)
   - `bArr[5..10]` = extra/channel data (6B)
   - Warunek: `if (bArr.length >= 11)` (line 2172)

5. **CAN_RAND2 (`0x02`) jako stała jest zdefiniowana (line 57) ale NIGDY nie użyta** — w `process_can_control` komenda `0x02` jest sprawdzana przez `if (b == 2)` z literałem.

6. **Długość CAN_CONTROL notyfikacji musi być `% 16 == 0`** — `process_can_pdu` sprawdza to przed deszyfrowaniem (line 1975).

---

## Implikacje dla CanBleHandshake

- **RAND1 payload jest potwierdzony:** `gen_cmd_encry(1, dyncRand1)` = `[0x01, 4 random bytes]` — nasz projekt był poprawny.
- **Key derivation jest potwierdzony:** interleave `dyncRand1[i]`, `deviceRand[i]`, `extra[i]` — wzór zgadza się z kodem.
- **Device response `0x02` wymaga 11+ bajtów:** walidacja w kodzie istnieje.
- **CAN_CONTROL notyfikacje muszą być % 16 == 0:** nasza implementacja powinna to sprawdzać.

Jedyna korekta do projektu: `dyncRand1` w PlBleService jest nazwany `dyncRand1`, nie `appRand`. Użyliśmy `appRand` w dokumencie projektowym — to drobna różnica nazewnictwa, semantyka jest identyczna.
