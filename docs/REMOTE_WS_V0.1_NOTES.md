# Android Remote WS v0.1 (implementation notes)

Protocol SSOT: https://github.com/aneminence/Chassis-Master-Test-Suite/blob/docs/remote-device-p0/docs/remote-device-p0-win.md

## Endpoint
`ws://<phone-lan-ip>:8787/cmts/v1` (no TLS)

## Sample rate (IMU vs GNSS)
- **IMU drives `stream.sample`**: emitted from sensor callbacks (accel/gyro), not GNSS `onLocationChanged`.
- **GNSS fields ride along** on those samples (last fix); `valid.gnss` / null when missing. Do not invent zeros.
- **`measureTimeMs`** follows IMU `elapsedRealtimeNanos` (mapped to wall-clock anchor), so Win curves advance at IMU cadence. GNSS alone stays ~1 Hz.
- **Throttle**: `stream.subscribe.maxHz` is the emit cap (absolute max 100). Device default / omitted maxHz = **100**.
- **Win tip**: set `maxHz` to **100** (or raise from any stale 20) to approach phone IMU (~100 Hz intent). A subscribe of `maxHz:20` will intentionally look ~20 Hz on accel/gyro plots.
- `seq` is process-monotonic; recording start/stop does not reset it.
- Backpressure: sample queue drops oldest; control frames preferred.

## Smoke (any WS client)
1. Open CMTS app (starts RemoteBridge). Dashboard / expanded panel shows deviceId + pairing token.
2. Connect WS to `ws://IP:8787/cmts/v1`.
3. Send hello:
```json
{"v":1,"type":"device.hello","id":"h1","ts":0,"payload":{"client":"test","clientVer":"0","token":"<token>","protoMin":1,"protoMax":1}}
```
4. Subscribe (use 100 for full IMU):
```json
{"v":1,"type":"stream.subscribe","id":"s1","ts":0,"payload":{"channels":["core"],"maxHz":100}}
```
5. Expect `stream.sample` with `seq` monotonic and `measureTimeMs` stepping much faster than 1 Hz when phone is moving; `record.start` / `record.stop` return `record.status`.

## Holds
- `CLIENT_RECORD` — CSV session (UI + remote share RecordingCommands)
- `CLIENT_REMOTE` — sensors for WS subscribers without CSV
- `CLIENT_PERF` — unchanged

## Out of scope
mDNS, file download, NTRIP, boot, hotspot, TLS.
