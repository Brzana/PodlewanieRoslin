# BLE Protocol — single source of truth

Firmware, Android, and iOS all depend on this contract. **A protocol change must
update all three in the same change** — nothing fails at compile time, only at
runtime.

## Constants

| Constant | Value |
| --- | --- |
| Advertised device name | `RoslinkiController` |
| Service UUID | `f7a3b4c5-6d7e-4890-a1b2-c3d4e5f6a7b8` |
| Command characteristic (write) | `a1b2c3d4-e5f6-4789-a0b1-c2d3e4f5a6b7` |
| Status characteristic (notify) | `b2c3d4e5-f6a7-4890-b1c2-d3e4f5a6b7c8` |

Firmware-side copies live in `firmware/include/ble_protocol.h`.

## Framing

UTF-8 JSON, one message per write / notification. Keep messages under 512
bytes (larger payloads require MTU negotiation, which most phones silently
cap at 185–247 bytes).

## App → device (write to command characteristic)

Water plants for N milliseconds:

```json
{"cmd": "water", "ms": 5000}
```

Stop all watering immediately:

```json
{"cmd": "stop"}
```

Request a status notification:

```json
{"cmd": "status"}
```

## Device → app (notify on status characteristic)

```json
{"battery": 87, "soil": 41, "watering": true, "msLeft": 3200}
```

| Field | Meaning |
| --- | --- |
| `battery` | battery charge, percent 0–100 |
| `soil` | soil moisture reading, arbitrary 0–100 scale |
| `watering` | whether the pump/valve is currently open |
| `msLeft` | ms remaining of the current watering, omitted when `watering` is false |

## Planned (not implemented)

- Scheduled watering: `{"cmd": "schedule", "items": [...]}`
- OTA firmware update characteristic
