# PodlewanieRoślinek

Arduino Mega 2560 plant-watering system. The Mega reads the soil sensor and
float switch, drives the 12 V membrane pump through a relay, and is operated
locally via LCD 1602 (I2C) + KY-040 encoder — or from Android / iOS phones
over BLE through an HM-10 / AT-09 bridge on `Serial1`.

```
soil sensor (A0) ──┐
float switch (D5) ─┼──► Arduino Mega 2560 ──► relay (D7) ──► 12 V pump
encoder (D2/D3/D4) ┘         │  ▲
                        LCD 1602 │ Serial1 9600 (pins 18/19)
                           (SDA20/SCL21) │ HM-10 / AT-09 (BLE 0xFFE0/0xFFE1)
                                         │ Android + iOS apps (JSON lines)
```

Hardware source of truth: [docs/hardware_documentation.md](docs/hardware_documentation.md)
(Polish) — BOM, Mega pinout, 12 V / 5 V power distribution, sensor
characteristics, HM-10 wiring (ch. 6), and the base hardware-test sketch.
It wins over every other doc on conflicts.

## Layout

| Folder | What | Stack |
| --- | --- | --- |
| `firmware/` | Watering controller | Arduino Mega 2560, PlatformIO (Arduino framework, C++) |
| `android/` | **Active** BLE client | Kotlin, Jetpack Compose, built-in `android.bluetooth` |
| `ios/` | **Active** BLE client | Swift, SwiftUI, CoreBluetooth, XcodeGen |
| `docs/hardware_documentation.md` | **Source of truth** | Hardware, pinout, power, sensors, HM-10, test sketch |
| `docs/wiring.md` | Wiring quick reference | Mega pin map + HM-10 rows |
| `docs/ble-protocol.md` | **Active phone contract** | HM-10 UUIDs + `water`/`stop`/`status` JSON + 20 B rule |
| `docs/usb-protocol.md` | Cable fallback | Same lines over USB (flash / monitor / OTG) |

## Quick start

**Firmware** (needs [PlatformIO](https://platformio.org/)):

```sh
cd firmware
pio run                # build (target: Arduino Mega 2560)
pio run -t upload      # flash
pio device monitor     # serial monitor, 115200 baud
```

Code map: `include/pins.h` = Mega pinout (incl. `Serial1` on 18/19),
`include/config.h` = calibration + timing + `BT_BAUD`, `src/main.cpp` =
shared USB/BLE line parser with TODOs (menu → auto mode → EEPROM).

**HM-10 setup (once, on the bench):** AT mode → `AT+NAME=RoslinkiPump`,
baud 9600 (`AT+BAUD4`), then wire via divider per `docs/wiring.md`.

**Android** (needs JDK 17; on Windows use `gradlew.bat`):

```sh
cd android
./gradlew assembleDebug
```

Grant the Bluetooth runtime permissions, scan, connect `RoslinkiPump`, then
water 5/30 s, stop, or refresh status. Test on a real device.

**iOS** (needs macOS with [XcodeGen](https://github.com/yonaskolb/XcodeGen) + Xcode):

```sh
cd ios
xcodegen generate      # creates the .xcodeproj (it is gitignored)
```

Open on a real device (Simulator has no BLE radio) and connect the same way.

## Docs

- [Hardware documentation](docs/hardware_documentation.md) — **source of
  truth**: components, Mega pinout, power wiring, sensors, HM-10.
- [Hardware wiring](docs/wiring.md) — Mega pin map + power summary, kept in
  sync with the doc above and `firmware/include/pins.h`.
- [BLE protocol](docs/ble-protocol.md) — **active**: HM-10 UUIDs, framing,
  commands, status/error JSON. Firmware + both apps must stay in sync.
- [USB protocol](docs/usb-protocol.md) — cable fallback, same line format.
