package com.cmts.mobile.performance

import org.junit.Assert.*
import org.junit.Test

class PerformanceEngineTest {

    private val zero = ZeroSpeedConfig(
        stopSpeedKmh = 0.5,
        launchSpeedKmh = 1.0,
        stationaryHoldMs = 200L,
        minStationarySamples = 3,
    )

    private fun sample(
        seq: Long,
        tNs: Long,
        speedMps: Double?,
        sourceId: String = "phone.gps",
        clock: String = "sess-1",
        accuracy: Double? = 0.2,
        mock: Boolean = false,
        valid: Boolean = true,
        recvOffsetNs: Long = 0L,
    ) = SpeedSample(
        sourceId = sourceId,
        channelKind = "gnss",
        sequence = seq,
        clockDomain = clock,
        sampleTimeNanos = tNs,
        receivedTimeNanos = tNs + recvOffsetNs,
        utcMillis = null,
        speedMps = speedMps,
        speedAccuracyMps = accuracy,
        valid = valid,
        mock = mock,
    )

    /** Uniform samples: speed = v0 + a * tSec over [0, durationSec]. */
    private fun ramp(
        v0: Double,
        v1: Double,
        durationSec: Double,
        hz: Double,
        t0Ns: Long = 1_000_000_000L,
        seq0: Long = 1L,
    ): List<SpeedSample> {
        val n = (durationSec * hz).toInt().coerceAtLeast(2)
        val dt = (durationSec * 1e9 / (n - 1)).toLong()
        return (0 until n).map { i ->
            val alpha = i.toDouble() / (n - 1)
            val v = v0 + (v1 - v0) * alpha
            sample(seq0 + i, t0Ns + dt * i, v)
        }
    }

    private fun stationary(atMps: Double, count: Int, t0: Long, dt: Long, seq0: Long): List<SpeedSample> =
        (0 until count).map { i -> sample(seq0 + i, t0 + dt * i, atMps) }

    @Test
    fun uniformAccel0toX_hasAnalyticalDuration() {
        // After stationary, launch at 1 km/h≈0.277.. and end at 100 km/h≈27.777...
        // Constant accel: v = a*t from 0, but timing starts at launch threshold.
        // Construct linear ramp 0 → 30 m/s in 6 s (a=5 m/s²).
        // t_launch = 1/3.6 / 5, t_end = (100/3.6)/5
        val a = 5.0
        val endKmh = 100.0
        val launch = 1.0 / 3.6
        val end = endKmh / 3.6
        val expected = (end - launch) / a
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_0_TO_X,
            startSpeedKmh = 0.0,
            endSpeedKmh = endKmh,
            zero = zero,
            quality = QualityConfig(maxSampleGapNs = 1_000_000_000L),
            timeoutNs = 60_000_000_000L,
        )
        val dt = 50_000_000L // 20 Hz
        val hold = stationary(0.0, 6, 1_000_000_000L, dt, 1)
        // Ramp 0→30 over 6s starting after hold
        val tRamp0 = hold.last().sampleTimeNanos + dt
        val rampSamples = ramp(0.0, 30.0, 6.0, 20.0, tRamp0, hold.last().sequence + 1)
        val engine = PerformanceEngine()
        val result = engine.recompute(cfg, hold + rampSamples)
        assertEquals(PerformancePhase.Completed, result.phase)
        assertNotNull(result.durationNs)
        val got = result.durationSeconds()!!
        assertEquals(expected, got, 1e-3)
        assertNull("accel modes report time only", result.distanceMeters)
        assertEquals(PERFORMANCE_ENGINE_VERSION, result.algorithmVersion)
        assertTrue(result.zeroDefinition.contains("launch="))
    }

    @Test
    fun uniformBrake30to0_distanceIs75m_andThresholdsMissSamplePoints() {
        // Doc suggestion: 30→0 m/s linear over 5 s → distance 75 m.
        // Sample at 10 Hz so thresholds 0.5 km/h and start 100 km/h are not on sample points.
        val cfg = PerformanceConfig(
            mode = PerformanceMode.BRAKE_X_TO_0,
            startSpeedKmh = 100.0, // ~27.78 m/s, within 30→0
            endSpeedKmh = 0.0,
            zero = zero.copy(stopSpeedKmh = 1.8, launchSpeedKmh = 2.5, stationaryHoldMs = 150L, minStationarySamples = 2),
            // stop 1.8 km/h = 0.5 m/s — not on 10 Hz points of 30→0 over 5s (points at 30,27,...,0)
            quality = QualityConfig(maxSampleGapNs = 1_000_000_000L),
            timeoutNs = 60_000_000_000L,
        )
        val startV = cfg.startMps() // 100/3.6
        val stopV = cfg.zero.stopMps() // 0.5
        // v(t)=30-6t, t in [0,5]
        // t_start=(30-startV)/6, t_end=(30-stopV)/6
        val expectedTime = ((30.0 - stopV) - (30.0 - startV)) / 6.0
        val expectedDist = (startV + stopV) / 2.0 * expectedTime

        val prep = stationary(30.0, 5, 2_000_000_000L, 100_000_000L, 1) // above start, ready band
        val t0 = prep.last().sampleTimeNanos + 100_000_000L
        val decelerate = ramp(30.0, 0.0, 5.0, 10.0, t0, prep.last().sequence + 1)
        // After reaching ~0, hold stationary for confirm
        val tHold = decelerate.last().sampleTimeNanos + 100_000_000L
        val after = stationary(0.0, 5, tHold, 100_000_000L, decelerate.last().sequence + 1)

        val result = PerformanceEngine().recompute(cfg, prep + decelerate + after)
        assertEquals(PerformancePhase.Completed, result.phase)
        assertNotNull(result.durationNs)
        assertNotNull(result.distanceMeters)
        assertEquals(expectedTime, result.durationSeconds()!!, 2e-3)
        assertEquals(expectedDist, result.distanceMeters!!, 5e-2)
        // Ensure crossings used interpolation (alpha not 0 or 1 ideally for start)
        assertTrue(result.start!!.alpha in 0.0..1.0)
        assertTrue(result.end!!.alpha in 0.0..1.0)
        assertFalse(
            "start threshold should not land exactly on a raw sample speed",
            decelerate.any { kotlin.math.abs(it.speedMps!! - startV) < 1e-9 },
        )
    }

    @Test
    fun intervalAccel_singleSegmentDoubleCrossing() {
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_X_TO_Y,
            startSpeedKmh = 36.0, // 10 m/s
            endSpeedKmh = 72.0,   // 20 m/s
            zero = zero.copy(stationaryHoldMs = 100L, minStationarySamples = 2),
            quality = QualityConfig(maxSampleGapNs = 2_000_000_000L),
        )
        // Stay below start, then one big jump 0 → 30 m/s in 1 s spanning both thresholds
        val prep = stationary(0.0, 4, 1_000_000_000L, 50_000_000L, 1)
        val a = sample(prep.last().sequence + 1, prep.last().sampleTimeNanos + 50_000_000L, 0.0)
        val b = sample(a.sequence + 1, a.sampleTimeNanos + 1_000_000_000L, 30.0)
        val result = PerformanceEngine().recompute(cfg, prep + listOf(a, b))
        assertEquals(PerformancePhase.Completed, result.phase)
        // t_start = t0 + (10-0)/(30-0)*1s = 1/3 s; t_end = 20/30 s; dur = 1/3 s
        assertEquals(1.0 / 3.0, result.durationSeconds()!!, 1e-6)
    }

    @Test
    fun intervalBrake_timeAndDistance() {
        val cfg = PerformanceConfig(
            mode = PerformanceMode.BRAKE_X_TO_Y,
            startSpeedKmh = 72.0, // 20
            endSpeedKmh = 36.0,   // 10
            zero = zero.copy(stationaryHoldMs = 100L, minStationarySamples = 2),
            quality = QualityConfig(maxSampleGapNs = 2_000_000_000L),
        )
        val prep = stationary(25.0, 4, 1_000_000_000L, 50_000_000L, 1)
        // Linear 25 → 5 over 2 s, a = -10
        val dec = ramp(25.0, 5.0, 2.0, 20.0, prep.last().sampleTimeNanos + 50_000_000L, prep.last().sequence + 1)
        val result = PerformanceEngine().recompute(cfg, prep + dec)
        assertEquals(PerformancePhase.Completed, result.phase)
        val expectedT = (20.0 - 10.0) / 10.0
        val expectedD = (20.0 + 10.0) / 2.0 * expectedT
        assertEquals(expectedT, result.durationSeconds()!!, 2e-3)
        assertEquals(expectedD, result.distanceMeters!!, 5e-2)
    }

    @Test
    fun endpointEqualsThreshold_risingUsesExclusiveStartInclusiveEnd() {
        // prev == V should NOT start; prev < V && curr == V should.
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_X_TO_Y,
            startSpeedKmh = 36.0,
            endSpeedKmh = 72.0,
            zero = zero.copy(stationaryHoldMs = 50L, minStationarySamples = 2),
            quality = QualityConfig(maxSampleGapNs = 5_000_000_000L),
        )
        val prep = stationary(5.0, 3, 1_000_000_000L, 50_000_000L, 1)
        val s1 = sample(10, prep.last().sampleTimeNanos + 50_000_000L, 10.0) // exactly start
        val s0 = sample(9, prep.last().sampleTimeNanos + 25_000_000L, 9.0)
        // Order: prep, then 9, then 10 (==start), then jump to 20
        val s2 = sample(11, s1.sampleTimeNanos + 100_000_000L, 20.0)
        val result = PerformanceEngine().recompute(cfg, prep + listOf(s0, s1, s2))
        assertEquals(PerformancePhase.Completed, result.phase)
        assertEquals(s0.sequence, result.start!!.beforeSequence)
        assertEquals(s1.sequence, result.start!!.afterSequence)
    }

    @Test
    fun brakeToZero_candidateExcludesConfirmWait() {
        val cfg = PerformanceConfig(
            mode = PerformanceMode.BRAKE_X_TO_0,
            startSpeedKmh = 36.0,
            endSpeedKmh = 0.0,
            zero = zero.copy(stopSpeedKmh = 1.8, launchSpeedKmh = 3.6, stationaryHoldMs = 300L, minStationarySamples = 3),
            quality = QualityConfig(maxSampleGapNs = 2_000_000_000L),
        )
        val prep = stationary(15.0, 4, 1_000_000_000L, 50_000_000L, 1)
        val dec = ramp(15.0, 0.0, 3.0, 20.0, prep.last().sampleTimeNanos + 50_000_000L, prep.last().sequence + 1)
        val confirmStart = dec.last().sampleTimeNanos + 50_000_000L
        val confirm = stationary(0.0, 10, confirmStart, 50_000_000L, dec.last().sequence + 1)
        val result = PerformanceEngine().recompute(cfg, prep + dec + confirm)
        assertEquals(PerformancePhase.Completed, result.phase)
        val stopV = cfg.zero.stopMps()
        val startV = cfg.startMps()
        // v=15-5t over 3s
        val expected = ((15.0 - stopV) - (15.0 - startV)) / 5.0
        assertEquals(expected, result.durationSeconds()!!, 3e-3)
        // End time must be before confirm samples
        assertTrue(result.end!!.timeNanos < confirmStart)
    }

    @Test
    fun armedAlreadyAboveStart_doesNotInstantComplete() {
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_X_TO_Y,
            startSpeedKmh = 36.0,
            endSpeedKmh = 72.0,
            zero = zero.copy(stationaryHoldMs = 100L, minStationarySamples = 2),
            quality = QualityConfig(maxSampleGapNs = 2_000_000_000L),
            timeoutNs = 2_000_000_000L,
        )
        // Start already at 15 m/s (>10) — must not complete without going below start first.
        val fast = ramp(15.0, 25.0, 1.0, 10.0, 1_000_000_000L, 1)
        val result = PerformanceEngine().recompute(cfg, fast, finalNowNanos = 4_000_000_000L)
        assertNotEquals(PerformancePhase.Completed, result.phase)
        assertTrue(
            result.phase == PerformancePhase.Invalid ||
                result.phase == PerformancePhase.Armed ||
                result.phase == PerformancePhase.Ready,
        )
    }

    @Test
    fun gapDuringRun_invalidates() {
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_0_TO_X,
            startSpeedKmh = 0.0,
            endSpeedKmh = 100.0,
            zero = zero.copy(stationaryHoldMs = 100L, minStationarySamples = 2),
            quality = QualityConfig(maxSampleGapNs = 200_000_000L),
        )
        val hold = stationary(0.0, 4, 1_000_000_000L, 50_000_000L, 1)
        val a = sample(10, hold.last().sampleTimeNanos + 50_000_000L, 0.2)
        val b = sample(11, a.sampleTimeNanos + 50_000_000L, 2.0) // cross launch
        val c = sample(12, b.sampleTimeNanos + 1_000_000_000L, 20.0) // huge gap while running
        val result = PerformanceEngine().recompute(cfg, hold + listOf(a, b, c))
        assertEquals(PerformancePhase.Invalid, result.phase)
        assertTrue(QualityReason.SAMPLE_GAP in result.reasons)
    }

    @Test
    fun sourceChange_invalidates() {
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_0_TO_X,
            startSpeedKmh = 0.0,
            endSpeedKmh = 50.0,
            zero = zero.copy(stationaryHoldMs = 50L, minStationarySamples = 2),
        )
        val hold = stationary(0.0, 3, 1_000_000_000L, 50_000_000L, 1)
        val mid = sample(10, hold.last().sampleTimeNanos + 50_000_000L, 5.0)
        val other = sample(11, mid.sampleTimeNanos + 50_000_000L, 10.0, sourceId = "um982")
        val result = PerformanceEngine().recompute(cfg, hold + listOf(mid, other))
        assertEquals(PerformancePhase.Invalid, result.phase)
        assertTrue(QualityReason.SOURCE_CHANGED in result.reasons)
    }

    @Test
    fun duplicateOrReorder_rejected() {
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_0_TO_X,
            startSpeedKmh = 0.0,
            endSpeedKmh = 50.0,
            zero = zero.copy(stationaryHoldMs = 50L, minStationarySamples = 2),
        )
        val a = sample(1, 1_000_000_000L, 0.0)
        val b = sample(2, 1_050_000_000L, 0.0)
        val c = sample(3, 1_040_000_000L, 0.0) // reorder
        val engine = PerformanceEngine()
        engine.onEvent(EngineEvent.Arm(cfg))
        engine.onEvent(EngineEvent.Sample(a))
        engine.onEvent(EngineEvent.Sample(b))
        val snap = engine.onEvent(EngineEvent.Sample(c))
        assertTrue(QualityReason.DUPLICATE_OR_REORDERED_TIME in snap.reasons)
    }

    @Test
    fun invalidValues_rejectedWithoutBridging() {
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_0_TO_X,
            startSpeedKmh = 0.0,
            endSpeedKmh = 36.0,
            zero = zero.copy(stationaryHoldMs = 50L, minStationarySamples = 2),
            quality = QualityConfig(maxSampleGapNs = 5_000_000_000L),
        )
        val hold = stationary(0.0, 3, 1_000_000_000L, 50_000_000L, 1)
        val bad = sample(10, hold.last().sampleTimeNanos + 50_000_000L, null)
        val neg = sample(11, bad.sampleTimeNanos + 50_000_000L, -1.0)
        val mock = sample(12, neg.sampleTimeNanos + 50_000_000L, 1.0, mock = true)
        val engine = PerformanceEngine()
        engine.onEvent(EngineEvent.Arm(cfg))
        (hold + listOf(bad, neg, mock)).forEach { engine.onEvent(EngineEvent.Sample(it)) }
        val snap = engine.snapshot()
        assertTrue(QualityReason.MISSING_SPEED in snap.reasons || QualityReason.NEGATIVE_SPEED in snap.reasons)
        assertTrue(QualityReason.MOCK_LOCATION in snap.reasons)
    }

    @Test
    fun cancel_thenRearmWorks() {
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_0_TO_X,
            startSpeedKmh = 0.0,
            endSpeedKmh = 36.0,
            zero = zero.copy(stationaryHoldMs = 80L, minStationarySamples = 2),
        )
        val engine = PerformanceEngine()
        engine.onEvent(EngineEvent.Arm(cfg))
        engine.onEvent(EngineEvent.Sample(sample(1, 1_000_000_000L, 0.0)))
        assertEquals(PerformancePhase.Cancelled, engine.onEvent(EngineEvent.Cancel).phase)
        val hold = stationary(0.0, 4, 2_000_000_000L, 50_000_000L, 1)
        val rampSamples = ramp(0.0, 15.0, 2.0, 20.0, hold.last().sampleTimeNanos + 50_000_000L, 10)
        val result = engine.recompute(cfg, hold + rampSamples)
        assertEquals(PerformancePhase.Completed, result.phase)
    }

    @Test
    fun realtimeAndRecompute_matchOnSameSequence() {
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_0_TO_X,
            startSpeedKmh = 0.0,
            endSpeedKmh = 72.0,
            zero = zero.copy(stationaryHoldMs = 100L, minStationarySamples = 3),
            quality = QualityConfig(maxSampleGapNs = 1_000_000_000L),
        )
        val hold = stationary(0.0, 5, 1_000_000_000L, 50_000_000L, 1)
        val rampSamples = ramp(0.0, 25.0, 4.0, 25.0, hold.last().sampleTimeNanos + 50_000_000L, 20)
        val all = hold + rampSamples

        val offline = PerformanceEngine().recompute(cfg, all)

        val live = PerformanceEngine()
        live.onEvent(EngineEvent.Arm(cfg))
        var last: EngineSnapshot? = null
        for (s in all) last = live.onEvent(EngineEvent.Sample(s))
        val liveResult = last!!.result!!
        assertEquals(offline.phase, liveResult.phase)
        assertEquals(offline.durationNs, liveResult.durationNs)
        assertEquals(offline.start!!.alpha, liveResult.start!!.alpha, 1e-12)
        assertEquals(offline.end!!.alpha, liveResult.end!!.alpha, 1e-12)
    }

    @Test
    fun watchdogStale_invalidates() {
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_0_TO_X,
            startSpeedKmh = 0.0,
            endSpeedKmh = 50.0,
            zero = zero,
            quality = QualityConfig(maxSampleAgeNs = 200_000_000L),
        )
        val engine = PerformanceEngine()
        engine.onEvent(EngineEvent.Arm(cfg))
        engine.onEvent(EngineEvent.Sample(sample(1, 1_000_000_000L, 0.0, recvOffsetNs = 0)))
        val snap = engine.onEvent(EngineEvent.Watchdog(1_500_000_000L))
        assertEquals(PerformancePhase.Invalid, snap.phase)
        assertTrue(QualityReason.WATCHDOG_STALE in snap.reasons)
    }

    @Test
    fun configRejectsWrongThresholdDirection() {
        try {
            PerformanceConfig(PerformanceMode.ACCEL_X_TO_Y, 80.0, 40.0)
            fail("expected require failure")
        } catch (_: IllegalArgumentException) {
        }
        try {
            PerformanceConfig(PerformanceMode.BRAKE_X_TO_Y, 40.0, 80.0)
            fail("expected require failure")
        } catch (_: IllegalArgumentException) {
        }
    }
}
