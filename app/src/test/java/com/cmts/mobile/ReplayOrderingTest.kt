package com.cmts.mobile

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ReplayOrderingTest {
    private fun session(lines: String, test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("cmts-replay-order").toFile()
        val session = File(root, "session").apply { mkdirs() }
        val scratch = File(root, "scratch").apply { mkdirs() }
        try {
            File(session, "closed.marker").writeText("")
            File(session, "samples.csv").writeText("header\n$lines")
            test(session, scratch)
        } finally { root.deleteRecursively() }
    }

    @Test fun lateGnssProducesOrderedFramesWithoutFutureImu() = session(
        "1,vehicle.accel,1000000000,1000000000,,ok,1,2,3\n" +
        "2,phone.gps,950000000,1010000000,123,reported,39,117,0,10\n" +
        "3,vehicle.accel,900000000,1020000000,,ok,4,5,6\n"
    ) { dir, scratch ->
        val original = File(dir, "samples.csv").readText()
        val data = Replay.read(dir, scratch)
        assertEquals(data.frames.map { it.nanos }.sorted(), data.frames.map { it.nanos })
        val gps = data.frames.first { it.nanos == 950000000L }
        assertEquals(900000000L, gps.samples.getValue("vehicle.accel").nanos)
        data.frames.forEach { frame -> assertTrue(frame.samples.values.all { it.nanos <= frame.nanos }) }
        assertEquals(original, File(dir, "samples.csv").readText())
        assertTrue(scratch.listFiles()!!.isEmpty())
    }

    @Test fun unchangedSamplesAreSharedButEarlierFramesStayImmutable() = session(
        "1,vehicle.accel,0,0,,ok,1,2,3\n" +
        "2,phone.gps,50000000,50000000,123,reported,39,117,0,10\n" +
        "3,vehicle.accel,100000000,100000000,,ok,4,5,6\n"
    ) { dir, scratch ->
        val frames = Replay.read(dir, scratch).frames
        assertSame(frames[0].samples["vehicle.accel"], frames[1].samples["vehicle.accel"])
        assertNull(frames[0].samples["phone.gps"])
        assertEquals(1.0, frames[0].samples.getValue("vehicle.accel").values[0]!!, 0.0)
        assertEquals(4.0, frames.last().samples.getValue("vehicle.accel").values[0]!!, 0.0)
    }

    @Test fun mergeKeepsEqualTimestampsInOriginalOrderAndCleansUpOnFailure() = session(
        (0 until 160).joinToString("\n") { i -> "$i,phone.gps,${(159-i)/3},0,,reported,39,117,0,$i" }
    ) { dir, scratch ->
        val csv = File(dir, "samples.csv")
        val rows = mutableListOf<ReplayRow>()
        orderedReplayRows(csv, scratch, chunkSize = 2) { rows.add(it) }
        assertEquals(160, rows.size)
        assertEquals(rows.sortedWith(compareBy<ReplayRow> { it.nanos }.thenBy { it.order }), rows)
        assertTrue(scratch.listFiles()!!.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            orderedReplayRows(csv, scratch, chunkSize = 2) { error("consumer failed") }
        }
        assertTrue(scratch.listFiles()!!.isEmpty())
    }
}
