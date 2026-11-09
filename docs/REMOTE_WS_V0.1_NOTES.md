# Android Remote WS v0.1 (implementation notes)

Protocol SSOT: https://github.com/aneminence/Chassis-Master-Test-Suite/blob/docs/remote-device-p0/docs/remote-device-p0-win.md

## Endpoint
`ws://<phone-lan-ip>:8787/cmts/v1` (no TLS)

## Pairing paste string
Phone UI **复制连接串** copies one line for Win paste-parse:

```
cmts://<ipv4>:8787/cmts/v1?token=<pairingToken>
```

Example: `cmts://10.11.6.214:8787/cmts/v1?token=72e3a38515d0`

Win should split host/port/path from the URI and the `token` query; remember last successful values and only re-prompt when connect fails. Token-only copy remains available. IPv4 prefers `wlan*`, skips loopback; if no usable LAN IP, phone shows Toast and copies nothing.

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

## stream.sample payload (vehicle Available)

Emitted at IMU cadence (throttled by `maxHz`). Typed **core** fields stay for Win backward compat; nested Live sources are **vehicle.* only** (Win flattens to `source.component`). Nested `phone.*` is no longer published.

### Core (always present; null when invalid — never silent 0)

| key | meaning | unit |
|---|---|---|
| `seq` | process-monotonic | — |
| `measureTimeMs` | IMU elapsed→wall | ms |
| `receiveTimeMs` | phone wall clock | ms |
| `vehicleLabel` | device label | — |
| `speedKph` | from internal GPS sample `[3]*3.6` | km/h |
| `ax`/`ay`/`az` | preferred `vehicle.accel` else phone accel | m/s²; **raw incl. gravity**; when `mountZero` on = gravity-removed linear in install vehicle axes |
| `yawRateDps` | preferred vehicle/phone gyro Z | **deg/s** (converted); when `mountZero` on = zeroed+projected vehicle Z |
| `lat`/`lon`/`altM`/`headingDeg` | from internal GPS sample | ° / m / ° |
| `valid.{gnss,speed,imu,heading}` | gates | bool |

### Nested Live sources (omit key if source absent; vehicle only)

| key | components | unit / notes |
|---|---|---|
| `vehicle.accel` | x,y,z | m/s², vehicle axes (X fwd / Y left / Z up); **raw incl. gravity**; when `mountZero` on = gravity-removed linear projected into install basis |
| `vehicle.gyro` | x,y,z | **rad/s**, vehicle axes; when `mountZero` on = bias-subtracted then projected |
| `vehicle.attitude` | roll,pitch,yaw | **deg**, vehicle Euler; when `mountZero` on = install-relative (`MountBasis.attitude`) |

`device.status` still carries `satellitesVisible` / `satellitesUsed` for early Available registration.

## Mount zero and sample coordinates

- **`mountZero` off (default / after 复位)**: nested `vehicle.*` and core `ax`/`ay`/`az`/`yawRateDps` are **raw** (accel includes gravity; attitude relative magnetic north).
- **`mountZero` on (after 归零)**: those fields are **zeroed vehicle coordinates**, using the **same transform as the phone Dashboard**:
  1. accel → gravity-removed linear (`vehicleLinearAccel`) then `MountBasis.project`
  2. gyro → subtract install bias then `MountBasis.project` (still **rad/s** nested; core yaw still **deg/s**)
  3. attitude → `MountBasis.attitude` vs install reference (deg)
- Missing measurements stay JSON `null` (never silent 0). Win should **consume transformed sample values** after reset; do not re-derive the install basis locally.

## stream.reset (mount zero / clear)

Dashboard **归零** / **复位** broadcast a **control** frame (preferred over sample queue). Win must clear plot buffers on this event. After `mount_zero`, subsequent `stream.sample` vehicle.*/core axis fields are already in zeroed vehicle coordinates (same as phone Dashboard).

Example (归零):
```json
{"v":1,"type":"stream.reset","id":"<uuid>","ts":0,"deviceId":"<id>","payload":{"reason":"mount_zero","mountZeroEnabled":true,"zeroedAtElapsedNanos":1234567890,"nextSeq":42}}
```

Example (复位):
```json
{"v":1,"type":"stream.reset","id":"<uuid>","ts":0,"deviceId":"<id>","payload":{"reason":"mount_zero_clear","mountZeroEnabled":false,"zeroedAtElapsedNanos":null,"nextSeq":43}}
```

| payload key | meaning |
|---|---|
| `reason` | `mount_zero` or `mount_zero_clear` |
| `mountZeroEnabled` | true after 归零, false after 复位 |
| `zeroedAtElapsedNanos` | elapsedRealtimeNanos at capture; null on clear |
| `nextSeq` | next `stream.sample` seq after the reset |

## Holds
- `CLIENT_RECORD` — CSV session (UI + remote share RecordingCommands)
- `CLIENT_REMOTE` — sensors for WS subscribers without CSV
- `CLIENT_PERF` — unchanged

## Out of scope
mDNS, file download, NTRIP, boot, hotspot, TLS.
