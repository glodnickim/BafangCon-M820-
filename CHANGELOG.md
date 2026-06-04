# Changelog

## 2026-05-29 — Autoryzacja AES, poprawki protokołu, mapowanie poziomów

### Dodane

- **`utils/AESUtils.kt`** — szyfrowanie AES/ECB/NoPadding kluczem `2CTDU40qNyCgTjb1`
- **`BleAuthState`** w `Constants.kt` — `NOT_AUTHENTICATED`, `AUTHENTICATING`, `AUTHENTICATED`, `AUTH_FAILED`
- **Flow autoryzacji** w `BleRepository.kt`:
  1. `meterRead(0x10, 0x00, 4)` — pobranie 4 bajtów losowych
  2. AES/ECB/NoPadding (dopełnienie zerami do 16 bajtów)
  3. `certification(0x10, 0x00, encrypted16)` — komenda 0x20
  4. `handleAuthResult()` — sprawdzenie RESULT == 0
- **`requestMtu(250)`** — po włączeniu notyfikacji
- **Widoczny status autoryzacji** w `MainFragment` + blokada przycisków przed auth
- **Obsługa konfiguracji 3, 4, 5, 9 poziomów** w `GearsInfoFragment` — automatyczne mapowanie na podstawie `meterInfo.totalGear`

### Zmienione

- **`createReadRequestFrame`** — 2-bajtowa suma kontrolna AA55Pack (`(~sum) & 0xFFFF`) zamiast 1-bajtowej + FE/FF
- **`createWriteRequestFrame`** — zawsze 2-bajtowa suma AA55Pack (usunięto specjalny przypadek dla 1-bajtowych payloadów)
- **`onDescriptorWrite`** — po włączeniu notyfikacji uruchamia autoryzację i żądanie MTU
- **`parseCompleteFrame`** — obsługa odpowiedzi autoryzacji (`rspType == 0x10`)
- **`handleDisconnectOrFailure`** — reset stanu autoryzacji
- **`GearsInfoFragment`** — indeksy tablic dostosowane do mapowania z analizy Bafang Go:
  - 3 poziomy: indeksy `[3, 5, 9]`
  - 4 poziomy: indeksy `[1, 3, 6, 9]`
  - 5 poziomów: indeksy `[2, 4, 6, 8, 9]` (było `[0, 1, 2, 3, 4]`)
  - 9 poziomów: indeksy `[1, 2, 3, 4, 5, 6, 7, 8, 9]`
- **`fragment_main.xml`** — dodano `authStatusTextView`
- **`DeviceViewModel.kt`** — eksport `authState`

### Usunięte

- Nieużywane stałe `FRAME_END_BYTE_FE`, `FRAME_END_BYTE_FF`, `WRITE_FRAME_END_BYTE` w `BleRepository.kt`
