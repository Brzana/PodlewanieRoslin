#pragma once

// Hardware pin assignments — these values MUST stay in sync with docs/wiring.md.

// Capacitive soil moisture sensor, analog out (ADC1 — radio-safe)
#define PIN_SOIL_SENSOR 34

// Pump/valve relay or MOSFET control
#define PIN_PUMP 26

// Water-level float switch, LOW = tank empty (INPUT_PULLUP)
#define PIN_WATER_LEVEL 27

// Onboard status LED (strapping pin, safe as output after boot)
#define PIN_STATUS_LED 2
