# USB Protocol — cable link (flash / monitor / OTG fallback)

> Phone apps normally connect **over BLE** (`docs/ble-protocol.md`). This
> cable protocol stays valid for flashing, Serial Monitor, and as a wired
> fallback (OTG cable) — same line format, shared firmware parser.

No new hardware on the controller side. The host talks to the **same USB
port used for flashing and Serial Monitor**, through a plain USB OTG cable:

```
Android phone ── OTG adapter + USB A–B cable ──► Arduino Mega 2560 (Serial)
   115200 baud, 8N1, one JSON object per line, '\n'-terminated
```

Controller power stays as built (12 V into `VIN` from the supply module;
see `docs/hardware_documentation.md` ch. 3). The phone only supplies VBUS on
the cable — same situation as a laptop with USB + 12 V barrel connected.

## Cable / adapter

- Phone must support **USB OTG / host mode** (most Android phones do; enable
  OTG in system settings if present).
- Wiring: phone USB-C/micro-USB → **OTG adapter** → standard **USB A–B
  "printer" cable** → Mega USB-B socket. No level shifters, no extra modules.
- Clone boards: original Mega 2560 is USB VID `0x2341` / PID `0x0010`
  (ATmega16U2). Clones often use **CH340** (`0x1A86:0x7523`) or **CP2102**
  (`0x10C4:0xEA60`) — the Android library below ships drivers for all three,
  no manual driver install needed.

## Framing

- Host → device: one ASCII JSON object per line, terminated by `\n`
  (`\r` is ignored). Max line `USB_LINE_MAX` (160) chars — longer lines are
  dropped to keep the parser in sync.
- Device → host: one JSON object per line for anything machine-readable.
  **Rule: a line starting with `{` is a status/error object; any other line
  (debug banners start with `#`) must be ignored by the app.** This keeps
  Serial Monitor human-readable without breaking the parser.
- The controller also emits a status line every `SERIAL_UPDATE_MS` (1 s) —
  the app should treat these as live telemetry, not command replies.

## Host → device

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

## Device → host

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

## Manual check (Serial Monitor, 115200 baud)

Type `{"cmd":"status"}` and press Send (Newline) — the controller answers
with one status line. Hold the encoder button to run the pump manually;
send `{"cmd":"water","ms":5000}` to run it for 5 s from the keyboard.
