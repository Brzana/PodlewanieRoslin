# BLE Protocol — phone ↔ Mega 2560 via HM-10 / AT-09

Firmware, Android, and iOS all depend on this contract. **A protocol change
must update all three in the same change** — nothing fails at compile time,
only on the radio.

Transport: HM-10 / AT-09 is a **transparent BLE–UART bridge** on Mega
`Serial1` (pins 18/19, 9600 baud). Its GATT table is fixed by the module
firmware and **cannot be customised**:

| Constant | Value |
| --- | --- |
| Advertised device name | `RoslinkiPump` (set via `AT+NAME`) |
| Service UUID | `0000FFE0-0000-1000-8000-00805F9B34FB` (16-bit `0xFFE0`) |
| UART characteristic (write + notify) | `0000FFE1-0000-1000-8000-00805F9B34FB` (16-bit `0xFFE1`) |

A GATT write to `0xFFE1` lands on the module's `RXD` (= Mega `Serial1` RX);
anything the Mega prints on `Serial1` TX comes back as a notification on
`0xFFE1`. The old ESP32-era custom UUIDs are gone and must not be reused.

## Framing

Same line format as the cable protocol (`docs/usb-protocol.md`): one ASCII
JSON object per line, terminated by `\n` (`\r` is ignored). The Mega runs
one shared line parser over both `Serial` (USB) and `Serial1` (BLE).

**20-byte rule (phone → device):** one GATT write carries at most 20 bytes
of payload. Apps **must chunk** each `\n`-terminated line into ≤ 20-byte
writes (write-with-response, in order); the firmware reassembles bytes until
`\n` and over-long lines (> `USB_LINE_MAX`) are dropped to stay in sync.
Device → phone needs no chunking in the apps — the HM-10 fragments `TXD`
data into 20-byte notifications itself; apps reassemble until `\n`.

**App parsing rule:** a line starting with `{` is a status/error object;
any other line (debug banners start with `#`) must be ignored.

## App → device (write to `0xFFE1`, chunked to 20 B)

Start timed watering (capped at `USB_MAX_WATER_MS` = 10 min, float
interlock always applies — empty tank refuses/​aborts the run):

```json
{"cmd":"water","ms":5000}
```

Cancel a timed run immediately:

```json
{"cmd":"stop"}
```

Request one status line back:

```json
{"cmd":"status"}
```

## Device → app (notify on `0xFFE1`, reassemble until `\n`)

Status (reply to `status`/`stop`, acknowledgement of `water`, run-end
notice, and 1 s telemetry):

```json
{"soil":812,"pct":23,"water":true,"pumping":true,"msLeft":3200}
```

| Field | Meaning |
| --- | --- |
| `soil` | raw ADC 0–1023 from `A0` (inverted: dry ≈ 950–1023, wet ≈ 300–450) |
| `pct` | `map(soil, 1023, 300, 0, 100)` constrained to 0–100 |
| `water` | float state: `true` = water present, `false` = tank empty |
| `pumping` | whether the relay is currently energised |
| `msLeft` | ms left of the timed run, 0 when no run is active |

Error (refused `water`: bad duration or empty tank; or tank emptied
mid-run, which also aborts the pump):

```json
{"error":"no_water","soil":980,"pct":4,"water":false,"pumping":false,"msLeft":0}
```

| `error` | Meaning |
| --- | --- |
| `no_water` | tank empty — run refused or aborted, pump forced off |
| `bad_ms` | missing/non-positive `ms` — nothing started |

## Planned (not implemented)

- Scheduled watering: `{"cmd":"schedule",...}` (needs the menu/auto-mode TODOs in `src/main.cpp` first)
- Renaming/pairing PIN change from the apps (currently AT-mode only, on the bench)
