#pragma once

#include <Arduino.h>

// Tunable parameters. Hardware facts (pins, power) live in pins.h;
// this file holds values that need calibration or UI decisions.
// Source of truth for the numbers below: docs/hardware_documentation.md ch. 4.

// --- LCD ---
#define LCD_I2C_ADDR 0x27  // try 0x3F if the display stays silent
#define LCD_COLS 16
#define LCD_ROWS 2

// --- Soil moisture calibration (raw ADC, 10-bit 0-1023, inverted) ---
// Defaults from the hardware doc; recalibrate in the target pot.
#define SOIL_ADC_DRY 1023  // air / bone-dry soil -> 0 %
#define SOIL_ADC_WET 300   // wet soil / water   -> 100 %

// --- Timing (all non-blocking via millis(); never delay() the loop) ---
#define STATUS_UPDATE_MS 250UL   // LCD refresh period
#define SERIAL_UPDATE_MS 1000UL  // Serial debug period

// --- Serial (USB: programming + Serial Monitor + OTG cable) ---
#define SERIAL_BAUD 115200

// --- BLE bridge HM-10 / AT-09 on Serial1 (pins 18/19) ---
#define BT_BAUD 9600  // must match the module's UART baud (AT+BAUD)

// --- Command line (shared parser for USB Serial and BLE Serial1) ---
#define USB_LINE_MAX 160     // longest accepted command line, incl. '\0'
#define USB_MAX_WATER_MS 600000L  // single "water" run cap: 10 min
