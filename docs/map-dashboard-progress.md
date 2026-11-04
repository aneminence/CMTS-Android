> **DEPRECATED:** Progress snapshot superseded by [GROKBOT_HANDOFF_2026-11-04.md](GROKBOT_HANDOFF_2026-11-04.md). Prefer the handoff + [tasks.md](tasks.md); do not treat this file as current status.

# Map + Dashboard implementation — 2026-11-04

Implemented in Android only; Windows files unchanged.

- Native Compose map canvas, pan/pinch zoom, +/- and follow/recenter controls.
- OpenStreetMap standard HTTPS tiles with app User-Agent, visible attribution,
  seven-day cache fallback, viewport-only requests (no bulk/offline downloading).
- WGS84 GNSS trajectory, accuracy circle, monotonic elapsed recording timer.
- Collapsed recording controls and expandable Dashboard/record exports.
- Track display retains the most recent 10,000 fixes; CSV still retains raw samples.
- Track segments break across gaps longer than ten seconds. No synthetic fixes.

## Validation

`gradlew.bat --offline --console=plain assembleDebug testDebugUnitTest lintDebug`

Build successful; eight unit tests pass. Lint: zero errors, ten warnings.
See `../evidence/map-build.log` and `../app/build/reports/tests/testDebugUnitTest/index.html`.
Emulator installation/launch and upward sheet gesture exercised. Screenshot checks
identified and corrected dark text contrast and increased collapsed control area.

## Blocker / not accepted yet

Emulator online tiles fail with SocketTimeoutException connecting to the resolved
tile.openstreetmap.org address. Host TCP/443 probe also failed. No map tile cache
was created. Cause may involve DNS/routing; not confirmed. No proxy/DNS settings
were changed. Runtime evidence: `../evidence/map-runtime.log`.

Await user decision on network troubleshooting versus domestic provider selection.
Map rendering with real tiles, recording/track integration, full gesture regression,
phone real GNSS, background continuity/performance and RK3588 are NOT verified.
This APK is an interim build, not map-feature acceptance.

Current design starts GNSS when recording starts; pre-recording location preview
is not implemented. Sheet currently has collapsed/expanded anchors, no third anchor.
Map service policy: https://operations.osmfoundation.org/policies/tiles/
Public tile availability is not guaranteed; a distribution-scale provider must be
evaluated before broader deployment. Sensor raw coordinates and CSV are unchanged.
