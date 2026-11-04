# CONTEXT_INDEX — CMTS Android

Single index. Prefer the handoff over older docs; do not fork a second encyclopedia.

## Canonical status
| Doc | Role |
|-----|------|
| [docs/GROKBOT_HANDOFF_2026-11-04.md](docs/GROKBOT_HANDOFF_2026-11-04.md) | Latest handoff (features, evidence, priorities) |
| [docs/tasks.md](docs/tasks.md) | Ordered open tasks (item 3 done) |
| [AGENTS.md](AGENTS.md) | Short agent rules |

## Decisions (ADR)
| ADR | Topic |
|-----|--------|
| [docs/adr/ADR-001.md](docs/adr/ADR-001.md) | Jetpack Compose UI |
| [docs/adr/ADR-002.md](docs/adr/ADR-002.md) | Vehicle coordinate frame |
| [docs/adr/ADR-003.md](docs/adr/ADR-003.md) | Google tile URL (unauthorized for shipping) |

## Format / architecture (still useful)
| Doc | Notes |
|-----|--------|
| [docs/data-format.md](docs/data-format.md) | CSV / VBO / metadata channels (`Vehicle_*`) |
| [docs/architecture.md](docs/architecture.md) | High-level layout |
| [docs/development.md](docs/development.md) | Dev notes |
| [docs/toolchain.md](docs/toolchain.md) + upgrade/validation | Toolchain history |
| [docs/windows-compatibility.md](docs/windows-compatibility.md) | Windows parity notes |
| [docs/rk3588-plan.md](docs/rk3588-plan.md) | Future RK; not validated |

## Deprecated narrative (header only; keep files)
`docs/map-dashboard-progress.md`, `docs/dashboard-mapping.md`, `docs/test-report.md` — progress snapshots superseded by the 2026-11-04 handoff.

## Code map (app)
| Area | Files |
|------|--------|
| Entry / panel | `MainActivity.kt` |
| Map | `MapScreen.kt`, `MapMath.kt` |
| Capture | `CaptureService.kt` |
| Attitude / vehicle | `Attitude.kt` |
| Dashboard model | `Dashboard.kt`, `Model.kt` |
| Records / VBO | `RecordsScreen.kt`, `Vbo.kt` |
| Tests | `app/src/test/java/com/cmts/mobile/*Test.kt` |
| Icon | `app/src/main/res/drawable-nodpi/cmts_icon.png` |

## Local-only (gitignored)
`evidence/`, `local.properties`, `.toolchain/`, `**/build/`, `*.apk`

## Out of repo
Windows: `D:\CMTS\Chassis Master Test Suite` (read-only reference).
