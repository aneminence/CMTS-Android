package com.cmts.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class TripMetricsTest {
    private fun fix(tSec: Double, kmh: Double?) = TripMetrics.SpeedFix(
        timeNs = (tSec * 1e9).toLong(),
        speedMps = kmh?.div(3.6),
    )

    @Test
    fun integratesSpeedTimesDt_notPolyline() {
        // Two 2 s intervals at 36 km/h (10 m/s), then two 2 s intervals at 72 km/h (20 m/s).
        val snap = TripMetrics.integrate(
            listOf(fix(0.0, 36.0), fix(2.0, 36.0), fix(4.0, 36.0), fix(6.0, 72.0), fix(8.0, 72.0), fix(10.0, 72.0)),
            recordingDurationNs = 10_000_000_000L,
        )
        assertEquals(10_000_000_000L, snap.recordingDurationNs)
        assertEquals(10_000_000_000L, snap.movingDurationNs)
        // 2*10 + 2*10 + 2*15 + 2*20 + 2*20 = 20+20+30+40+40 = 150 m (trapezoid).
        assertEquals(150.0, snap.distanceMeters, 1e-6)
    }

    @Test
    fun ignoresStoppedIntervalsBelowThreshold() {
        val moving = TripMetrics.MOVING_SPEED_THRESHOLD_KMH
        val snap = TripMetrics.integrate(
            listOf(
                fix(0.0, 36.0),
                fix(2.0, 36.0), // 20 m
                fix(4.0, 0.0), // slowing: mean 5 m/s, counted
                fix(8.0, 0.0), // 4 s parked at 0, ignored
                fix(10.0, 36.0), // launching: mean 5 m/s, counted
            ),
            recordingDurationNs = 10_000_000_000L,
        )
        assertEquals(10_000_000_000L, snap.recordingDurationNs)
        // parked 4 s not in moving time
        assertEquals(6_000_000_000L, snap.movingDurationNs)
        assertEquals(40.0, snap.distanceMeters, 1e-6)
        val onlyStop = TripMetrics.integrate(listOf(fix(0.0, 0.0), fix(2.0, 0.0), fix(4.0, moving / 2)), 4_000_000_000L)
        assertEquals(0L, onlyStop.movingDurationNs)
        assertEquals(0.0, onlyStop.distanceMeters, 1e-9)
    }

    @Test
    fun skipsGapsWiderThanMaxInterval() {
        val snap = TripMetrics.integrate(
            listOf(fix(0.0, 36.0), fix(2.0, 36.0), fix(2.0 + 6.0, 36.0), fix(4.0 + 6.0, 36.0)),
            recordingDurationNs = 10_000_000_000L,
        )
        // Two 2 s moving intervals; the 6 s gap (> 5 s) is skipped.
        assertEquals(4_000_000_000L, snap.movingDurationNs)
        assertEquals(40.0, snap.distanceMeters, 1e-6)
    }

    @Test
    fun accumulatorMatchesBatchIntegrate() {
        val fixes = listOf(fix(0.0, 36.0), fix(5.0, 36.0), fix(10.0, 0.0), fix(20.0, 0.0), fix(25.0, 36.0), fix(30.0, 36.0))
        val batch = TripMetrics.integrate(fixes, 30_000_000_000L)
        val acc = TripMetrics.Accumulator()
        fixes.forEach { acc.onFix(it.timeNs, it.speedMps) }
        val live = acc.snapshot(30_000_000_000L)
        assertEquals(batch.movingDurationNs, live.movingDurationNs)
        assertEquals(batch.distanceMeters, live.distanceMeters, 1e-9)
        acc.reset()
        assertEquals(0L, acc.snapshot(0L).movingDurationNs)
        assertEquals(0.0, acc.snapshot(0L).distanceMeters, 0.0)
    }

    @Test
    fun catalogComputesOnceAndReadsCache() {
        val dir = Files.createTempDirectory("trip-session").toFile()
        try {
            File(dir, "closed.marker").writeText("1000000")
            File(dir, "metadata.json").writeText(JSONObject().put("startedUtcMs", 1_000L).put("endedUtcMs", 31_000L).toString())
            // 20 samples, 1 s apart, 10 m/s → 19 s * 10 m = 190 m
            val body = (0..19).joinToString("\n") { i ->
                val ns = i * 1_000_000_000L
                val utc = 1_000L + i * 1_000L
                "$i,phone.gps,$ns,$ns,$utc,ok,39.0,117.0,0,10.0,0,1,1,0.1"
            }
            File(dir, "samples.csv").writeText("sequence,source,sample_elapsed_ns,callback_elapsed_ns,utc_ms,quality,v1,v2,v3,v4,v5,v6,v7,v8\n$body\n")
            val first = SessionCatalog.ensureMetrics(dir)
            assertEquals(30_000_000_000L, first.recordingDurationNs)
            assertEquals(19_000_000_000L, first.movingDurationNs)
            assertEquals(190.0, first.distanceMeters!!, 1e-6)
            assertTrue(first.displayName.isNotBlank())

            // Corrupt the CSV; cache must still return the first result.
            File(dir, "samples.csv").writeText("header\n")
            val cached = SessionCatalog.ensureMetrics(dir)
            assertEquals(first.recordingDurationNs, cached.recordingDurationNs)
            assertEquals(first.movingDurationNs, cached.movingDurationNs)
            assertEquals(first.distanceMeters!!, cached.distanceMeters!!, 0.0)

            SessionCatalog.rename(dir, "试车-东丽")
            assertEquals("试车-东丽", SessionCatalog.exportBaseName(dir))
            assertEquals("试车-东丽", SessionCatalog.readRecord(dir).displayName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyTripIsNotSaved() {
        assertTrue(TripMetrics.isEmptyTrip(0.0))
        assertTrue(TripMetrics.isEmptyTrip(Double.NaN))
        assertTrue(TripMetrics.isEmptyTrip(-0.0))
        assertFalse(TripMetrics.isEmptyTrip(0.01))
    }

    @Test
    fun sanitizeStripsPathCharacters() {
        assertEquals("a_b_c", SessionCatalog.sanitizeFileName("a/b\\c"))
        assertEquals("cmts-session", SessionCatalog.sanitizeFileName("   "))
    }
}
