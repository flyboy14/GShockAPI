# DW-H5600 protocol notes (branch `h5600-sound-mode`)

Everything here was decoded from Android Bluetooth (HCI snoop) captures of the official
**CASIO WATCHES** app talking to a DW-H5600-1 (module 3516), plus izivkov's own DW-H5600
captures in the Python `gshock_api` repository, and (2026-11-17) from the official app's own code:
packet codes from its Java `casiolib`, field layouts from its Flutter code decompiled with blutter
(how: the app's `docs/DW-H5600-DEVELOPMENT.md`). "Confirmed" means a write was read back changed on
the watch; the watch acknowledges many writes it then ignores.

Read sweep (2026-11-17): the watch answers `10 11 13 15 16 18 1D 1E 1F 20 22 23 24 25 26 27 28 2A 2F
39 3A 3B 40 41 42 43 44 45 47 4F 50 51 52 53 56 57 58 5A`; every other code is rejected (`FF 81 xx`).

## Transport

| Role | Characteristic | ATT handle (DW-H5600) | Write type |
|---|---|---|---|
| Read request (1 byte = packet code) | `26eb002c` | 0x16 | without response (`GetSetMode.GET`) |
| Settings data / notifications | `26eb002d` | 0x18 (CCCD 0x19) | with response (`GetSetMode.SET`) |
| LifeLog data request (DRSP) | `26eb0023` | 0x10 | with response (`GetSetMode.DATA_REQUEST`) |
| LifeLog bulk (CONVOY) | `26eb0024` | 0x13 | without response (`GetSetMode.CONVOY`, added here) |

A read is: write `<code>` to `26eb002c`, the watch notifies `<code> …` on `26eb002d`.
The official app changes settings by **read-modify-write** of the whole packet.
The watch answers `FF 81 <code>` when it rejects a command (e.g. when busy, or 0x2D which it
does not implement); `RawSettingIO.onRejected` fails the pending read at once.

## Packets

| Code | Size | Meaning | Status |
|---|---|---|---|
| `0x39` | 2 | Connection handshake: app requests `39`, watch `39 04`, app writes `39 04` back, watch `47 01`. Without it the watch drops the link after ~45 s. | Confirmed |
| `0x42` | 6 | Sound / notification mode. byte 1: `00` all off, `02` sound only, `05` custom, `08` vibe only, `0A` sound + vibe. bytes 2–5: per-function CUSTOM table (byte 5 bit `0x10` = low-battery alert sound on); rest unmapped. | Byte 1 confirmed |
| `0x51` | 6 | Watch face. byte 1: `01` face 1 (simple, weekday), `02` face 2 (customisable). bytes 2–5 (all equal): face-2 item `00` steps, `0A` heart rate, `0B` heart rate min/max, `0E` sleep evaluation, `12` sunrise/sunset, `13` moon. | Confirmed |
| `0x50` | 5 | Smart alarm (one slot): `50 <hour> <minute> <flags> <window>`; flags `0x01` on, `0x02` snooze; window = minutes before (15/30/45/60). | Confirmed |
| `0x24` | 20 | Location: `24 <slot> 01 <lat f64 BE> <lon f64 BE> <flag>`; slot 0 = home (sunrise/sunset), slot 1 = world city 2. Current official app uses flag `04` (an older capture had `01`). | Confirmed |
| `0x13` | 17 | Basic settings. byte 1 bit `0x01` = 24 h; byte 12 bit `0x20` = hourly time signal, bit `0x02` = step reminder; bytes 13–16 = night time start h, m, end h, m (binary, `17 00 08 14` = 23:00–08:20). Not kept by this watch: byte 4 date order, byte 5 weekday language, byte 8 bit `0x20` font, byte 10 bit `0x01` operation vibration. | Confirmed |
| `0x15` / `0x16` | 5 / 17 | Alarms 1 / 2–5: `<flags> <00> <hour> <minute>`; flags `0x40` on, `0x10` snooze, `0x20` one-time, `0x80` hourly chime (alarm 1). The watch keeps byte 1 = `00`. Snooze: 10 s ring, then every 5 min up to 7 times; a button press stops only the current ring, switching the alarm off ends the snooze (Casio 3516 manual). The watch reports nothing about ringing or snoozing, and rewriting the alarms or setting the time cancels a running snooze. | Confirmed |
| `0x18` | 15 | Countdown timer: `18 <h> <m> <s> 00 03 …`; byte 5 = `03` meaning unknown (kept). | Length confirmed |
| `0x1D` / `0x1E` / `0x1F` | | DST / world-city rules / city names. | (library) |
| `0x3D` | 17–18 | Status report the watch sends after DST/city writes (`3D 02/11/12 …`) and on request `3D 30`. The official app only reads it. | Not a request |
| `0x41` | 5 | Heart-rate monitor (CASIO_SETTING_FOR_HEART_RATE_MONITOR). byte 1 bits 0–1: `0` off, `1` activities + night (RunActOnly), `2` all day (AlwaysOn). Bits 2–4 (zone alert) and byte 2 (zone) are dropped by this watch. | Bits 0–1 confirmed |
| `0x43` | 17 | Targets (CASIO_SETTING_FOR_TARGET_VALUE): bytes 1–3 daily steps (LE, `10 27 00` = 10 000), byte 16 bit `0x01` notify when reached. | Confirmed |
| `0x4F` | 10 | Physical settings: byte 1 training background, bytes 2–5 sleep onset h, m / wake h, m, byte 6 flags (`0x01` max HR, `0x02` resting HR set by the user), byte 7 max HR, byte 8 resting HR, byte 9 VO2max. | Confirmed (sleep time, HR values) |
| `0x52` | 20 | Watch modes in button order (CASIO WIDGET_LIST), from byte 1, `00` = end. A mode left out is hidden. IDs: `01` time & date, `02` heart rate, `03` life log, `04` stress, `05` nightly recharge, `06` activity log, `07` cardio load, `08` breathing, `09` notifications, `0A` blood oxygen, `0B` altimeter, `0C` barometer, `0D` compass, `0E` stopwatch, `0F` timer, `10` world time, `11` almanac, `12` QR, `13` tide. | Confirmed (order, hiding) |
| `0x45` | 11 | User profile (CASIO_SETTING_FOR_USER_PROFILE). The official app's layout (BCD height, weight, birth date) is not what this watch keeps; writes are dropped. | Not writable |
| `0x2F`, `0x40` | 6 | Sensor units / run measurement. Miles (`0x2F` byte 4 bit `0x02`) and auto pause (`0x40` byte 1 bit `0x02`) are dropped. | Not writable |
| `0x21` | 3 | `21 00 04` / `21 01 04` written by the official app while browsing its screens. | Unknown |
| `0x2D` | | Exercise profile used by other models; this watch rejects it (`FF 81 2D`). | Not implemented |
| `0x25`, `0x2A`, `0x3A`, `0x3B`, `0x53`, `0x57`, `0x5A` | | Answered; partly named (0x2A program timer, 0x3A/0x3B connection/advertising parameters, 0x53 activity list, 0x57 activity settings, 0x5A operation log). | Not decoded |

## Time sync and sunrise/sunset

The watch recalculates sunrise/sunset when the time or cities are written. Writing the
DST/city packets clears the stored location, so a time sync must include it. The official app
writes, in this order: `1D` → `1E` → **`24 00`** (+ `24 01`) → `1F` names → … → `09` time.
`TimeIO.initializeForSettingTime` now calls `H5600SettingsIO.writeLocationInTimeSync()` at that
point; the coordinates come from `H5600SettingsIO.homeLocationProvider` (supplied by the app).

The watch's own sunrise/sunset formula matches the standard sunrise equation with a horizon
angle of about **−1.0°** (standard −0.833°), fitted from two readings; the app uses this to aim
"fake" coordinates precisely (see the app's `TempSun.kt`).

## LifeLog (steps)

Ported from izivkov's `h5600_health.py` (`io/H5600LifeLog.kt`, `io/H5600LifeLogIO.kt`):
setup requests `23 10 11 3B 3A 26 28 20 2F 43 13 45` (replies to `11 3B 3A 2F 13 45` echoed),
early START `00 2e ff ff ff ff ff` on DRSP, poll `00 00 00` on CONVOY until ready, setup
`04 …` / `04 01 18 00 18 00 00 00 dc 05` / `06 …`, echo of the 9-byte `06` reply, START, then
each `05 …` CONVOY fragment is acked with `07 00 …` and index announcements `00 2e <idx> <len>` /
`07 2e …` / END `04 2e …` are echoed on DRSP. Decoding: strip `05` + 2-byte checksum, concatenate,
XOR `0xFF`; 20-byte header (time UTC, day steps int32 at 14), frame `50` = 5-minute step slots.
Verified: izivkov's capture -4 → 624 steps (= official app); the user's own sync → 351.

**The sync is incremental.** The watch sends only what has not been fetched before; a sync right
after a sync carries just the newest 5-minute slots. START's last five bytes (`FF FFFFFFFF`) are the
CASIO app's "all" selector (`writeCasioDataRequestSPForQW3515Lifelog(cmd, type, idx, len)`); there is
no date parameter, so old data cannot be requested again. Store every sync.

Frame `63` (daily summary, sent when a day is complete): 109-byte records, date BCD `yy mm dd` at
0–2, steps int32 LE at 10, step goal int32 LE at 14 (layout from izivkov's `parse_lifelog.py`).

## Safer encoders (shared code)

`SettingsIO`, `AlarmsIO` and `TimerIO` now encode from the last packet read from the watch and
change only the bits they manage, instead of building packets from zeros (which wiped unknown
bytes such as the alarm snooze bit and the DW-H5600's 0x13 bytes 12–16).
