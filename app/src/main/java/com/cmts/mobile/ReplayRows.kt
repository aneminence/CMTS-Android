package com.cmts.mobile

import java.io.File
import java.io.BufferedReader
import java.nio.file.Files
import java.util.PriorityQueue

internal data class ReplayRow(val nanos: Long, val order: Long, val source: String, val raw: String)

private fun replayRow(raw: String, order: Long): ReplayRow? {
    val first = raw.indexOf(',')
    val second = raw.indexOf(',', first + 1)
    val third = raw.indexOf(',', second + 1)
    if (first < 0 || second < 0 || third < 0) return null
    val source = raw.substring(first + 1, second)
    if (source !in REPLAY_SOURCES) return null
    val time = raw.substring(second + 1, third).toLongOrNull()?.takeIf { it >= 0 } ?: return null
    return ReplayRow(time, order, source, raw)
}

private fun forReplayRows(csv: File, visit: (ReplayRow) -> Unit) {
    csv.bufferedReader().use { reader ->
        reader.readLine()
        var order = 0L
        while (true) {
            val line = reader.readLine() ?: break
            replayRow(line, order++)?.let(visit)
        }
    }
}

/** Stable external ordering only when callbacks arrived out of measurement-time order.
 * Bounds both sort memory and open files; original CSV is never rewritten.
 */
internal fun orderedReplayRows(
    csv: File,
    temporaryDirectory: File,
    chunkSize: Int = 32_768,
    visit: (ReplayRow) -> Unit,
) {
    require(chunkSize > 0)
    var previous = -1L
    var ordered = true
    forReplayRows(csv) { row ->
        if (row.nanos < previous) ordered = false
        previous = row.nanos
    }
    if (ordered) {
        forReplayRows(csv, visit)
        return
    }
    val scratch = Files.createTempDirectory(temporaryDirectory.toPath(), "cmts-replay-").toFile()
    val comparator = compareBy<ReplayRow> { it.nanos }.thenBy { it.order }
    try {
        var runs = mutableListOf<File>()
        val chunk = ArrayList<ReplayRow>(chunkSize)
        fun writeChunk() {
            if (chunk.isEmpty()) return
            chunk.sortWith(comparator)
            val file = File.createTempFile("run-", ".csv", scratch)
            file.bufferedWriter().use { out ->
                for (row in chunk) { out.write("${row.order}\t${row.raw}"); out.newLine() }
            }
            runs.add(file)
            chunk.clear()
        }
        forReplayRows(csv) { row ->
            chunk.add(row)
            if (chunk.size >= chunkSize) writeChunk()
        }
        writeChunk()
        // Bound file descriptors even for very large, heavily interleaved recordings.
        while (runs.size > 32) {
            val next = mutableListOf<File>()
            for (group in runs.chunked(32)) {
                val file = File.createTempFile("merge-", ".csv", scratch)
                file.bufferedWriter().use { out ->
                    mergeReplayRuns(group, comparator) { row -> out.write("${row.order}\t${row.raw}"); out.newLine() }
                }
                next.add(file)
                group.forEach { it.delete() }
            }
            runs = next
        }
        mergeReplayRuns(runs, comparator, visit)
    } finally {
        // Only files created in this unique scratch directory are removed.
        scratch.listFiles()?.forEach { it.delete() }
        scratch.delete()
    }
}

private fun mergeReplayRuns(files: List<File>, order: Comparator<ReplayRow>, visit: (ReplayRow) -> Unit) {
    data class Head(val row: ReplayRow, val reader: BufferedReader)
    val readers = mutableListOf<BufferedReader>()
    val queue = PriorityQueue<Head> { a, b -> order.compare(a.row, b.row) }
    fun advance(reader: BufferedReader) {
        val line = reader.readLine() ?: return
        val tab = line.indexOf('\t')
        val row = requireNotNull(replayRow(line.substring(tab + 1), line.substring(0, tab).toLong()))
        queue.add(Head(row, reader))
    }
    try {
        files.forEach { file -> file.bufferedReader().also { readers.add(it); advance(it) } }
        while (queue.isNotEmpty()) {
            val head = queue.remove()
            visit(head.row)
            advance(head.reader)
        }
    } finally {
        readers.forEach { it.close() }
    }
}
