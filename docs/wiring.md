# Hardware wiring — ESP32 DevKit (esp32dev)

Pin map for the watering controller. **Any pin change must update
`firmware/include/pins.h` and this file in the same change** — the firmware
compiles fine against a stale pin map; it only fails on the bench.

Status: draft pinout (hardware not wired yet) — treat the tables below as the
current plan and update them as components are added.

## Pin map

| ESP32 pin | Connected to | Direction | Notes |
| --- | --- | --- | --- |
| GPIO 34 | Soil moisture sensor, analog out | analog input | ADC1 — analog reads keep working while WiFi/BLE is on |
| GPIO 26 | Pump relay IN (or MOSFET gate) | digital output | safe non-strapping pin; relay idle state = pump off |
| GPIO 27 | Water-level float switch | digital input, pull-up | LOW = tank empty |
| GPIO 2 | Onboard status LED | digital output | strapping pin, safe as LED output after boot |
| 5V / VIN | Relay module VCC, sensor VCC | — | check sensor voltage range; some modules need 3.3V |
| GND | Common ground: ESP32 + all modules + pump supply | — | no exceptions |

## ESP32 pin constraints (checked before choosing any new pin)

- **GPIO 6–11:** wired to the internal flash — never usable.
- **GPIO 34/35/36/39:** input-only, no internal pull-ups — fine for analog
  sensors, useless for outputs or pull-up switches.
- **Strapping pins 0, 2, 5, 12, 15:** sampled at reset to pick boot mode.
  GPIO 0 and 12 are the dangerous ones — a module holding GPIO 12 high at
  reset bricks boot; GPIO 0 must be high/floating (devkit buttons handle it).
- **ADC2 (GPIO 0, 2, 4, 12–15, 25–27):** analog reads fail while the radio is
  on. This project is BLE-first, so **all analog sensors go on ADC1
  (GPIO 32–39)**.
- GPIO max drive current ≈ 40 mA — always switch the pump through a
  relay/MOSFET, never straight from a pin.

## Power

- Pump/valve gets its own 5V supply (or VIN when USB-powered); all grounds
  common.
- Relay module coils run on 5V, control input is 3.3V-logic compatible —
  verify before using a random relay board.
- Capacitive soil sensors output ≈ 10–80 % duty vs moisture; calibrate the
  0–100 scale in firmware, don't assume raw ADC = percent.
