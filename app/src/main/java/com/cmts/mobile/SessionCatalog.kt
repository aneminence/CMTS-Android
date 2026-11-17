package com.cmts.mobile

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionRecord(
    val dir: File,
    val displayName: String,
    val recordingDurationNs: Long?,
    val movingDurationNs: Long?,
    val distanceMeters: Double?,
    val closed: Boolean,
    val lastModified: Long,
)

/**
 * Session list metadata. Trip metrics are computed once (at recording stop, or on first
 * scan of a legacy file) and written to metadata.json. The records page only reads the cache.
 */
internal object SessionCatalog {
    private const val META = "metadata.json"
    private const val CSV = "samples.csv"
    private const val CLOSED = "closed.marker"

    fun list(sessionsRoot: File): List<SessionRecord> {
        return sessionsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.map { session -> runCatching { readRecord(session) }.getOrElse { toRecord(session, null) } }
            ?: emptyList()
    }

    fun needsMetrics(record: SessionRecord): Boolean =
        record.closed && (record.recordingDurationNs == null || record.movingDurationNs == null || record.distanceMeters == null)

    fun listAndEnsure(sessionsRoot: File): List<SessionRecord> {
        return list(sessionsRoot).map { record ->
            if (needsMetrics(record)) runCatching { ensureMetrics(record.dir) }.getOrDefault(record) else record
        }
    }

    fun rename(session: File, rawName: String) {
        val name = displayNameOrNull(rawName) ?: throw IllegalArgumentException("名称不能为空")
        val meta = readMeta(session)
        meta.put("displayName", name)
        writeMeta(session, meta)
    }

    fun exportBaseName(session: File): String = sanitizeFileName(readRecord(session).displayName)

    fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ")
        return cleaned.take(80).ifBlank { "cmts-session" }
    }

    fun ensureMetrics(session: File): SessionRecord {
        val meta = runCatching { readMeta(session) }.getOrElse { JSONObject() }
        if (hasCachedMetrics(meta)) {
            if (displayNameOrNull(meta.optString("displayName")) == null) {
                meta.put("displayName", defaultDisplayName(session, meta))
                writeMeta(session, meta)
            }
            return toRecord(session, meta)
        }
        val snapshot = computeFromCsv(session, meta)
        meta.put("recordingDurationNs", snapshot.recordingDurationNs)
        meta.put("movingDurationNs", snapshot.movingDurationNs)
        meta.put("distanceMeters", snapshot.distanceMeters)
        meta.put("tripMetricsVersion", TripMetrics.VERSION)
        meta.put("movingSpeedThresholdKmh", TripMetrics.MOVING_SPEED_THRESHOLD_KMH)
        if (displayNameOrNull(meta.optString("displayName")) == null) {
            meta.put("displayName", defaultDisplayName(session, meta))
        }
        writeMeta(session, meta)
        return toRecord(session, meta)
    }

    fun readRecord(session: File): SessionRecord = toRecord(session, runCatching { readMeta(session) }.getOrNull())

    internal fun computeFromCsv(session: File, meta: JSONObject): TripMetrics.Snapshot {
        val fixes = ArrayList<TripMetrics.SpeedFix>()
        val csv = File(session, CSV)
        var firstUtc: Long? = null
        var lastUtc: Long? = null
        var firstNs: Long? = null
        var lastNs: Long? = null
        if (csv.isFile) {
            csv.bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val c = line.split(',')
                    val source = c.getOrNull(1) ?: return@forEach
                    val nanos = c.getOrNull(2)?.toLongOrNull() ?: return@forEach
                    val utc = c.getOrNull(4)?.toLongOrNull()
                    if (firstNs == null) firstNs = nanos
                    lastNs = nanos
                    if (utc != null) {
                        if (firstUtc == null) firstUtc = utc
                        lastUtc = utc
                    }
                    if (source != "phone.gps") return@forEach
                    val speed = c.getOrNull(9)?.toDoubleOrNull()?.takeIf { it.isFinite() }
                    fixes += TripMetrics.SpeedFix(nanos, speed)
                }
            }
        }
        val recordingNs = recordingDurationNs(session, meta, firstUtc, lastUtc, firstNs, lastNs)
        return TripMetrics.integrate(fixes, recordingNs)
    }

    private fun recordingDurationNs(
        session: File,
        meta: JSONObject,
        firstUtc: Long?,
        lastUtc: Long?,
        firstNs: Long?,
        lastNs: Long?,
    ): Long {
        val started = meta.optLong("startedUtcMs", -1L).takeIf { it > 0 }
        val ended = meta.optLong("endedUtcMs", -1L).takeIf { it > 0 }
            ?: File(session, CLOSED).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        if (started != null && ended != null && ended >= started) return (ended - started) * 1_000_000L
        if (firstUtc != null && lastUtc != null && lastUtc >= firstUtc) return (lastUtc - firstUtc) * 1_000_000L
        if (firstNs != null && lastNs != null && lastNs >= firstNs) return lastNs - firstNs
        return 0L
    }

    private fun hasCachedMetrics(meta: JSONObject): Boolean =
        meta.has("recordingDurationNs") && meta.has("movingDurationNs") && meta.has("distanceMeters")

    private fun toRecord(session: File, meta: JSONObject?): SessionRecord {
        val closed = File(session, CLOSED).exists()
        return SessionRecord(
            dir = session,
            displayName = displayNameOrNull(meta?.optString("displayName")) ?: defaultDisplayName(session, meta),
            recordingDurationNs = meta?.optLong("recordingDurationNs", -1L)?.takeIf { it >= 0 && meta.has("recordingDurationNs") },
            movingDurationNs = meta?.optLong("movingDurationNs", -1L)?.takeIf { it >= 0 && meta.has("movingDurationNs") },
            distanceMeters = if (meta != null && meta.has("distanceMeters")) meta.optDouble("distanceMeters") else null,
            closed = closed,
            lastModified = session.lastModified(),
        )
    }

    private fun defaultDisplayName(session: File, meta: JSONObject?): String {
        val started = meta?.optLong("startedUtcMs", -1L)?.takeIf { it > 0 } ?: session.lastModified()
        return SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(Date(started))
    }

    private fun displayNameOrNull(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    private fun readMeta(session: File): JSONObject {
        val file = File(session, META)
        if (!file.isFile) return JSONObject()
        return JSONObject(file.readText())
    }

    private fun writeMeta(session: File, meta: JSONObject) {
        File(session, META).writeText(meta.toString(2))
    }
}
