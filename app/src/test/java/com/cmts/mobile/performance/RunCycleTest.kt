package com.cmts.mobile.performance

import org.junit.Assert.*
import org.junit.Test

class RunCycleTest {
    private val zero = ZeroSpeedConfig(0.5, 1.0, 100, 2)
    private val quality = QualityConfig(maxSampleGapNs = 2_000_000_000L)
    private val cfg = PerformanceConfig(PerformanceMode.ACCEL_0_TO_X, 0.0, 36.0, zero, quality)

    private var seq = 1L
    private var t = 1_000_000_000L
    private fun sample(v: Double) = SpeedSample(
        sourceId = "phone.gps", channelKind = "gnss", sequence = seq++, clockDomain = "test",
        sampleTimeNanos = t, receivedTimeNanos = t, speedMps = v, speedAccuracyMps = 0.1,
    )

    /** Mirrors PerformanceController.onRawGnss bookkeeping for one segment. */
    private fun feed(engine: PerformanceEngine, cycle: RunCycle<String>, v: Double, label: String): EngineSnapshot {
        val snap = engine.onEvent(EngineEvent.Sample(sample(v)))
        t += 50_000_000L
        cycle.observe(snap.phase)
        if (snap.phase == PerformancePhase.Completed) cycle.onCompleted(label)
        return snap
    }

    private fun armed(): PerformanceEngine = PerformanceEngine().also { it.onEvent(EngineEvent.Arm(cfg)) }

    private fun stationary(engine: PerformanceEngine, cycle: RunCycle<String>, label: String) =
        repeat(6) { feed(engine, cycle, 0.0, label) }

    /** 0 → 15 m/s ramp; returns final phase. */
    private fun launch(engine: PerformanceEngine, cycle: RunCycle<String>, label: String): PerformancePhase {
        var phase = PerformancePhase.Idle
        for (i in 0..60) {
            phase = feed(engine, cycle, 15.0 * i / 60, label).phase
            if (phase == PerformancePhase.Completed) break
        }
        return phase
    }

    @Test
    fun stayArmedByDefault_rearmsAfterRoundFinished() {
        val cycle = RunCycle<String>()
        assertTrue(cycle.stayArmed)
        assertEquals(RunCycle.RoundAction.REARM, cycle.onRoundFinished())
    }

    @Test
    fun cancelTurnsContinuousOff_untilArmedAgain() {
        val cycle = RunCycle<String>()
        cycle.onCancel()
        assertEquals(RunCycle.RoundAction.RELEASE, cycle.onRoundFinished())
        cycle.setStayArmed(true)
        assertEquals(RunCycle.RoundAction.REARM, cycle.onRoundFinished())
    }

    @Test
    fun scoresStayAfterRearm_andClearOnlyWhenNextRunStarts() {
        val cycle = RunCycle<String>()
        var engine = armed()
        cycle.onArmed()
        stationary(engine, cycle, "run1")
        assertEquals(PerformancePhase.Completed, launch(engine, cycle, "run1"))
        assertEquals(listOf("run1"), cycle.rows)

        // Round finished → re-arm the same config; last run's score must remain visible.
        assertEquals(RunCycle.RoundAction.REARM, cycle.onRoundFinished())
        engine = armed()
        cycle.onArmed()
        assertEquals(listOf("run1"), cycle.rows)

        // Waiting (Armed / Ready) does not clear.
        stationary(engine, cycle, "run2")
        assertNotEquals(PerformancePhase.Running, engine.snapshot().phase)
        assertEquals(listOf("run1"), cycle.rows)

        // Next run actually starts → table cleared, then refilled with only the new score.
        assertEquals(PerformancePhase.Completed, launch(engine, cycle, "run2"))
        assertEquals(listOf("run2"), cycle.rows)
    }

    @Test
    fun timeoutOrInvalidWithoutRunning_doesNotClear() {
        val cycle = RunCycle<String>()
        cycle.onCompleted("previous")
        cycle.onArmed()
        assertFalse(cycle.observe(PerformancePhase.Armed))
        assertFalse(cycle.observe(PerformancePhase.Ready))
        assertFalse(cycle.observe(PerformancePhase.StartCandidate))
        assertFalse(cycle.observe(PerformancePhase.Invalid))
        assertEquals(listOf("previous"), cycle.rows)
        assertTrue(cycle.observe(PerformancePhase.Running))
        assertTrue(cycle.rows.isEmpty())
        // Only the first start clears; later segments of the same run append normally.
        cycle.onCompleted("a")
        assertFalse(cycle.observe(PerformancePhase.Running))
        assertEquals(listOf("a"), cycle.rows)
    }

    @Test
    fun liveTableHoldsOnlyCurrentSessionRows() {
        val cycle = RunCycle<String>()
        cycle.onArmed()
        cycle.observe(PerformancePhase.Running)
        cycle.onCompleted("0-60")
        cycle.onCompleted("60-100")
        // History is never fed into the cycle; rows are exactly this session's completions.
        assertEquals(listOf("0-60", "60-100"), cycle.rows)
    }

    @Test
    fun defaultDraftsBuildAllFourConfigs() {
        val configs = configsFromDrafts(defaultSegmentDrafts()).getOrThrow()
        assertEquals(
            listOf(PerformanceMode.ACCEL_0_TO_X, PerformanceMode.ACCEL_X_TO_Y, PerformanceMode.BRAKE_X_TO_Y, PerformanceMode.BRAKE_X_TO_0),
            configs.map { it.mode },
        )
        assertTrue(configsFromDrafts(defaultSegmentDrafts().map { it.copy(selected = false) }).isFailure)
    }
}
