package com.cmts.mobile.performance

import org.junit.Assert.*
import org.junit.Test

/** Analytical multi-segment: one linear accel ramp feeds two engines (0→10 and 10→20 m/s). */
class MultiSegmentEngineTest {
    private fun sample(seq: Long, t: Long, v: Double) = SpeedSample(
        sourceId = "phone.gps",
        channelKind = "gnss",
        sequence = seq,
        clockDomain = "test",
        sampleTimeNanos = t,
        receivedTimeNanos = t,
        speedMps = v,
        speedAccuracyMps = 0.1,
    )

    @Test
    fun twoAccelSegmentsOnSameRamp_haveAnalyticalTimes() {
        val zero = ZeroSpeedConfig(0.5, 1.0, 100, 2)
        val quality = QualityConfig(maxSampleGapNs = 2_000_000_000L)
        val c1 = PerformanceConfig(PerformanceMode.ACCEL_0_TO_X, 0.0, 36.0, zero, quality) // 0→10 m/s
        val c2 = PerformanceConfig(PerformanceMode.ACCEL_X_TO_Y, 36.0, 72.0, zero, quality) // 10→20

        // stationary then 0→25 m/s in 5s, a=5
        val samples = mutableListOf<SpeedSample>()
        var seq = 1L
        var t = 1_000_000_000L
        repeat(4) {
            samples += sample(seq++, t, 0.0)
            t += 50_000_000L
        }
        val n = 101
        val t0 = t
        for (i in 0 until n) {
            val alpha = i.toDouble() / (n - 1)
            samples += sample(seq++, t0 + (5_000_000_000L * alpha).toLong(), 25.0 * alpha)
        }

        val e1 = PerformanceEngine().recompute(c1, samples)
        val e2 = PerformanceEngine().recompute(c2, samples)
        assertEquals(PerformancePhase.Completed, e1.phase)
        assertEquals(PerformancePhase.Completed, e2.phase)
        // launch 1/3.6 m/s → 10 m/s at a=5: (10 - 1/3.6)/5
        val launch = 1.0 / 3.6
        assertEquals((10.0 - launch) / 5.0, e1.durationSeconds()!!, 2e-3)
        // 10→20 at a=5 → 2.0 s
        assertEquals(2.0, e2.durationSeconds()!!, 2e-3)
    }
}
