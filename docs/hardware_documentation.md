# Dokumentacja Sprzętowa – Automatyczny System Podlewania Roślin

Niniejszy dokument zawiera kompletny opis warstwy sprzętowej (Hardware), schemat połączeń elektrycznych oraz wytyczne programistyczne dla osoby rozwijającej oprogramowanie systemu.

---

## 1. Wykorzystane komponenty (BOM)

Układ został zbudowany w oparciu o architekturę **5V** (logika mikrokontrolera i czujników) oraz osobny obwód mocy **12V** (zasilanie pompki i płyty głównej).

| Komponent | Model / Specyfikacja | Rola w systemie |
| :--- | :--- | :--- |
| **Mikrokontroler** | Arduino Mega 2560 (ATmega2560) | Jednostka centralna (logika 5V) |
| **Moduł zasilający** | Moduł zasilacza płytkowego (z przyciskiem `S1` i gniazdem `DC-IN`) | Główny hub dystrybucji zasilania (`12V`, `5V`, `GND`) – zastępuje płytkę stykową |
| **Wyświetlacz** | LCD 1602 z konwerterem I2C | Interfejs użytkownika (adres I2C: `0x27` lub `0x3F`) |
| **Enkoder obrotowy** | KY-040 (z przyciskiem `SW`) | Nawigacja po menu, zmiana nastaw, ręczne wyzwalanie |
| **Czujnik wilgotności gleby** | Sonda rezystancyjna (widełkowa) + moduł komparatora LM393 | Pomiar analogowy wilgotności podłoża |
| **Czujnik poziomu wody** | Czujnik pływakowy ZP5210 (kontaktron) | Zabezpieczenie przed pracą pompki na sucho (brak wody w zbiorniku) |
| **Moduł wykonawczy** | Przekaźnik 1-kanałowy 5V (`JQC3F-5VDC-C`) | Bezpieczne załączanie obwodu 12V pompki |
| **Pompka wody** | Pompka membranowa `DC 12V` (zewnętrzna) | Tłoczenie wody ze zbiornika do doniczki |
| **Zasilacz sieciowy** | Zasilacz wtyczkowy DC (np. 12V) | Główne źródło zasilania układu |

> **Uwaga:** Buzzer (sygnalizator dźwiękowy) został pominięty w fizycznym montażu – w kodzie zarezerwowano dla niego **Pin 11**, gdyby zaszła potrzeba jego dołożenia.

---

## 2. Mapa połączeń (Pinout Arduino Mega 2560)

Piny komunikacji szeregowej **0 (RX0)** oraz **1 (TX0)** pozostawiono celowo wolne, aby nie blokować wgrywania kodu oraz debugowania przez port USB (Serial Monitor).

| Pin Arduino Mega | Element docelowy | Pin na module | Kierunek / Tryb | Opis funkcji |
| :--- | :--- | :--- | :--- | :--- |
| **Pin 2** | Enkoder KY-040 | `CLK` | `INPUT_PULLUP` | Sygnał zegarowy enkodera (obsługuje przerwanie sprzętowe `INT4`) |
| **Pin 3** | Enkoder KY-040 | `DT` | `INPUT_PULLUP` | Sygnał kierunku obrotu enkodera (obsługuje przerwanie `INT5`) |
| **Pin 4** | Enkoder KY-040 | `SW` | `INPUT_PULLUP` | Przycisk w gałce enkodera (stan aktywny: `LOW`) |
| **Pin 5** | Czujnik pływakowy ZP5210 | Przewód 1 | `INPUT_PULLUP` | Wykrywanie poziomu wody (drugi przewód podpięty do `GND`) |
| **Pin 7** | Moduł przekaźnika | `IN` | `OUTPUT` | Sterowanie cewką przekaźnika (zazwyczaj aktywny stanem `LOW`) |
| **Pin 11** | *(Opcjonalnie) Buzzer* | `+` | `OUTPUT` | Zarezerwowany pin pod sygnalizator akustyczny |
| **Pin 20 (SDA)** | Wyświetlacz LCD I2C | `SDA` | I2C Data | Linia danych magistrali I2C |
| **Pin 21 (SCL)** | Wyświetlacz LCD I2C | `SCL` | I2C Clock | Linia zegarowa magistrali I2C |
| **Pin A0** | Czujnik gleby (LM393) | `AO` | `INPUT` (Analog) | Odczyt surowej wartości ADC ($0 - 1023$). Pin `DO` nieużywany |
| **VIN** | Moduł zasilający | `DC-IN/OUT (+)` | Zasilanie wej. | Zasilanie płytki Arduino napięciem 12V z modułu zasilającego |
| **GND** | Moduł zasilający | `DC-IN/OUT (-)` | Masa | Wspólna masa całego układu |

---

## 3. Architektura zasilania i dystrybucja napięć

W układzie nie zastosowano klasycznej płytki stykowej – rolę szyny zasilającej pełni **niebieski moduł zasilacza** (z przyciskiem `S1`).

### A. Szyna 12V – sekcja `DC-IN/OUT` na module zasilającym
Zasilacz sieciowy podpięty jest do czarnego gniazda `DC-IN`. Napięcie 12V rozdzielane jest na:
1. **Zasilanie Arduino Mega:**
   * `DC-IN/OUT (+)` -> pin **`VIN`** na Arduino Mega.
   * `DC-IN/OUT (-)` -> pin **`GND`** na Arduino Mega.
   * *Bezpieczeństwo USB:* Pin **`5V`** na Arduino Mega **nie jest połączony** z linią `5V OUT` modułu zasilającego. Dzięki wbudowanemu układowi przełączającemu na płytce Mega można bezpiecznie mieć jednocześnie podłączony zasilacz 12V oraz kabel USB do laptopa.
2. **Obwód mocy pompki (rozłączany po stronie plusa `+12V`):**
   * `DC-IN/OUT (+)` -> środkowy zacisk śrubowy przekaźnika (**`COM`**).
   * Lewy zacisk śrubowy przekaźnika (**`NO`** – Normalnie Otwarty) -> styk **`+`** pompki (oznaczony czerwoną kropką przy blaszce silnika).
   * `DC-IN/OUT (-)` -> bezpośrednio do styku **`-`** pompki (blaszka bez oznaczenia).
   * Prawy zacisk przekaźnika (**`NC`**) pozostaje pusty.

### B. Szyna 5V – sekcja `5V OUT` na module zasilającym
Z żółtych pinów `5V OUT` (`+` oraz `GND`) zasilane są wszystkie układy logiczne i czujniki:
* Wyświetlacz LCD 1602 I2C (`VCC` -> `+5V`, `GND` -> `GND`)
* Enkoder KY-040 (`+` -> `+5V`, `GND` -> `GND`)
* Czujnik wilgotności gleby LM393 (`VCC` -> `+5V`, `GND` -> `GND`)
* Moduł przekaźnika (`VCC` -> `+5V`, `GND` -> `GND`)
* Czujnik pływakowy ZP5210 (jeden z przewodów podpięty do szyny `GND`)

---

## 4. Charakterystyka czujników i wytyczne programistyczne

### 1. Czujnik wilgotności gleby (LM393 + sonda widełkowa)
* **Odczyt:** Wykorzystujemy wyłącznie wyjście analogowe **`AO`** podpięte do **`A0`**.
* **Charakterystyka napięciowa (odwrotna):**
  * **Sucha gleba / powietrze:** wysoka wartość ADC ($\approx 950 - 1023$).
  * **Mokra gleba / woda:** niska wartość ADC ($\approx 300 - 450$).
* **Zalecenie dla kodu:** W programie należy zmapować odczyt za pomocą funkcji `map(val, 1023, 300, 0, 100)` (z ograniczeniem `constrain` do przedziału $0 - 100\%$) po wcześniejszej kalibracji w docelowej doniczce.
* **Uwaga eksploatacyjna:** Jest to czujnik rezystancyjny – przy długotrwałej pracy w wilgotnej ziemi może ulegać elektrolizie, co z czasem lekko przesunie punkt kalibracji.

### 2. Czujnik poziomu wody (ZP5210)
* **Zasada działania:** Kontaktron (przełącznik magnetyczny) zwarty z masą (`GND`), skonfigurowany na **Pinie 5** jako `INPUT_PULLUP`.
* **Logika stanów:**
  * `LOW` (zwarcie do masy) = pływak w górze -> **woda jest w zbiorniku**.
  * `HIGH` (rozwarty) = pływak opadł na dno -> **brak wody w zbiorniku**.
  * *Uwaga:* W razie odwrotnego działania po montażu w zbiorniku, stan można odwrócić w kodzie lub fizycznie (zdejmując dolną zawleczkę na trzpieniu pływaka i obracając pływak o $180^\circ$).
* **Wymagany mechanizm bezpieczeństwa (Interlock):** Program musi bezwzględnie blokować uruchomienie przekaźnika (oraz natychmiast przerywać trwające podlewanie), gdy czujnik zgłosi brak wody.

### 3. Pompka membranowa 12V i przekaźnik
* **Typ pompki:** Zewnętrzna pompka membranowa – **nie wolno zanurzać jej w wodzie** (do zbiornika trafia wyłącznie wężyk ssący nałożony na króciec).
* **Ochrona przed pracą na sucho:** Pompka nie powinna pracować bez wody dłużej niż kilka sekund.
* **Logika przekaźnika:** Standardowe moduły przekaźników wyzwalane są stanem niskim (`LOW` = przekaźnik załączony / pompka pracuje, `HIGH` = przekaźnik rozłączony / pompka stoi). W kodzie warto zdefiniować stałe `RELAY_ON` i `RELAY_OFF` dla łatwej zmiany w razie potrzeby.
* **Sterowanie czasowe:** Do odmierzania czasu pracy pompki oraz przerw między kolejnymi podlewaniami (czas na wsiąknięcie wody w glebę) należy użyć **nieblokującego odmierzania czasu** (`millis()` lub timerów sprzętowych AVR), aby nie zamrażać obsługi enkodera i odświeżania ekranu LCD podczas podlewania.

### 4. Wyświetlacz LCD 1602 I2C oraz enkoder KY-040
* **Biblioteka LCD:** `LiquidCrystal_I2C` (adres `0x27`, w przypadku braku reakcji sprawdzić `0x3F`). Kontrast regulowany jest niebieskim potencjometrem na pleckach modułu I2C.
* **Obsługa enkodera:** Piny `2` (`CLK`) i `3` (`DT`) są wyprowadzone na piny obsługujące przerwania sprzętowe Arduino Mega (`INT4` oraz `INT5`), co pozwala na płynną obsługę pokrętła bez gubienia kroków.

---

## 5. Kod bazowy do szybkiego testu sprzętu

Poniższy szkic pozwala zweryfikować poprawność wszystkich połączeń po złożeniu układu (odczyt `A0`, stan pływaka na `D5`, ruch enkodera `D2/D3`, wyzwalanie przekaźnika `D7` przyciskiem enkodera `D4` oraz komunikację I2C z ekranem):

```cpp
#include <Wire.h>
#include <LiquidCrystal_I2C.h>

LiquidCrystal_I2C lcd(0x27, 16, 2);

const int PIN_CLK   = 2;
const int PIN_DT    = 3;
const int PIN_SW    = 4;
const int PIN_FLOAT = 5;
const int PIN_RELAY = 7;
const int PIN_SOIL  = A0;

const int RELAY_ON  = LOW;
const int RELAY_OFF = HIGH;

int encoderPos = 0;
int lastClkState;

void setup() {
  pinMode(PIN_CLK, INPUT_PULLUP);
  pinMode(PIN_DT, INPUT_PULLUP);
  pinMode(PIN_SW, INPUT_PULLUP);
  pinMode(PIN_FLOAT, INPUT_PULLUP);
  pinMode(PIN_RELAY, OUTPUT);

  digitalWrite(PIN_RELAY, RELAY_OFF);
  lastClkState = digitalRead(PIN_CLK);

  lcd.init();
  lcd.backlight();
  lcd.setCursor(0, 0);
  lcd.print("Test sprzetu...");
  delay(1000);
  lcd.clear();
}

void loop() {
  int currentClk = digitalRead(PIN_CLK);
  if (currentClk != lastClkState && currentClk == LOW) {
    if (digitalRead(PIN_DT) != currentClk) {
      encoderPos++;
    } else {
      encoderPos--;
    }
  }
  lastClkState = currentClk;

  int soilRaw = analogRead(PIN_SOIL);
  bool waterOk = (digitalRead(PIN_FLOAT) == LOW);
  bool buttonPressed = (digitalRead(PIN_SW) == LOW);

  // Zabezpieczenie: przekaźnik zadziała tylko przy wciśniętym przycisku i obecności wody
  if (buttonPressed && waterOk) {
    digitalWrite(PIN_RELAY, RELAY_ON);
  } else {
    digitalWrite(PIN_RELAY, RELAY_OFF);
  }

  lcd.setCursor(0, 0);
  lcd.print("Gl:");
  lcd.print(soilRaw);
  lcd.print("   ");

  lcd.setCursor(8, 0);
  lcd.print(waterOk ? "Woda:OK " : "BRAK WOD");

  lcd.setCursor(0, 1);
  lcd.print("Enk:");
  lcd.print(encoderPos);
  lcd.print("   ");

  lcd.setCursor(9, 1);
  lcd.print((buttonPressed && waterOk) ? "Pomp:ON " : "Pomp:OFF");
}
```

---

## 6. Moduł BLE HM-10 / AT-09 (most BLE–UART pod aplikacje mobilne)

> Decyzja projektowa: telefony (Android + iOS) łączą się ze sterownikiem przez
> **Bluetooth Low Energy** za pomocą modułu **HM-10 (lub zgodnego AT-09)** —
> przezroczystego mostu BLE–UART. Mega nie implementuje BLE samodzielnie;
> cały ruch radiowy kończy się w module, a Mega widzi zwykły UART.

### 6.1. Podłączenie (wolny UART `Serial1`, `Serial0` zostaje na USB)

| Pin Arduino Mega | HM-10 / AT-09 | Uwagi |
| :--- | :--- | :--- |
| **Pin 18 (TX1)** | `RXD` | **Przez dzielnik napięcia** (np. 1 kΩ / 2 kΩ): Mega nadaje 5 V, wejście modułu to 3,3 V |
| **Pin 19 (RX1)** | `TXD` | Bezpośrednio (3,3 V z modułu jest czytane jako `HIGH`) |
| `3,3 V` lub `5V` (*) | `VCC` | (*) zależnie od wersji płytki — większość breakoutów HM-10/AT-09 ma stabilizator i przyjmuje 5 V; czysty moduł wymaga 3,3 V. Masa wspólna obowiązkowo |
| `GND` | `GND` | Wspólna masa całego układu |
| (opcjonalnie) Pin 8 | `EN/KEY` | Do trybu AT; domyślnie niepodłączony = tryb pracy |
| (opcjonalnie) dowolny cyfrowy | `STATE` | `HIGH` = telefon połączony; tylko sygnalizacja, nie logika pompki |

### 6.2. Konfiguracja radiowa (tryb AT, raz na stole)

1. Baud pracy UART: **`9600`** — taki sam ustaw w `config.h` (`BT_BAUD`).
2. Tryb: peripheral/slave (`AT+ROLE0`), nazwa rozgłaszana: **`RoslinkiPump`**
   (`AT+NAME=RoslinkiPump`). Aplikacje filtrują po usłudze `0xFFE0` i nazwie.
3. Serwis i charakterystyka są **narzucone przez firmware modułu** i nie do
   zmiany: serwis **`0xFFE0`**, charakterystyka **`0xFFE1`** (transparentny
   UART: zapis → `RXD`, powiadomienia ← `TXD`). Patrz `docs/ble-protocol.md.

### 6.3. Zasady programistyczne (obowiązkowe)

* Cały ruch z telefonów idzie przez **`Serial1` (18/19)** — piny **0/1
  (`Serial`)** zostają na USB (programowanie + Serial Monitor + kabel OTG).
* Format linii identyczny jak po USB (`docs/usb-protocol.md`): jeden obiekt
  JSON na linię, `\n` na końcu. Parser współdzielony dla obu portów.
* Przez BLE jedna operacja zapisu niesie **maks. 20 B** — aplikacje tną linie
  na kawałki 20 B (zapis z odpowiedzią), Mega składa je z powrotem po `\n`.
* Sterowanie pompką wyłącznie przez `setPump()` — interlock pływaka (brak
  wody = blokada + przerwanie w toku) obowiązuje tak samo dla komend z BLE,
  z USB i z przycisku enkodera.