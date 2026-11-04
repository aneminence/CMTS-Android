# CMTS Android 0.1

Standalone phone MVP for Chassis Master Test Suite: GNSS + IMU capture, Compose map UI, CSV/ZIP + VBO export.

**Current status:** see [docs/GROKBOT_HANDOFF_2026-11-04.md](docs/GROKBOT_HANDOFF_2026-11-04.md). Agent entry points: [AGENTS.md](AGENTS.md), [CONTEXT_INDEX.md](CONTEXT_INDEX.md), [docs/tasks.md](docs/tasks.md).

## Toolchain
Android Studio (Rabbit), AGP 9.5.0, Gradle 9.6.0, JBR 25, Kotlin / Compose compiler 2.2.10. Java bytecode target 17; compile/target SDK 35, min SDK 26.

The Windows Visual Studio project (`Chassis Master Test Suite`) stays a separate, read-only reference for this repo.

## Build
Open this directory in Android Studio (Gradle JDK = bundled JBR), or from PowerShell at the repo root:

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat --offline --console=plain assembleDebug testDebugUnitTest lintDebug
```

Optional verify script: `./scripts/Verify.ps1`. If you already use a local proxy: `-ProxyHost 127.0.0.1 -ProxyPort 7897`. Official Gradle Wrapper is included.

## What works (high level)
- GPS / accel / gyro / rotation-vector capture via foreground service; vehicle-frame remapping (see ADR-002).
- Dark + champagne UI; full-screen Google satellite tiles under a floating bottom panel; records list with VBO / raw ZIP export.
- Unit tests (Model, Dashboard, MapMath, Attitude, Vbo) and offline assemble/test/lint have been green on the maintainer machine; phone map gestures and ~100 Hz IMU request verified on device. Outdoor GNSS → VBOX acceptance and Google licensing remain open (see tasks).

## Not in git
`evidence/`, `local.properties`, `.toolchain/`, build outputs, and packaged `*.apk` / `*.aab` are gitignored — rebuild locally.

## Migration / toolchain history
See `docs/toolchain-upgrade.md` and related toolchain docs.
