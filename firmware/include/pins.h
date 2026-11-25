#pragma once

#include <Arduino.h>

// Hardware pin assignments for Arduino Mega 2560.
// MUST stay in sync with docs/hardware_documentation.md (source of truth)
// and docs/wiring.md (quick reference).
//
// Power (see source-of-truth doc, ch. 3):
//   12 V rail (module DC-IN/OUT) -> Mega VIN + GND, and relay COM/NO -> pump.
//   5 V rail (module 5V OUT)     -> LCD, encoder, soil sensor, relay coil.
//   Mega pin 5V is NOT tied to the module's 5V OUT (USB + 12 V may coexist).
// Pins 0/1 (RX0/TX0) are reserved for USB upload + Serial Monitor.

// --- Rotary encoder KY-040 ---
#define PIN_ENC_CLK 2  // CLK, INPUT_PULLUP, HW interrupt INT4
#define PIN_ENC_DT 3   // DT,  INPUT_PULLUP, HW interrupt INT5
#define PIN_ENC_SW 4   // SW button, INPUT_PULLUP, active LOW

// --- Water level float switch ZP5210 ---
// Second wire goes to GND. LOW = water present, HIGH = tank empty.
#define PIN_FLOAT 5

// --- Pump relay JQC3F-5VDC-C ---
// IN pin of the relay module. Most modules are active LOW.
#define PIN_RELAY 7
#define RELAY_ON LOW
#define RELAY_OFF HIGH

// --- Buzzer (optional, not mounted) ---
#define PIN_BUZZER 11

// --- LCD 1602 via I2C backpack (fixed Mega I2C pins) ---
#define PIN_LCD_SDA 20
#define PIN_LCD_SCL 21

// --- BLE bridge HM-10 / AT-09 (transparent UART on Serial1) ---
// Mega TX1 (5 V) -> module RXD *via voltage divider*; module TXD -> Mega RX1.
#define PIN_BT_TX 18
#define PIN_BT_RX 19
// Optional: EN/KEY for AT mode (unconnected = run mode), STATE for link LED.
#define PIN_BT_KEY 8

// --- Soil moisture sensor LM393 (analog out only, DO unused) ---
// Inverted characteristic: dry/air ~= 950-1023, wet/water ~= 300-450.
#define PIN_SOIL A0
