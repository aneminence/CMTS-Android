package com.cmts.mobile

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ReplayRenderingDataTest {
    @Test fun prefixIncludesDuplicateTimestampsWithoutCopyingPoints() {
        val track = listOf(1L, 2L, 2L, 4L).map { TrackPoint(39.0, 117.0, it) }
        assertEquals(0, trackUpperBound(track, 0))
        assertEquals(3, trackUpperBound(track, 2))
        assertEquals(4, trackUpperBound(track, 9))
        val prefix = TrackPrefix(track, 3)
        assertSame(track, prefix.source)
        assertEquals(track.take(3), prefix)
        assertThrows(IndexOutOfBoundsException::class.java) { prefix[3] }
    }

    @Test fun seekingReusesCoordinatesAndOldViewsSurviveAppending() {
        val track = (0L..20L).map { TrackPoint(39.0, 117.0, it) }
        var conversions = 0
        val cache = TrackCoordinateCache { point -> conversions++; point.nanos }
        val old = cache.get(TrackPrefix(track, 2))
        assertEquals(2, conversions)
        assertEquals(listOf(0L, 1L, 2L, 3L), cache.get(TrackPrefix(track, 4)))
        assertEquals(4, conversions)
        assertEquals(listOf(0L, 1L), old)
        assertEquals(listOf(0L), cache.get(TrackPrefix(track, 1)))
        assertEquals(4, conversions)
        cache.get(track.take(4).drop(1) + track[4])
        assertEquals(5, conversions)
    }

    @Test fun exactWindowBoundsMatchFullScanWithMissingValuesAndBlockEdges() {
        val random = Random(42)
        val values = DoubleArray(4097) { if (it % 5 == 0) Double.NaN else random.nextDouble(-100.0, 100.0) }
        val series = ReplayCurveSeries(values)
        repeat(300) {
            val first = random.nextInt(values.size)
            val last = random.nextInt(first, values.size)
            val expected = values.sliceArray(first..last).filter { it.isFinite() }
            assertEquals((expected.minOrNull() ?: 0.0) to (expected.maxOrNull() ?: 0.0), series.bounds(first, last))
        }
        assertNull(series[0])
        assertEquals(0.0 to 0.0, ReplayCurveSeries(DoubleArray(512) { Double.NaN }).bounds(0, 511))
    }
}
