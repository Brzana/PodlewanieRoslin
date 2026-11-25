# Hardware wiring — Arduino Mega 2560

Quick-reference pin map and power summary **derived from
`docs/hardware_documentation.md`, which is the single source of truth.**
If this file and the source-of-truth doc disagree, the source-of-truth doc
wins — fix this file, don't argue with it.

Status: hardware as built (standalone controller + HM-10/AT-09 BLE–UART bridge
for the phone apps, no breadboard — the blue supply module with `S1` / `DC-IN`
is the power hub).

## Pin map

| Mega pin | Connected to | Module pin | Direction / mode | Notes |
| --- | --- | --- | --- | --- |
| Pin 2 | Encoder KY-040 | `CLK` | `INPUT_PULLUP`, HW interrupt `INT4` | Clock signal |
| Pin 3 | Encoder KY-040 | `DT` | `INPUT_PULLUP`, HW interrupt `INT5` | Direction signal |
| Pin 4 | Encoder KY-040 | `SW` | `INPUT_PULLUP` | Button, active `LOW` |
| Pin 5 | Float switch ZP5210 | wire 1 (wire 2 → `GND`) | `INPUT_PULLUP` | `LOW` = water present, `HIGH` = tank empty |
| Pin 7 | Relay 1-ch 5 V (`JQC3F-5VDC-C`) | `IN` | `OUTPUT`, active `LOW` | `LOW` = pump runs; define `RELAY_ON` / `RELAY_OFF` |
| Pin 11 | (Optional) buzzer `+` | — | `OUTPUT` | Reserved, not mounted |
| Pin 18 (TX1) | HM-10 / AT-09 | `RXD` | UART `Serial1` @ `BT_BAUD` | **Via voltage divider** (Mega 5 V → module 3.3 V) |
| Pin 19 (RX1) | HM-10 / AT-09 | `TXD` | UART `Serial1` @ `BT_BAUD` | Direct (3.3 V reads as `HIGH`) |
| Pin 20 (SDA) | LCD 1602 I2C | `SDA` | I2C data | Address `0x27` (fallback `0x3F`) |
| Pin 21 (SCL) | LCD 1602 I2C | `SCL` | I2C clock | Contrast via pot on I2C backpack |
| Pin A0 | Soil sensor LM393 | `AO` | Analog input (10-bit, 0–1023) | `DO` unused; inverted: dry ≈ 950–1023, wet ≈ 300–450 |
| VIN | Supply module | `DC-IN/OUT (+)` | Power in | 12 V feed from the module |
| GND | Supply module | `DC-IN/OUT (-)` | Ground | Common ground for the whole build |
| 0 / 1 (RX0/TX0) | — | — | — | **Keep free** — USB upload + Serial Monitor + OTG cable |

Any pin change must update `docs/hardware_documentation.md`, this file, and
`firmware/include/pins.h` in the same change — the firmware compiles fine
against a stale pin map; it only fails on the bench.

## Power

- **12 V rail** (module section `DC-IN/OUT`, barrel jack `DC-IN` is the inlet):  - → Mega `VIN` + `GND`.
  - → pump power circuit (switched on the `+12 V` side): `DC-IN/OUT (+)` →
    relay `COM`, relay `NO` → pump `+` (red dot), `DC-IN/OUT (-)` → pump `-`
    directly. Relay `NC` stays empty.
  - Mega pin `5V` is **not** connected to the module's `5V OUT`, so USB + 12 V
    can be plugged in together.
- **5 V rail** (module section `5V OUT`, `+` + `GND`): feeds LCD `VCC`, encoder
  `+`, soil `VCC`, relay `VCC`, and their `GND`s, plus one float-switch wire.

## Phone connection (HM-10 / AT-09 BLE–UART bridge on `Serial1`)

Both phone apps talk to the controller **over BLE** through the HM-10/AT-09
module on pins 18/19 (`Serial1` @ `BT_BAUD` = 9600). The module is a
transparent UART: a GATT write lands on its `RXD`, anything on its `TXD`
comes back as a notification. Contract: `docs/ble-protocol.md` (fixed
HM-10 UUIDs `0xFFE0`/`0xFFE1`, JSON lines, **20 B per write** — apps chunk,
firmware reassembles). The USB port (`Serial`) stays for flashing, Serial
Monitor, and the OTG cable fallback (`docs/usb-protocol.md`, same line
format, shared parser).

## Firmware rules (from the source-of-truth doc)

- Soil: `map(val, 1023, 300, 0, 100)` + `constrain(…, 0, 100)` after
  calibrating in the target pot. Resistive probe drifts (electrolysis).
- Float empty (`HIGH` on pin 5) must **block the relay and abort any ongoing
  watering**. 12 V membrane pump: never submerged (only the suction hose goes
  in the tank), never run dry for more than a few seconds.
- Pump timing and soak pauses: non-blocking (`millis()` / AVR timers), never
  `delay()`, so the encoder and LCD stay responsive.
