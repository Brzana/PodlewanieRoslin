# PodlewanieRoślinek

ESP32-based plant watering system. The ESP32 drives the pump/valves, and
Android/iOS apps control it over Bluetooth Low Energy.

```
Android app ──┐                     ┌── pump / valves
              ├── BLE (GATT) ──►  ESP32
iOS app ──────┘                     └── sensors
```

## Layout

| Folder | What | Stack |
| --- | --- | --- |
| `firmware/` | Watering controller | ESP32, PlatformIO (Arduino framework, C++) |
| `android/` | Android client | Kotlin, Jetpack Compose, Gradle (single module) |
| `ios/` | iOS client | Swift, SwiftUI, XcodeGen |
| `docs/ble-protocol.md` | Shared BLE contract | UUIDs + JSON payloads — keep all three sides in sync |
| `docs/wiring.md` | Hardware wiring | ESP32 pin map — keep `firmware/include/pins.h` in sync |

## Quick start

**Firmware** (needs [PlatformIO](https://platformio.org/)):

```sh
cd firmware
pio run                # build
pio run -t upload      # flash
pio device monitor     # serial monitor, 115200 baud
```

**Android** (needs JDK 17; on Windows use `gradlew.bat`):

```sh
cd android
./gradlew assembleDebug
```

**iOS** (needs macOS with [XcodeGen](https://github.com/yonaskolb/XcodeGen) + Xcode):

```sh
cd ios
xcodegen generate      # creates the .xcodeproj (it is gitignored)
open PodlewanieRoslinek.xcodeproj
```

## Docs

- [BLE protocol](docs/ble-protocol.md) — service/characteristic UUIDs and the
  JSON command format shared by the firmware and both apps.
- [Hardware wiring](docs/wiring.md) — ESP32 pin map, power wiring, and pin
  constraints.
