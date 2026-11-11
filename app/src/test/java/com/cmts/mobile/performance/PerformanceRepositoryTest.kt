package com.cmts.mobile.performance

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PerformanceRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sample(seq: Long, t: Long, v: Double) = SpeedSample(
        sourceId = "phone.gps",
        channelKind = "gnss",
        sequence = seq,
        clockDomain = "test",
        sampleTimeNanos = t,
        receivedTimeNanos = t,
        speedMps = v,
        speedAccuracyMps = 0.2,
    )

    @Test
    fun persistAndRecomputeWritesNewVersionWithoutOverwrite() {
        val repo = PerformanceRepository(temp.newFolder("performance"))
        val config = PerformanceConfig(
            mode = PerformanceMode.ACCEL_0_TO_X,
            startSpeedKmh = 0.0,
            endSpeedKmh = 36.0,
            zero = ZeroSpeedConfig(0.5, 1.0, 100, 2),
            quality = QualityConfig(maxSampleGapNs = 2_000_000_000L),
        )
        val handle = repo.createAttempt(config)
        var seq = 1L
        var t = 1_000_000_000L
        repeat(4) {
            repo.appendSample(handle, sample(seq++, t, 0.0))
            t += 50_000_000L
        }
        val n = 41
        for (i in 0 until n) {
            val alpha = i.toDouble() / (n - 1)
            repo.appendSample(handle, sample(seq++, t, 15.0 * alpha))
            t += 50_000_000L
        }
        repo.closeSamples(handle)
        val stored = repo.loadSamples(handle.dir)
        val result = PerformanceEngine().recompute(config, stored)
        assertEquals(PerformancePhase.Completed, result.phase)

        val closedHandle = handle.copy(samplesAlreadyClosed = true)
        val v1 = repo.writeResult(closedHandle, result)
        assertEquals("result-v1.json", v1.name)
        assertTrue(File(handle.dir, "result-v1.json").exists())

        val (ver2, result2) = repo.recompute(handle.dir)
        assertEquals(2, ver2)
        assertTrue(File(handle.dir, "result-v1.json").exists())
        assertTrue(File(handle.dir, "result-v2.json").exists())
        assertEquals(result.durationNs, result2.durationNs)
        assertEquals(result.start!!.alpha, result2.start!!.alpha, 1e-12)
    }

    @Test
    fun configRoundTripPreservesQualityGates() {
        val repo = PerformanceRepository(temp.newFolder("performance"))
        val config = PerformanceConfig(
            mode = PerformanceMode.BRAKE_X_TO_Y,
            startSpeedKmh = 100.0,
            endSpeedKmh = 40.0,
            zero = ZeroSpeedConfig(0.8, 1.5, 1200, 4),
            quality = QualityConfig(
                maxSampleGapNs = 300_000_000L,
                maxSampleAgeNs = 800_000_000L,
                warnSpeedAccuracyMps = 2.0,
                failSpeedAccuracyMps = null,
            ),
            timeoutNs = 90_000_000_000L,
        )
        val handle = repo.createAttempt(config)
        repo.closeSamples(handle)
        val loaded = repo.loadConfig(handle.dir)
        assertEquals(config.mode, loaded.mode)
        assertEquals(config.startSpeedKmh, loaded.startSpeedKmh, 0.0)
        assertEquals(config.endSpeedKmh, loaded.endSpeedKmh, 0.0)
        assertEquals(config.zero.stopSpeedKmh, loaded.zero.stopSpeedKmh, 0.0)
        assertEquals(config.quality.maxSampleGapNs, loaded.quality.maxSampleGapNs)
        assertEquals(config.algorithmVersion, loaded.algorithmVersion)
    }

    @Test
    fun discardAttemptRemovesOnlyThatAttemptDir() {
        val repo = PerformanceRepository(temp.newFolder("perf-discard"))
        val config = PerformanceConfig(PerformanceMode.ACCEL_0_TO_X, 0.0, 36.0)
        val keep = repo.createAttempt(config)
        val drop = repo.createAttempt(config)
        assertTrue(repo.discardAttempt(drop))
        assertFalse(drop.dir.exists())
        assertTrue(keep.dir.exists())
        assertEquals(listOf(keep.id), repo.listAttempts().map { it.id })
    }
}
