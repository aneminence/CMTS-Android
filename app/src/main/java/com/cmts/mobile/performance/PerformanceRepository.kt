package com.cmts.mobile.performance

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.security.MessageDigest

/**
 * Persistence for performance attempts. Recalc writes result-vN.json and never overwrites v1.
 * Speed samples are raw GNSS observations — not Replay snapshots.
 */
class PerformanceRepository(private val root: File) {
    init { root.mkdirs() }

    fun attemptsDir(): File = File(root, "attempts").also { it.mkdirs() }

    fun listAttempts(): List<AttemptSummary> {
        val dir = attemptsDir()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { summarize(it) }
            ?: emptyList()
    }

    fun createAttempt(config: PerformanceConfig): AttemptHandle {
        val id = java.util.UUID.randomUUID().toString()
        val dir = File(attemptsDir(), id).also { it.mkdirs() }
        File(dir, "config.json").writeText(configToJson(config).toString(2))
        val samples = File(dir, "speed_samples.csv")
        val writer = samples.bufferedWriter().also {
            it.write(SPEED_CSV_HEADER)
            it.newLine()
            it.flush()
        }
        return AttemptHandle(id = id, dir = dir, config = config, sampleWriter = writer, samplesFile = samples)
    }

    fun appendSample(handle: AttemptHandle, sample: SpeedSample) {
        val line = listOf(
            sample.sequence,
            sample.sourceId,
            sample.channelKind,
            sample.clockDomain,
            sample.sampleTimeNanos,
            sample.receivedTimeNanos,
            sample.utcMillis ?: "",
            sample.speedMps ?: "",
            sample.speedAccuracyMps ?: "",
            sample.valid,
            sample.mock,
            sample.rawReference ?: "",
        ).joinToString(",")
        handle.sampleWriter.write(line)
        handle.sampleWriter.newLine()
        if (sample.sequence % 20L == 0L) handle.sampleWriter.flush()
    }

    fun closeSamples(handle: AttemptHandle) {
        if (handle.samplesAlreadyClosed) return
        try { handle.sampleWriter.flush(); handle.sampleWriter.close() } catch (_: Exception) {}
    }

    fun writeResult(handle: AttemptHandle, result: PerformanceResult): File {
        closeSamples(handle)
        val next = nextResultVersion(handle.dir)
        val file = File(handle.dir, "result-v$next.json")
        val tmp = File(handle.dir, "result-v$next.json.tmp")
        val hash = sha256(handle.samplesFile)
        tmp.writeText(resultToJson(result, handle, hash, next).toString(2))
        check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) { "failed to publish ${file.name}" }
        return file
    }

    fun loadConfig(attemptDir: File): PerformanceConfig =
        configFromJson(JSONObject(File(attemptDir, "config.json").readText()))

    fun loadSamples(attemptDir: File): List<SpeedSample> {
        val file = File(attemptDir, "speed_samples.csv")
        if (!file.exists()) return emptyList()
        return file.bufferedReader().useLines { lines -> lines.drop(1).mapNotNull { parseSampleLine(it) }.toList() }
    }

    fun loadLatestResult(attemptDir: File): Pair<Int, PerformanceResult>? {
        val versions = attemptDir.listFiles()
            ?.mapNotNull { f ->
                val m = Regex("""result-v(\d+)\.json""").matchEntire(f.name) ?: return@mapNotNull null
                m.groupValues[1].toInt() to f
            }
            ?.sortedBy { it.first }
            ?: return null
        val (ver, file) = versions.lastOrNull() ?: return null
        return ver to resultFromJson(JSONObject(file.readText()))
    }

    fun recompute(attemptDir: File, engine: PerformanceEngine = PerformanceEngine()): Pair<Int, PerformanceResult> {
        val config = loadConfig(attemptDir)
        val samples = loadSamples(attemptDir)
        val result = engine.recompute(config, samples)
        val next = nextResultVersion(attemptDir)
        val handle = AttemptHandle(
            id = attemptDir.name,
            dir = attemptDir,
            config = config,
            sampleWriter = nullWriter(),
            samplesFile = File(attemptDir, "speed_samples.csv"),
            samplesAlreadyClosed = true,
        )
        val file = File(attemptDir, "result-v$next.json")
        val tmp = File(attemptDir, "result-v$next.json.tmp")
        tmp.writeText(resultToJson(result, handle, sha256(handle.samplesFile), next).toString(2))
        check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file)))
        return next to result
    }

    private fun summarize(dir: File): AttemptSummary? {
        if (!File(dir, "config.json").exists()) return null
        val config = runCatching { loadConfig(dir) }.getOrNull() ?: return null
        val latest = runCatching { loadLatestResult(dir) }.getOrNull()
        return AttemptSummary(
            id = dir.name,
            dir = dir,
            modifiedMs = dir.lastModified(),
            mode = config.mode,
            startSpeedKmh = config.startSpeedKmh,
            endSpeedKmh = config.endSpeedKmh,
            latestVersion = latest?.first,
            latestPhase = latest?.second?.phase,
            latestQuality = latest?.second?.quality,
            latestDurationNs = latest?.second?.durationNs,
            latestDistanceMeters = latest?.second?.distanceMeters,
        )
    }

    private fun nextResultVersion(dir: File): Int {
        val max = dir.listFiles()
            ?.mapNotNull { Regex("""result-v(\d+)\.json""").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            ?.maxOrNull()
            ?: 0
        return max + 1
    }

    companion object {
        const val SPEED_CSV_HEADER =
            "sequence,sourceId,channelKind,clockDomain,sampleTimeNanos,receivedTimeNanos,utcMillis,speedMps,speedAccuracyMps,valid,mock,rawReference"

        private fun nullWriter(): BufferedWriter = object : BufferedWriter(java.io.StringWriter()) {
            override fun write(cbuf: CharArray, off: Int, len: Int) {}
            override fun write(str: String, off: Int, len: Int) {}
            override fun newLine() {}
            override fun flush() {}
            override fun close() {}
        }

        fun configToJson(config: PerformanceConfig): JSONObject = JSONObject()
            .put("schema", 1)
            .put("algorithmVersion", config.algorithmVersion)
            .put("mode", config.mode.name)
            .put("startSpeedKmh", config.startSpeedKmh)
            .put("endSpeedKmh", config.endSpeedKmh)
            .put("timeoutNs", config.timeoutNs)
            .put("expectedSourceId", config.expectedSourceId)
            .put("zero", JSONObject()
                .put("stopSpeedKmh", config.zero.stopSpeedKmh)
                .put("launchSpeedKmh", config.zero.launchSpeedKmh)
                .put("stationaryHoldMs", config.zero.stationaryHoldMs)
                .put("minStationarySamples", config.zero.minStationarySamples))
            .put("quality", JSONObject()
                .put("maxSampleGapNs", config.quality.maxSampleGapNs)
                .put("maxSampleAgeNs", config.quality.maxSampleAgeNs)
                .put("warnSpeedAccuracyMps", config.quality.warnSpeedAccuracyMps)
                .put("failSpeedAccuracyMps", config.quality.failSpeedAccuracyMps)
                .put("rejectMock", config.quality.rejectMock)
                .put("rejectNegativeSpeed", config.quality.rejectNegativeSpeed))

        fun configFromJson(o: JSONObject): PerformanceConfig {
            val zero = o.getJSONObject("zero")
            val quality = o.getJSONObject("quality")
            return PerformanceConfig(
                mode = PerformanceMode.valueOf(o.getString("mode")),
                startSpeedKmh = o.getDouble("startSpeedKmh"),
                endSpeedKmh = o.getDouble("endSpeedKmh"),
                zero = ZeroSpeedConfig(
                    stopSpeedKmh = zero.getDouble("stopSpeedKmh"),
                    launchSpeedKmh = zero.getDouble("launchSpeedKmh"),
                    stationaryHoldMs = zero.getLong("stationaryHoldMs"),
                    minStationarySamples = zero.getInt("minStationarySamples"),
                ),
                quality = QualityConfig(
                    maxSampleGapNs = quality.getLong("maxSampleGapNs"),
                    maxSampleAgeNs = quality.getLong("maxSampleAgeNs"),
                    warnSpeedAccuracyMps = if (quality.isNull("warnSpeedAccuracyMps")) null else quality.getDouble("warnSpeedAccuracyMps"),
                    failSpeedAccuracyMps = if (quality.isNull("failSpeedAccuracyMps")) null else quality.getDouble("failSpeedAccuracyMps"),
                    rejectMock = quality.getBoolean("rejectMock"),
                    rejectNegativeSpeed = quality.getBoolean("rejectNegativeSpeed"),
                ),
                timeoutNs = o.getLong("timeoutNs"),
                expectedSourceId = o.getString("expectedSourceId"),
                algorithmVersion = o.optString("algorithmVersion", PERFORMANCE_ENGINE_VERSION),
            )
        }

        fun resultToJson(result: PerformanceResult, handle: AttemptHandle, samplesSha256: String, version: Int): JSONObject {
            fun cross(c: CrossingPoint?) = if (c == null) JSONObject.NULL else JSONObject()
                .put("timeNanos", c.timeNanos)
                .put("speedMps", c.speedMps)
                .put("beforeSequence", c.beforeSequence)
                .put("afterSequence", c.afterSequence)
                .put("alpha", c.alpha)
            return JSONObject()
                .put("schema", 1)
                .put("resultVersion", version)
                .put("attemptId", handle.id)
                .put("algorithmVersion", result.algorithmVersion)
                .put("phase", result.phase.name)
                .put("quality", result.quality.name)
                .put("reasons", JSONArray(result.reasons.map { it.name }))
                .put("durationNs", result.durationNs)
                .put("distanceMeters", result.distanceMeters)
                .put("zeroDefinition", result.zeroDefinition)
                .put("start", cross(result.start))
                .put("end", cross(result.end))
                .put("config", configToJson(result.config))
                .put("stats", JSONObject()
                    .put("acceptedSamples", result.stats.acceptedSamples)
                    .put("rejectedSamples", result.stats.rejectedSamples)
                    .put("maxGapNs", result.stats.maxGapNs)
                    .put("measuredMeanHz", result.stats.measuredMeanHz)
                    .put("speedAccuracyUnknown", result.stats.speedAccuracyUnknown))
                .put("samplesFile", handle.samplesFile.name)
                .put("samplesSha256", samplesSha256)
                .put("note", "Interpolation refines threshold crossings only; it does not create GNSS precision.")
        }

        fun resultFromJson(o: JSONObject): PerformanceResult {
            fun cross(key: String): CrossingPoint? {
                if (o.isNull(key)) return null
                val c = o.getJSONObject(key)
                return CrossingPoint(
                    timeNanos = c.getLong("timeNanos"),
                    speedMps = c.getDouble("speedMps"),
                    beforeSequence = c.getLong("beforeSequence"),
                    afterSequence = c.getLong("afterSequence"),
                    alpha = c.getDouble("alpha"),
                )
            }
            val reasons = mutableListOf<QualityReason>()
            val arr = o.getJSONArray("reasons")
            for (i in 0 until arr.length()) reasons.add(QualityReason.valueOf(arr.getString(i)))
            val stats = o.getJSONObject("stats")
            return PerformanceResult(
                config = configFromJson(o.getJSONObject("config")),
                phase = PerformancePhase.valueOf(o.getString("phase")),
                quality = QualityTier.valueOf(o.getString("quality")),
                reasons = reasons,
                start = cross("start"),
                end = cross("end"),
                durationNs = if (o.isNull("durationNs")) null else o.getLong("durationNs"),
                distanceMeters = if (o.isNull("distanceMeters")) null else o.getDouble("distanceMeters"),
                stats = AttemptStats(
                    acceptedSamples = stats.getInt("acceptedSamples"),
                    rejectedSamples = stats.getInt("rejectedSamples"),
                    maxGapNs = stats.getLong("maxGapNs"),
                    measuredMeanHz = if (stats.isNull("measuredMeanHz")) null else stats.getDouble("measuredMeanHz"),
                    speedAccuracyUnknown = stats.getBoolean("speedAccuracyUnknown"),
                    reasons = reasons,
                ),
                algorithmVersion = o.getString("algorithmVersion"),
                zeroDefinition = o.getString("zeroDefinition"),
            )
        }

        private fun parseSampleLine(line: String): SpeedSample? {
            if (line.isBlank()) return null
            val p = line.split(',')
            if (p.size < 11) return null
            fun optDouble(s: String) = s.takeIf { it.isNotBlank() }?.toDoubleOrNull()
            fun optLong(s: String) = s.takeIf { it.isNotBlank() }?.toLongOrNull()
            return SpeedSample(
                sourceId = p[1],
                channelKind = p[2],
                sequence = p[0].toLong(),
                clockDomain = p[3],
                sampleTimeNanos = p[4].toLong(),
                receivedTimeNanos = p[5].toLong(),
                utcMillis = optLong(p[6]),
                speedMps = optDouble(p[7]),
                speedAccuracyMps = optDouble(p[8]),
                valid = p[9].toBooleanStrict(),
                mock = p[10].toBooleanStrict(),
                rawReference = p.getOrNull(11)?.takeIf { it.isNotBlank() },
            )
        }

        private fun sha256(file: File): String {
            if (!file.exists()) return ""
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

data class AttemptHandle(
    val id: String,
    val dir: File,
    val config: PerformanceConfig,
    val sampleWriter: BufferedWriter,
    val samplesFile: File,
    val samplesAlreadyClosed: Boolean = false,
)

data class AttemptSummary(
    val id: String,
    val dir: File,
    val modifiedMs: Long,
    val mode: PerformanceMode,
    val startSpeedKmh: Double,
    val endSpeedKmh: Double,
    val latestVersion: Int?,
    val latestPhase: PerformancePhase?,
    val latestQuality: QualityTier?,
    val latestDurationNs: Long?,
    val latestDistanceMeters: Double?,
)
