package com.cmts.mobile.performance

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * Offline 0→50 on the vbox.vbo speed series, using the VBOX cut:
 * leave 0.5 km/h, do not fall back to 0.5, then integrate to 50.
 */
class VboxAccelAlignmentTest {

    @Test
    fun vboxVbo_sevenAccel0to50_matchVboxTimesAndDistances() {
        val samples = loadSpeedSeries()
        val cfg = PerformanceConfig(
            mode = PerformanceMode.ACCEL_0_TO_X,
            startSpeedKmh = 0.0,
            endSpeedKmh = 50.0,
            zero = ZeroSpeedConfig(),
            quality = QualityConfig(
                maxSampleGapNs = 2_500_000_000L,
                warnSpeedAccuracyMps = null,
                failSpeedAccuracyMps = null,
            ),
            timeoutNs = 600_000_000_000L,
            expectedSourceId = "vbo",
        )
        val results = collectCompletedRuns(cfg, samples)
        val expected = listOf(
            18.54 to 114.76,
            3.93 to 24.49,
            31.95 to 277.39,
            5.15 to 30.28,
            36.53 to 350.28,
            4.91 to 27.02,
            7.74 to 51.96,
        )
        assertEquals("run count", expected.size, results.size)
        expected.zip(results).forEachIndexed { i, (exp, got) ->
            assertEquals(PerformancePhase.Completed, got.phase)
            val dt = requireNotNull(got.durationSeconds())
            val dist = requireNotNull(got.distanceMeters)
            assertEquals("run ${i + 1} time s", exp.first, dt, 0.015)
            assertEquals("run ${i + 1} distance m", exp.second, dist, 0.05)
        }
    }

    private fun collectCompletedRuns(
        cfg: PerformanceConfig,
        samples: List<SpeedSample>,
    ): List<PerformanceResult> {
        val engine = PerformanceEngine()
        engine.onEvent(EngineEvent.Arm(cfg))
        val out = ArrayList<PerformanceResult>(8)
        for (s in samples) {
            val snap = engine.onEvent(EngineEvent.Sample(s))
            if (snap.phase == PerformancePhase.Completed && snap.result != null) {
                out += snap.result!!
                engine.onEvent(EngineEvent.Arm(cfg))
                engine.onEvent(EngineEvent.Sample(s))
            } else if (snap.phase == PerformancePhase.Invalid) {
                engine.onEvent(EngineEvent.Arm(cfg))
                engine.onEvent(EngineEvent.Sample(s))
            }
        }
        return out
    }

    private fun loadSpeedSeries(): List<SpeedSample> {
        val raw = javaClass.getResourceAsStream("/com/cmts/mobile/performance/vbox_0to50_speed.csv.gz")
            ?: error("missing vbox_0to50_speed.csv.gz")
        val lines = GZIPInputStream(raw).bufferedReader().use { it.readLines() }
        val out = ArrayList<SpeedSample>(lines.size)
        var seq = 0L
        for (line in lines) {
            if (line.isBlank() || line.startsWith("elapsed_s")) continue
            val parts = line.split(',')
            val elapsed = parts[0].toDouble()
            val kmh = parts[1].toDouble()
            val tNs = (elapsed * 1_000_000_000.0).toLong()
            seq++
            out += SpeedSample(
                sourceId = "vbo",
                channelKind = "gnss",
                sequence = seq,
                clockDomain = "vbo",
                sampleTimeNanos = tNs,
                receivedTimeNanos = tNs,
                speedMps = kmh / 3.6,
                speedAccuracyMps = 0.01,
            )
        }
        return out
    }
}
