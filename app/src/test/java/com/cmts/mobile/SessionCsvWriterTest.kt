package com.cmts.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedWriter
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionCsvWriterTest {
    private class Output : BufferedWriter(StringWriter()) {
        val rows = mutableListOf<String>()
        var flushes = 0
        var closed = false
        override fun write(str: String) { rows += str }
        override fun newLine() {}
        override fun flush() { flushes++ }
        override fun close() { closed = true }
    }
    private fun sample(i: Int) = Sample("vehicle.accel", i.toLong(), null, listOf(i.toDouble(), null, 3.0), "ok")

    @Test fun preservesEveryRowTimestampAndFiftyRowFlushWindow() {
        val output = Output()
        val checks = AtomicInteger()
        val writer = SessionCsvWriter(output, { checks.incrementAndGet(); 100_000_000 }, { throw AssertionError(it) }, clock = { 1L })
        repeat(125) { writer.append(sample(it), it + 1L, it + 900L) }
        writer.close()
        assertEquals(126, output.rows.size)
        repeat(125) { assertEquals(sample(it).csv(it + 1L, it + 900L), output.rows[it + 1]) }
        assertEquals(3, output.flushes) // header, row 50, row 100
        assertEquals(1, checks.get())
        assertTrue(output.closed)
    }

    @Test fun fullQueueReportsFailureAndStillClosesOutput() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = Output()
        val failures = AtomicInteger()
        val writer = SessionCsvWriter(output, {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); 100_000_000
        }, { failures.incrementAndGet() }, capacity = 1)
        try {
            writer.append(sample(1), 1, 100)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            writer.append(sample(2), 2, 200)
            assertThrows(java.io.IOException::class.java) { writer.append(sample(3), 3, 300) }
        } finally {
            release.countDown()
            assertThrows(java.io.IOException::class.java) { writer.close() }
        }
        assertEquals(1, failures.get())
        assertTrue(output.closed)
        assertEquals(listOf(sample(1).csv(1, 100), sample(2).csv(2, 200)), output.rows.drop(1))
    }

    @Test fun lowSpaceFailureDoesNotWriteAFalseCompleteRecording() {
        val output = Output()
        val failures = AtomicInteger()
        val writer = SessionCsvWriter(output, { 10L * 1024 * 1024 - 1 }, { failures.incrementAndGet() })
        writer.append(sample(1), 1, 100)
        assertThrows(java.io.IOException::class.java) { writer.close() }
        assertEquals(1, output.rows.size) // header only
        assertEquals(1, failures.get())
        assertTrue(output.closed)
        assertThrows(java.io.IOException::class.java) { writer.close() }
    }
}
