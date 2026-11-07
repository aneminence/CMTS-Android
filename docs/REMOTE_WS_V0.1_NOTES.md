# Android Remote WS v0.1 (implementation notes)

Protocol SSOT: https://github.com/aneminence/Chassis-Master-Test-Suite/blob/docs/remote-device-p0/docs/remote-device-p0-win.md

## Endpoint
`ws://<phone-lan-ip>:8787/cmts/v1` (no TLS)

## Smoke (any WS client)
1. Open CMTS app (starts RemoteBridge). Dashboard / expanded panel shows deviceId + pairing token.
2. Connect WS to `ws://IP:8787/cmts/v1`.
3. Send hello:
```json
{"v":1,"type":"device.hello","id":"h1","ts":0,"payload":{"client":"test","clientVer":"0","token":"<token>","protoMin":1,"protoMax":1}}
```
4. Subscribe:
```json
{"v":1,"type":"stream.subscribe","id":"s1","ts":0,"payload":{"channels":["core"],"maxHz":10}}
```
5. Expect `stream.sample` with `seq` monotonic; `record.start` / `record.stop` return `record.status`.

## Holds
- `CLIENT_RECORD` — CSV session (UI + remote share RecordingCommands)
- `CLIENT_REMOTE` — sensors for WS subscribers without CSV
- `CLIENT_PERF` — unchanged

## Out of scope
mDNS, file download, NTRIP, boot, hotspot, TLS.
