package com.cmts.mobile

import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** One ordered writer per recording. A full queue fails the session, never silently drops rows. */
internal class SessionCsvWriter(
    private val output: BufferedWriter,
    private val availableSpace: () -> Long,
    private val onFailure: (Exception) -> Unit,
    private val capacity: Int = 4096,
    private val clock: () -> Long = System::nanoTime,
) {
    private data class Row(val sample: Sample, val sequence: Long, val callbackNanos: Long)
    private val queue = ArrayDeque<Row>()
    private val failure = AtomicReference<Exception?>(null)
    private val stopped = CountDownLatch(1)
    private val lock = Object()
    private var accepting = true
    private var closing = false

    init {
        try {
            output.write("sequence,source,sample_elapsed_ns,callback_elapsed_ns,utc_ms,quality,v1,v2,v3,v4,v5,v6,v7,v8\n")
            output.flush()
        } catch (e: Exception) {
            runCatching { output.close() }
            throw e
        }
        Thread({ drain() }, "cmts-csv-writer").apply { isDaemon = true; start() }
    }

    fun append(sample: Sample, sequence: Long, callbackNanos: Long) = synchronized(lock) {
        failure.get()?.let { throw it }
        check(accepting) { "recording writer is closed" }
        if (queue.size >= capacity) {
            val e = IOException("记录写入队列已满，录制已停止；请检查存储速度")
            accepting = false
            closing = true
            report(e)
            lock.notifyAll()
            throw e
        }
        queue.addLast(Row(sample, sequence, callbackNanos))
        lock.notifyAll()
    }

    /** Called only when recording ends. All accepted rows precede close. */
    fun close() {
        synchronized(lock) {
            accepting = false
            closing = true
            lock.notifyAll()
        }
        stopped.await()
        failure.get()?.let { throw it }
    }

    private fun report(e: Exception) {
        if (failure.compareAndSet(null, e)) onFailure(e)
    }

    private fun drain() {
        var checkedAt = Long.MIN_VALUE
        var remaining = 0L
        var storageFailed = false
        try {
            while (true) {
                val row = synchronized(lock) {
                    while (queue.isEmpty() && !closing) lock.wait()
                    if (queue.isEmpty()) null else queue.removeFirst()
                } ?: break
                // Backlog overflow still drains accepted rows; only failed storage prevents writes.
                if (storageFailed) continue
                try {
                    val line = row.sample.csv(row.sequence, row.callbackNanos)
                    // Conservative UTF-8 budget (including newline), reconciled at least once/sec.
                    val bytes = line.length.toLong() * 3L + 2L
                    val now = clock()
                    if (checkedAt == Long.MIN_VALUE || now - checkedAt >= 1_000_000_000L || remaining - bytes < MIN_SPACE) {
                        remaining = availableSpace()
                        checkedAt = now
                    }
                    if (remaining < MIN_SPACE) throw IOException("剩余空间不足 10 MiB")
                    output.write(line)
                    output.newLine()
                    remaining -= bytes
                    if (row.sequence % 50L == 0L) output.flush()
                } catch (e: Exception) {
                    storageFailed = true
                    report(e)
                }
            }
        } catch (e: Exception) {
            report(e)
        } finally {
            try { output.close() } catch (e: Exception) { report(e) }
            stopped.countDown()
        }
    }

    private companion object { const val MIN_SPACE = 10L * 1024 * 1024 }
}
