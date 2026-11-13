package com.cmts.mobile.performance

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PerformancePersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val config = PerformanceConfig(
        PerformanceMode.ACCEL_0_TO_X, 0.0, 36.0,
        zero = ZeroSpeedConfig(0.5, 1.0, 100, 2),
        quality = QualityConfig(maxSampleGapNs = 2_000_000_000L),
    )
    private fun sample(i: Int, speed: Double) = SpeedSample(
        "phone.gps", "gnss", i + 1L, "test", 1_000_000_000L + i * 50_000_000L,
        1_000_000_000L + i * 50_000_000L, speedMps = speed, speedAccuracyMps = 0.2,
    )

    @Test fun publishesResultOnlyAfterAllOrderedRawSamplesAndTheirHash() {
        val repo = PerformanceRepository(temp.newFolder("performance"))
        val failures = AtomicInteger()
        val saved = CountDownLatch(1)
        val input = (0..44).map { sample(it, if (it < 4) 0.0 else 15.0 * (it - 4) / 40) }
        val expected = PerformanceEngine().recompute(config, input)
        assertEquals(PerformancePhase.Completed, expected.phase)
        val io = PerformancePersistence(repo, { failures.incrementAndGet() })
        val handle = io.create(config)
        input.forEach { io.append(handle, it) }
        io.finish(handle, expected) {
            // Notification is only issued after the atomic result-file publication.
            assertTrue(File(handle.dir, "result-v1.json").isFile)
            saved.countDown()
        }
        io.close()
        assertTrue(saved.await(1, TimeUnit.SECONDS))
        assertEquals(0, failures.get())
        assertEquals(input, repo.loadSamples(handle.dir))
        assertEquals(expected.durationNs, repo.loadLatestResult(handle.dir)!!.second.durationNs)
        val csv = File(handle.dir, "speed_samples.csv")
        val sha = MessageDigest.getInstance("SHA-256").digest(csv.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(sha, JSONObject(File(handle.dir, "result-v1.json").readText()).getString("samplesSha256"))
    }

    @Test fun backlogFailureIsExplicitAndFinalizationStillClosesWithoutPublishingResult() {
        val repo = PerformanceRepository(temp.newFolder("full"))
        val worker = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        worker.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val failures = AtomicInteger()
        val io = PerformancePersistence(repo, { failures.incrementAndGet() }, sampleCapacity = 1, worker = worker)
        val handle = io.create(config)
        try {
            io.append(handle, sample(0, 0.0))
            assertThrows(java.io.IOException::class.java) { io.append(handle, sample(1, 0.0)) }
            io.finish(handle, PerformanceEngine().recompute(config, listOf(sample(0, 0.0)))) {
                fail("A failed sample stream must not publish a result")
            }
        } finally { release.countDown(); io.close() }
        assertEquals(1, failures.get())
        assertFalse(File(handle.dir, "result-v1.json").exists())
        assertTrue(repo.discardAttempt(handle.handle.join())) // no writer keeps the files open
    }
}
