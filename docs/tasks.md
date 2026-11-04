# Tasks — CMTS Android (from 2026-11-04 handoff)

Source of truth for narrative: `GROKBOT_HANDOFF_2026-11-04.md`. Check boxes here as work lands.

## Done
- [x] **3.** Align `Vbo.kt` `[comments]` + `data-format.md` with vehicle axes (`Vehicle_*` / `vehicle.*`); Vbo unit tests pass offline.

## Open (priority order)
- [ ] **1.** Full regression: fullscreen map, panel hide/restore/scroll, orientation, file page back; ensure Start/Stop not covered by touch intercept or scroll.
- [ ] **2.** Outdoor real GNSS + moving track; export VBO; open in Windows CMTS and VBOX Test Suite (lat/lon signs, UTC midnight, NaN, column map).
- [ ] **4.** Prefer per-channel time/quality/valid flags on VBO; 200 ms sample rule is not precision sync — do not claim 100 Hz-synced GNSS.
- [ ] **5.** No-GNSS export failure is by design; if IMU-only VBO is wanted, agree anchoring — never invent GPS.
- [ ] **6.** Cap / decimate map track for large VBO (import already capped at 100000 rows); background parse + memory checks.
- [ ] **7.** Tile load: parallel limited queue / cancel policy when panning on slow networks.
- [ ] **8.** Rate: add windowed average / drop stats; keep raw timestamps; stress high-rate write + long background runs.
- [ ] **9.** Export picker: Activity recreate, URI open association, cancel, readable errors.
- [ ] **10.** CaptureService status / closed.marker, list auto-refresh after close, quality accuracy; harden error handling.
- [ ] **11.** Optional level / bias calibration later; keep raw sensors + calibration params (fixed remap ≠ calibration).
- [ ] **12.** Google shipping license + CN network stability; ask before changing basemap.

## Repo hygiene (this wave)
- [x] Strengthen `.gitignore`; add `AGENTS.md`, `CONTEXT_INDEX.md`, ADRs, this file; initial GitHub push of sources (no `evidence/`).
