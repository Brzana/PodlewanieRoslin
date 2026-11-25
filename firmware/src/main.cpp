#include <Arduino.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>

#include "config.h"
#include "pins.h"

// Arduino Mega 2560 standalone watering controller.
// Hardware source of truth: docs/hardware_documentation.md (ch. 6 = HM-10).
// Wiring quick reference:   docs/wiring.md.
// Phone protocol over BLE:  docs/ble-protocol.md (HM-10 0xFFE0/0xFFE1).
// Cable protocol (same lines): docs/usb-protocol.md.
//
// Current stage: hardware-verification skeleton (from ch. 5 of the
// source-of-truth doc), restructured for further work:
//   - encoder on HW interrupts (no lost steps),
//   - soil mapping + float interlock as separate functions,
//   - pump behind setPump() so the dry-run interlock has one choke point,
//   - shared JSON-line parser over USB Serial (monitor/OTG) and BLE Serial1,
//   - timed watering via millis() deadline, never delay(),
//   - non-blocking LCD/Serial updates via millis().
//
// TODO (next steps, in this order):
//   1. Menu/UI on encoder+LCD (threshold, manual dose, auto on/off).
//   2. Auto mode: pump when soil % < threshold, then soak pause.
//   3. Persistent settings (EEPROM) + dry-run event logging.

static LiquidCrystal_I2C lcd(LCD_I2C_ADDR, LCD_COLS, LCD_ROWS);

// Encoder state. Written from the ISR -> volatile.
static volatile long encoderPos = 0;

// Timed watering requested over serial/BLE. 0 = inactive, else deadline.
static unsigned long pumpDeadline = 0;

// One line buffer per input port (BLE writes arrive in <= 20 B chunks,
// the parser reassembles them until '\n').
struct LineBuf {
    char buf[USB_LINE_MAX];
    size_t len = 0;
};
static LineBuf usbLine;
static LineBuf btLine;

// --- Sensors / actuators -------------------------------------------------

int readSoilRaw() { return analogRead(PIN_SOIL); }

// Inverted sensor: dry ~= SOIL_ADC_DRY -> 0 %, wet ~= SOIL_ADC_WET -> 100 %.
int soilMoisturePercent(int raw) {
    long pct = map(raw, SOIL_ADC_DRY, SOIL_ADC_WET, 0, 100);
    return (int)constrain(pct, 0, 100);
}

// LOW (pulled to GND by the float) = water present.
bool isWaterPresent() { return digitalRead(PIN_FLOAT) == LOW; }

bool isButtonPressed() { return digitalRead(PIN_ENC_SW) == LOW; }

// Single choke point for the pump. Enforces the dry-run interlock:
// the pump can never be ON while the tank is empty, no matter who asks.
bool setPump(bool wantOn) {
    bool allowed = wantOn && isWaterPresent();
    digitalWrite(PIN_RELAY, allowed ? RELAY_ON : RELAY_OFF);
    return allowed;
}

// --- Status output -------------------------------------------------------

long timedMsLeft(unsigned long now) {
    if (pumpDeadline == 0) return 0;
    if (now >= pumpDeadline) return 0;
    return (long)(pumpDeadline - now);
}

// One JSON line per status. Lines starting with '{' are machine-readable
// (phone apps); anything else on the port is human debug and the app ignores it.
void sendStatusOn(Stream& out, bool pumping, long msLeft) {
    int raw = readSoilRaw();
    out.print(F("{\"soil\":"));
    out.print(raw);
    out.print(F(",\"pct\":"));
    out.print(soilMoisturePercent(raw));
    out.print(F(",\"water\":"));
    out.print(isWaterPresent() ? F("true") : F("false"));
    out.print(F(",\"pumping\":"));
    out.print(pumping ? F("true") : F("false"));
    out.print(F(",\"msLeft\":"));
    out.print(msLeft);
    out.println(F("}"));
}

void sendErrorOn(Stream& out, const __FlashStringHelper* code, bool pumping, long msLeft) {
    int raw = readSoilRaw();
    out.print(F("{\"error\":\""));
    out.print(code);
    out.print(F("\",\"soil\":"));
    out.print(raw);
    out.print(F(",\"pct\":"));
    out.print(soilMoisturePercent(raw));
    out.print(F(",\"water\":"));
    out.print(isWaterPresent() ? F("true") : F("false"));
    out.print(F(",\"pumping\":"));
    out.print(pumping ? F("true") : F("false"));
    out.print(F(",\"msLeft\":"));
    out.print(msLeft);
    out.println(F("}"));
}

// Telemetry goes to both ports; abort/error notices must reach the phone too.
void broadcastStatus(bool pumping, long msLeft) {
    sendStatusOn(Serial, pumping, msLeft);
    sendStatusOn(Serial1, pumping, msLeft);
}

void broadcastError(const __FlashStringHelper* code, bool pumping, long msLeft) {
    sendErrorOn(Serial, code, pumping, msLeft);
    sendErrorOn(Serial1, code, pumping, msLeft);
}

void updateLcd(int soilRaw, bool waterOk, long enc, bool pumpOn) {
    lcd.setCursor(0, 0);
    lcd.print("Gl:");
    lcd.print(soilRaw);
    lcd.print("   ");

    lcd.setCursor(8, 0);
    lcd.print(waterOk ? "Woda:OK " : "BRAK WOD");

    lcd.setCursor(0, 1);
    lcd.print("Enk:");
    lcd.print(enc);
    lcd.print("   ");

    lcd.setCursor(9, 1);
    lcd.print(pumpOn ? "Pomp:ON " : "Pomp:OFF");
}

// --- Command line (shared by USB Serial and BLE Serial1) -----------------
// Protocol: docs/ble-protocol.md / docs/usb-protocol.md. One JSON object per
// line, '\n'-terminated. Replies go back on the port the command came from.

static long parseMsValue(const char* line) {
    const char* key = strstr(line, "\"ms\"");
    if (key == nullptr) return -1;
    const char* colon = strchr(key, ':');
    if (colon == nullptr) return -1;
    long ms = atol(colon + 1);  // skips whitespace/quotes, stops at ',' or '}'
    if (ms < 0) return -1;
    if (ms > USB_MAX_WATER_MS) return USB_MAX_WATER_MS;
    return ms;
}

static void handleCommandLine(const char* line, unsigned long now, Stream& reply) {
    if (strstr(line, "\"cmd\"") == nullptr) return;  // not a command, ignore
    if (strstr(line, "\"stop\"") != nullptr) {
        pumpDeadline = 0;
        sendStatusOn(reply, false, 0);
        return;
    }
    if (strstr(line, "\"status\"") != nullptr) {
        sendStatusOn(reply, isWaterPresent() && pumpDeadline != 0, timedMsLeft(now));
        return;
    }
    if (strstr(line, "\"water\"") != nullptr) {
        long ms = parseMsValue(line);
        if (ms <= 0) {
            sendErrorOn(reply, F("bad_ms"), false, timedMsLeft(now));
            return;
        }
        if (!isWaterPresent()) {
            pumpDeadline = 0;
            sendErrorOn(reply, F("no_water"), false, 0);
            return;
        }
        pumpDeadline = now + (unsigned long)ms;
        sendStatusOn(reply, true, ms);
        return;
    }
}

static void pollStream(Stream& port, LineBuf& lb, unsigned long now) {
    while (port.available() > 0) {
        char c = (char)port.read();
        if (c == '\r') continue;
        if (c == '\n') {
            lb.buf[lb.len] = '\0';
            if (lb.len > 0) handleCommandLine(lb.buf, now, port);
            lb.len = 0;
            continue;
        }
        if (lb.len + 1 < sizeof(lb.buf)) {
            lb.buf[lb.len++] = c;
        } else {
            lb.len = 0;  // line too long: drop it, keep parser in sync
        }
    }
}

// --- Encoder interrupt ---------------------------------------------------
// CLK falling edge: DT level decides direction.
void onEncoderClk() {
    if (digitalRead(PIN_ENC_DT) != LOW) {
        encoderPos++;
    } else {
        encoderPos--;
    }
}

// --- Arduino entry points -------------------------------------------------

void setup() {
    pinMode(PIN_ENC_CLK, INPUT_PULLUP);
    pinMode(PIN_ENC_DT, INPUT_PULLUP);
    pinMode(PIN_ENC_SW, INPUT_PULLUP);
    pinMode(PIN_FLOAT, INPUT_PULLUP);
    pinMode(PIN_RELAY, OUTPUT);
    pinMode(PIN_BUZZER, OUTPUT);

    digitalWrite(PIN_RELAY, RELAY_OFF);
    digitalWrite(PIN_BUZZER, LOW);

    Serial.begin(SERIAL_BAUD);
    Serial1.begin(BT_BAUD);  // HM-10 / AT-09 BLE-UART bridge

    lcd.init();
    lcd.backlight();
    lcd.setCursor(0, 0);
    lcd.print("Test sprzetu...");
    // Short blocking splash only; the main loop itself never blocks.
    delay(1000);
    lcd.clear();

    attachInterrupt(digitalPinToInterrupt(PIN_ENC_CLK), onEncoderClk, FALLING);

    Serial.println(F("# Mega2560 watering controller ready"));
    Serial.println(F("# USB+BLE commands: {\"cmd\":\"water\",\"ms\":5000} {\"cmd\":\"stop\"} {\"cmd\":\"status\"}"));
}

void loop() {
    unsigned long now = millis();

    pollStream(Serial, usbLine, now);
    pollStream(Serial1, btLine, now);

    // Timed watering from serial/BLE. The float interlock wins over the timer:
    // an empty tank cancels the run immediately (pump must not run dry).
    // Abort notices are broadcast so the phone sees them even mid-telemetry.
    bool timedActive = false;
    if (pumpDeadline != 0) {
        if (!isWaterPresent()) {
            pumpDeadline = 0;
            broadcastError(F("no_water"), false, 0);
        } else if (now >= pumpDeadline) {
            pumpDeadline = 0;
            broadcastStatus(false, 0);
        } else {
            timedActive = true;
        }
    }

    // Manual verification behaviour (hardware doc ch. 5): pump runs while the
    // encoder button is held AND water is present.
    // TODO: replace with menu/auto logic; keep setPump() as the choke point.
    bool pumpOn = setPump(isButtonPressed() || timedActive);

    static unsigned long lastLcd = 0;
    static unsigned long lastSerial = 0;

    if (now - lastLcd >= STATUS_UPDATE_MS) {
        lastLcd = now;
        long enc;
        noInterrupts();
        enc = encoderPos;
        interrupts();
        updateLcd(readSoilRaw(), isWaterPresent(), enc, pumpOn);
    }

    if (now - lastSerial >= SERIAL_UPDATE_MS) {
        lastSerial = now;
        broadcastStatus(pumpOn, timedMsLeft(now));
    }
}
