# Class Requirements — Firmware Acceptance Criteria

This file tracks the extra requirements needed for this project to pass as a
class / university assignment. They apply on top of the functional behaviour
described in `docs/hardware_documentation.md` (source of truth), `docs/wiring.md`,
`docs/ble-protocol.md`, and `docs/usb-protocol.md`.

Target: `firmware/` on Arduino Mega 2560 (ATmega2560), PlatformIO, Arduino framework.

---

## R1 — Direct register use (mandatory)

High-level Arduino helpers (`digitalRead`, `digitalWrite`, `analogRead`, …)
are allowed for most code, but **at least the performance/critical parts must
touch AVR registers directly** to prove low-level understanding.

Minimum to pass:

1. **Encoder ISR on direct port read** — `src/main.cpp :: onEncoderClk()`
   must read the DT pin state from the port input register instead of
   `digitalRead()`.
   - `PIN_ENC_CLK = 2 = PE4`, `PIN_ENC_DT = 3 = PE5` → use `PINE`.
   - Direction decision = one bit test on `PINE` (e.g. `PINE & (1 << PINE5)`).
2. **Pump / relay output via port register** — `setPump()` must drive
   `PIN_RELAY = 7 = PH4` via `PORTH` (`PORTH |= / &= ~(1 << PH4)`), honouring
   `RELAY_ON` / `RELAY_OFF` (active `LOW` by default).
3. **At least one of:**
   - Float switch read via `PINE` (`PIN_FLOAT = 5 = PE3`) instead of
     `digitalRead()`, or
   - Soil ADC via registers (`ADMUX` / `ADCSRA` / `ADCW` for channel ADC0 /
     `PF0`) instead of `analogRead()`, or
   - Timing via a hardware timer (`Timer1`/`Timer3` registers `TCCRnA/B`,
     `OCRnA`, `TIMSKn`) instead of only `millis()`.

Rules:

- Every register access gets a short comment: which register, which bit, why.
- `pins.h` stays the pin-name authority; register code must match it. If a pin
  moves, both must be updated together.
- Keep the float dry-run interlock inside `setPump()` — register optimisation
  must never bypass it.

ATmega2560 quick map (Arduino pin → port/bit):

| Signal | Arduino pin | Port / bit |
| --- | --- | --- |
| `PIN_ENC_CLK` | 2 | `PE4` (`PINE4`, `INT4`) |
| `PIN_ENC_DT` | 3 | `PE5` (`PINE5`, `INT5`) |
| `PIN_ENC_SW` | 4 | `PG5` |
| `PIN_FLOAT` | 5 | `PE3` |
| `PIN_RELAY` | 7 | `PH4` |
| `PIN_BUZZER` | 11 | `PB5` |
| `PIN_SOIL` | A0 | `PF0` / ADC0 |

---

## R2 — No blocking delays (mandatory)

The main `loop()` must stay responsive (encoder, LCD, float interlock, BLE).

- **Forbidden anywhere:** `delay()`. No exceptions — not in `loop()`, not in
  helpers, not in menu/auto code.
- **Avoid** `delayMicroseconds()` for anything > a few µs and any other busy
  polling loop. If a peripheral needs settling time, do it with a
  `millis()`-based state machine or a hardware timer.
- **Allowed:** non-blocking patterns already in `src/main.cpp`:
  `millis()` deadlines (`pumpDeadline`), interval gating (`STATUS_UPDATE_MS`,
  `SERIAL_UPDATE_MS`), shared line parser over `Serial` / `Serial1`.
- LCD/Serial refresh must be interval-gated, never paced with sleeps.

Known violation — fixed:

- `setup()` LCD splash previously used `delay(1000)`. It now records
  `bootMs = millis()` and `loop()` clears the LCD after `SPLASH_MS`
  (`config.h`) while suppressing normal telemetry until then.

---

## Acceptance checklist

- [ ] `pio run` builds with no warnings introduced by register code.
- [ ] `grep -rn "delay(" firmware/src firmware/include` returns no hits
      (lowercase `delay` only; `pumpDeadline` / `STATUS_UPDATE_MS` names are fine).
- [ ] Encoder still counts both directions without lost steps.
- [ ] Relay still follows `RELAY_ON`/`RELAY_OFF` and float interlock aborts a
      watering run immediately when the tank is empty.
- [ ] `R1` register sites are commented with register + bit + reason.
- [ ] No behaviour change on BLE (`0xFFE0`/`0xFFE1`, 20 B chunks) or USB line
      protocol — machine lines still start with `{`, debug with `#`.
