# CMTS Android — agent rules (short)

Scope: this repo only (`D:\CMTS\android` on P16). Do **not** modify the Windows client at `D:\CMTS\Chassis Master Test Suite`. Do **not** flash RK3588 or alter vendor system images.

## Read first
1. `docs/GROKBOT_HANDOFF_2026-11-04.md` — current status and priorities
2. `CONTEXT_INDEX.md` — where docs and code live
3. `docs/tasks.md` — ordered follow-ups
4. `docs/adr/` — locked decisions (do not reverse quietly)

## Hard boundaries
- No Windows code edits; Windows is read-only reference.
- No RK3588 flashing / vendor APK deletion.
- Do not swap basemap provider without asking the user (Google tiles are intentional; authorization is **not** claimed).
- Do not treat simulated / emulator data as phone or RK acceptance.
- Do not invent GNSS coordinates for IMU-only VBO exports.
- Prefer continuing from the current APK and sources over redoing old work.

## Build (PowerShell, repo root)
```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat --offline --console=plain assembleDebug testDebugUnitTest lintDebug
```
Proxy when needed: `127.0.0.1:7897` (see `scripts/Verify.ps1 -ProxyHost ...`).

## Git / secrets
Never commit: `local.properties`, `.toolchain/`, `evidence/`, `*.apk`/`*.aab`, `.idea/`, `.kotlin/`, build outputs.

## Product facts (do not “fix” in prose)
- UI: Jetpack Compose; map under floating bottom panel; dark + champagne `#D4AF37`.
- Vehicle axes (screen up, phone top = vehicle forward): `X=device.Y`, `Y=-device.X`, `Z=device.Z`.
- IMU request ~10000 µs (~100 Hz intent); phone measured ~106.7 Hz average — not hard 100 Hz, not GNSS rate.
- VBO is GNSS-timed rows with vehicle IMU samples ≤200 ms old; full fidelity stays in CSV/ZIP.

When blocked on product/network/licensing choices, stop and ask the user.
