package com.cmts.mobile.performance

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridges CaptureService raw GNSS to one or more [PerformanceEngine] instances (multi-segment).
 * Same raw samples are fed to every armed segment; each persists its own attempt + result.
 */

/** UI draft for one selectable test segment; persisted across sheet collapse / pager dispose. */
data class SegmentDraft(
    val mode: PerformanceMode,
    val selected: Boolean,
    val startKmh: String,
    val endKmh: String,
)

fun defaultSegmentDrafts(): List<SegmentDraft> = listOf(
    SegmentDraft(PerformanceMode.ACCEL_0_TO_X, true, "0", "60"),
    SegmentDraft(PerformanceMode.ACCEL_X_TO_Y, true, "60", "100"),
    SegmentDraft(PerformanceMode.BRAKE_X_TO_Y, true, "100", "60"),
    SegmentDraft(PerformanceMode.BRAKE_X_TO_0, true, "60", "0"),
)
object PerformanceController {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val sampleSeq = AtomicLong(0)
    private val armed = AtomicBoolean(false)
    /** 250 ms watchdog owned here so VerticalPager dispose cannot stop timeouts. */
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!armed.get()) return
            watchdogTick()
            if (armed.get()) watchdogHandler.postDelayed(this, 250L)
        }
    }

    private data class LiveSegment(
        val config: PerformanceConfig,
        val engine: PerformanceEngine,
        val handle: AttemptHandle,
        var finished: Boolean = false,
    )

    @Volatile private var repository: PerformanceRepository? = null
    @Volatile private var segments: List<LiveSegment> = emptyList()
    @Volatile private var clockDomain: String = "unset"
    @Volatile private var captureSessionId: String? = null

    var holdCapture by mutableStateOf(false)
        private set

    /** Aggregate snapshot: first non-terminal segment, else last finished. */
    var snapshot by mutableStateOf(idleSnapshot())
        private set

    /** Per-segment live snapshots while armed (mode label → snapshot). */
    var segmentSnapshots by mutableStateOf<List<Pair<PerformanceConfig, EngineSnapshot>>>(emptyList())
        private set

    var lastMessage by mutableStateOf("")
        private set

    var historyRevision by mutableStateOf(0)
        private set

    /** Segment threshold drafts — survives PerformancePane leaving composition (sheet peek / page switch). */
    var drafts by mutableStateOf(defaultSegmentDrafts())
        private set

    /** Completed scores from the current arm session, appended one-by-one as each segment finishes. */
    var sessionResults by mutableStateOf<List<AttemptSummary>>(emptyList())
        private set

    /** Accrued under [lock]; copied to [sessionResults] on main to avoid lost updates. */
    private val sessionResultsAcc = mutableListOf<AttemptSummary>()

    private var settingsFile: File? = null

    fun ensureRepo(filesDir: File): PerformanceRepository {
        loadSettings(filesDir)
        repository?.let { return it }
        return PerformanceRepository(File(filesDir, "performance")).also { repository = it }
    }

    fun loadSettings(filesDir: File) {
        val file = File(filesDir, "performance/ui_settings.json")
        settingsFile = file
        if (!file.exists()) return
        runCatching {
            val o = org.json.JSONObject(file.readText())
            val arr = o.getJSONArray("drafts")
            val loaded = mutableListOf<SegmentDraft>()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                loaded += SegmentDraft(
                    mode = PerformanceMode.valueOf(d.getString("mode")),
                    selected = d.getBoolean("selected"),
                    startKmh = d.getString("startKmh"),
                    endKmh = d.getString("endKmh"),
                )
            }
            if (loaded.size == 4) drafts = loaded
        }
    }

    fun updateDrafts(next: List<SegmentDraft>, filesDir: File? = null) {
        drafts = next
        if (settingsFile == null && filesDir != null) {
            settingsFile = File(filesDir, "performance/ui_settings.json")
        }
        val file = settingsFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val arr = org.json.JSONArray()
            next.forEach { d ->
                arr.put(
                    org.json.JSONObject()
                        .put("mode", d.mode.name)
                        .put("selected", d.selected)
                        .put("startKmh", d.startKmh)
                        .put("endKmh", d.endKmh),
                )
            }
            file.writeText(org.json.JSONObject().put("schema", 1).put("drafts", arr).toString(2))
        }
    }

    fun bindCaptureSession(sessionId: String?) {
        captureSessionId = sessionId
        if (sessionId != null) clockDomain = "session:$sessionId"
    }

    fun onRawGnss(
        sampleTimeNanos: Long,
        receivedTimeNanos: Long,
        utcMillis: Long?,
        speedMps: Double?,
        speedAccuracyMps: Double?,
        hasSpeed: Boolean,
        mock: Boolean,
        rawReference: String?,
    ) {
        if (!armed.get()) return
        val live = segments
        if (live.isEmpty()) return
        val seq = sampleSeq.incrementAndGet()
        val sample = SpeedSample(
            sourceId = "phone.gps",
            channelKind = "gnss",
            sequence = seq,
            clockDomain = clockDomain,
            sampleTimeNanos = sampleTimeNanos,
            receivedTimeNanos = receivedTimeNanos,
            utcMillis = utcMillis,
            speedMps = if (hasSpeed) speedMps else null,
            speedAccuracyMps = speedAccuracyMps,
            valid = hasSpeed && speedMps != null && speedMps.isFinite(),
            mock = mock,
            rawReference = rawReference,
        )
        val repo = repository
        synchronized(lock) {
            for (seg in live) {
                if (seg.finished) continue
                try {
                    repo?.appendSample(seg.handle, sample)
                } catch (e: Exception) {
                    publishMessage("速度样本写入失败: ${e.message}")
                }
                val snap = seg.engine.onEvent(EngineEvent.Sample(sample))
                if (isTerminal(snap.phase)) {
                    seg.finished = true
                    persistSegment(seg, snap.result)
                }
            }
            publishLocked()
            maybeReleaseLocked()
        }
    }

    fun watchdogTick() {
        if (!armed.get()) return
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            for (seg in segments) {
                if (seg.finished) continue
                val snap = seg.engine.onEvent(EngineEvent.Watchdog(now))
                if (isTerminal(snap.phase)) {
                    seg.finished = true
                    persistSegment(seg, snap.result)
                }
            }
            publishLocked()
            maybeReleaseLocked()
        }
    }

    /** Arm one or more segments; all share the same GNSS stream until each finishes or cancel. */
    fun arm(filesDir: File, configs: List<PerformanceConfig>): Boolean {
        if (configs.isEmpty()) {
            publishMessage("请至少选择一个测试段")
            return false
        }
        if (armed.get()) {
            publishMessage("已有进行中的测试，请先取消")
            return false
        }
        val repo = ensureRepo(filesDir)
        val created = configs.map { cfg ->
            val h = repo.createAttempt(cfg)
            val eng = PerformanceEngine()
            eng.onEvent(EngineEvent.Arm(cfg))
            LiveSegment(cfg, eng, h)
        }
        segments = created
        sampleSeq.set(0)
        if (captureSessionId == null) clockDomain = "perf:${created.first().handle.id}"
        sessionResultsAcc.clear()
        sessionResults = emptyList()
        armed.set(true)
        holdCapture = true
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.post(watchdogRunnable)
        synchronized(lock) { publishLocked() }
        val labels = configs.joinToString { "${it.mode.shortLabel()} ${trim(it.startSpeedKmh)}→${trim(it.endSpeedKmh)}" }
        publishMessage("已待命 · ${configs.size} 段：$labels")
        return true
    }

    fun arm(filesDir: File, config: PerformanceConfig): Boolean = arm(filesDir, listOf(config))

    fun cancel() {
        if (!armed.get()) return
        synchronized(lock) {
            for (seg in segments) {
                if (seg.finished) continue
                val snap = seg.engine.onEvent(EngineEvent.Cancel)
                seg.finished = true
                persistSegment(seg, snap.result ?: seg.engine.snapshot().result)
            }
            publishLocked()
            releaseLocked("已取消")
        }
    }

    fun recomputeAttempt(filesDir: File, attemptDir: File): Pair<Int, PerformanceResult> {
        val repo = ensureRepo(filesDir)
        val out = repo.recompute(attemptDir)
        historyRevision++
        publishMessage("已重算为 result-v${out.first}（不覆盖旧版本）")
        return out
    }

    fun listAttempts(filesDir: File): List<AttemptSummary> = ensureRepo(filesDir).listAttempts()

    private fun persistSegment(seg: LiveSegment, result: PerformanceResult?) {
        val repo = repository ?: return
        if (result != null) {
            try {
                repo.writeResult(seg.handle, result)
                historyRevision++
                if (result.phase == PerformancePhase.Completed) {
                    val summary = AttemptSummary(
                        id = seg.handle.id,
                        dir = seg.handle.dir,
                        modifiedMs = System.currentTimeMillis(),
                        mode = seg.config.mode,
                        startSpeedKmh = seg.config.startSpeedKmh,
                        endSpeedKmh = seg.config.endSpeedKmh,
                        latestVersion = 1,
                        latestPhase = result.phase,
                        latestQuality = result.quality,
                        latestDurationNs = result.durationNs,
                        latestDistanceMeters = result.distanceMeters,
                    )
                    // Accrue under lock, publish copy on main (one row at a time, no lost updates).
                    sessionResultsAcc += summary
                    val published = sessionResultsAcc.toList()
                    main.post { sessionResults = published }
                    publishMessage(
                        "出成绩 · ${seg.config.mode.shortLabel()} " +
                            "${trim(seg.config.startSpeedKmh)}→${trim(seg.config.endSpeedKmh)}",
                    )
                }
            } catch (e: Exception) {
                publishMessage("保存成绩失败: ${e.message}")
                runCatching { repo.closeSamples(seg.handle) }
            }
        } else {
            runCatching { repo.closeSamples(seg.handle) }
        }
    }

    private fun maybeReleaseLocked() {
        if (segments.isNotEmpty() && segments.all { it.finished }) {
            val n = segments.size
            val ok = segments.count { it.engine.snapshot().phase == PerformancePhase.Completed }
            releaseLocked("本轮结束 · 出成绩 $ok / $n 段")
        }
    }

    private fun releaseLocked(msg: String) {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        armed.set(false)
        holdCapture = false
        segments = emptyList()
        publishMessage(msg)
        main.post {
            snapshot = idleSnapshot()
            segmentSnapshots = emptyList()
        }
    }

    private fun publishLocked() {
        val snaps = segments.map { it.config to it.engine.snapshot() }
        val primary = snaps.firstOrNull { !isTerminal(it.second.phase) }?.second
            ?: snaps.lastOrNull()?.second
            ?: idleSnapshot()
        main.post {
            segmentSnapshots = snaps
            snapshot = primary
        }
    }

    private fun publishMessage(msg: String) {
        main.post { lastMessage = msg }
    }

    private fun isTerminal(phase: PerformancePhase) =
        phase == PerformancePhase.Completed ||
            phase == PerformancePhase.Invalid ||
            phase == PerformancePhase.Cancelled

    private fun idleSnapshot() = EngineSnapshot(
        phase = PerformancePhase.Idle,
        config = null,
        lastSpeedMps = null,
        measuredHz = null,
        quality = QualityTier.PASS,
        reasons = emptyList(),
        result = null,
        runningDurationNs = null,
        runningDistanceMeters = null,
    )

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
}

fun PerformanceMode.shortLabel(): String = when (this) {
    PerformanceMode.ACCEL_0_TO_X -> "起步"
    PerformanceMode.ACCEL_X_TO_Y -> "加速"
    PerformanceMode.BRAKE_X_TO_0 -> "制动"
    PerformanceMode.BRAKE_X_TO_Y -> "减速"
}
