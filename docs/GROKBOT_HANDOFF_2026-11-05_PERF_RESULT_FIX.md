# Perf result-output fixes (2026-11-05 evening)

Root causes addressed:
1. Phone GNSS ~1 Hz vs QualityConfig gap=500ms / age=1s → WATCHDOG_STALE / SAMPLE_GAP self-stop before thresholds. Defaults now gap=2.5s, age=3.5s; waiting-phase watchdog soft-warns only.
2. Segment drafts lived in Compose `remember` → sheet peek / pager dispose cleared thresholds. Now persisted in `files/performance/ui_settings.json` via PerformanceController.
3. Bottom four gauges gated on `Live.running` only → blank/flash during perf-only capture. Now treat `Live.serviceAlive` as live + last-good hold.
4. Results table showed all attempts including mass Invalid; session Completed rows append one-by-one via `sessionResults`.

Do not retighten phone GNSS quality defaults without outdoor evidence.
