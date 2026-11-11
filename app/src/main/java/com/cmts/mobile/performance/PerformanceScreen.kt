package com.cmts.mobile.performance

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmts.mobile.Live
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private val Champagne = Color(0xFFD4AF37)

@Composable
fun PerformanceScreen(
    filesDir: File,
    onBack: () -> Unit,
    onEnsureCapture: () -> Unit,
    onReleaseCaptureIfIdle: () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TextButton(onClick = {
            onReleaseCaptureIfIdle()
            onBack()
        }) { Text("← 返回地图") }
        PerformancePane(
            filesDir = filesDir,
            onEnsureCapture = onEnsureCapture,
            onReleaseCaptureIfIdle = onReleaseCaptureIfIdle,
            modifier = Modifier.fillMaxSize(),
            embedded = false,
        )
    }
}

@Composable
fun PerformancePane(
    filesDir: File,
    onEnsureCapture: () -> Unit,
    onReleaseCaptureIfIdle: () -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    embedded: Boolean = true,
) {
    val snap = PerformanceController.snapshot
    val segmentSnaps = PerformanceController.segmentSnapshots
    val message = PerformanceController.lastMessage
    val historyRev = PerformanceController.historyRevision
    LaunchedEffect(filesDir) {
        PerformanceController.loadSettings(filesDir)
        PerformanceController.ensureRepo(filesDir)
    }
    val drafts = PerformanceController.drafts
    val sessionResults = PerformanceController.sessionResults
    var showHistory by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<AttemptSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    if (showHistory) {
        PerformanceHistoryPane(
            filesDir = filesDir,
            revision = historyRev,
            detail = detail,
            onDetail = { detail = it },
            onBack = { if (detail != null) detail = null else showHistory = false },
            onRecompute = { summary ->
                runCatching {
                    PerformanceController.recomputeAttempt(filesDir, summary.dir)
                }.onFailure { error = it.message }
            },
            modifier = modifier,
        )
        return
    }

    val active = snap.phase !in setOf(
        PerformancePhase.Idle,
        PerformancePhase.Completed,
        PerformancePhase.Cancelled,
        PerformancePhase.Invalid,
    ) || PerformanceController.holdCapture

    val settings: @Composable () -> Unit = {
        if (!landscape) {
        Text(
            "性能测试",
            style = if (embedded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
            color = Champagne,
        )
        Text(
            "可多选测试段：一次跑完输出多项成绩。原始 GNSS 速度；插值不提高精度。",
            style = MaterialTheme.typography.bodySmall,
        )

        }
        if (!landscape) Text("测试段（可多选）", color = Champagne, style = MaterialTheme.typography.labelMedium)
        // Row1: two accel · Row2: two brake — compact dual columns
        listOf(0 to 1, 2 to 3).forEach { (left, right) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(left, right).forEach { index ->
                    val draft = drafts[index]
                    SegmentRow(
                        compact = landscape,
                        draft = draft,
                        enabled = !active,
                        modifier = Modifier.weight(1f),
                        onToggle = {
                            PerformanceController.updateDrafts(
                                drafts.toMutableList().also {
                                    it[index] = draft.copy(selected = !draft.selected)
                                },
                                filesDir,
                            )
                        },
                        onStart = { v ->
                            PerformanceController.updateDrafts(
                                drafts.toMutableList().also { it[index] = draft.copy(startKmh = v) },
                                filesDir,
                            )
                        },
                        onEnd = { v ->
                            PerformanceController.updateDrafts(
                                drafts.toMutableList().also { it[index] = draft.copy(endKmh = v) },
                                filesDir,
                            )
                        },
                    )
                }
            }
        }

    }
    val actions: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    error = null
                    val configs = configsFromDrafts(drafts).getOrElse {
                        error = it.message
                        return@Button
                    }
                    onEnsureCapture()
                    if (!PerformanceController.arm(filesDir, configs)) {
                        error = PerformanceController.lastMessage
                    }
                },
                enabled = !active,
                modifier = Modifier.weight(1f),
            ) { Text("待命") }
            OutlinedButton(
                onClick = { PerformanceController.cancel() },
                enabled = active,
                modifier = Modifier.weight(1f),
            ) { Text("取消") }
            TextButton(onClick = { showHistory = true }) { Text("详情") }
        }

        Text(
            if (PerformanceController.stayArmed) "连续待命：开 · 每轮结束自动待命下一轮，取消后关闭"
            else "连续待命：关 · 点「待命」开启",
            style = MaterialTheme.typography.labelSmall,
            color = if (PerformanceController.stayArmed) Champagne else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
    }
    val results: @Composable () -> Unit = {
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)

        StatusCard(snap, segmentSnaps)
    }
    val scores: @Composable () -> Unit = {
        // Live table = current / just-finished run only; older runs live behind 详情 (history).
        ResultsTable(
            attempts = sessionResults,
            showTitle = !landscape,
            onOpen = { detail = it; showHistory = true },
        )
        Text(
            "采集: ${when {
                Live.running -> "前台记录中"
                PerformanceController.holdCapture -> "性能保持中"
                else -> "未采集"
            }} · phone.gps",
            style = MaterialTheme.typography.labelSmall,
        )
    }
    if (landscape) {
        val pager = rememberPagerState { 2 }
        val scope = rememberCoroutineScope()
        Column(modifier.fillMaxSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf("成绩", "设置与状态").forEachIndexed { index, label ->
                    Text(label, Modifier.clickable { scope.launch { pager.animateScrollToPage(index) } }.padding(4.dp),
                        color = if (pager.currentPage == index) Champagne else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { page ->
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (page == 0) scores() else { settings(); actions(); results() }
                    }

                }
            }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            settings()
            actions()
            results()
            scores()
        }
    }
}


@Composable
private fun DenseSpeedField(
    prefix: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val textColor = if (enabled) Color(0xFFE7E1E5) else Color(0xFF8A8580)
    val borderColor = if (enabled) Color(0xFF5C5B60) else Color(0xFF3A3940)
    Row(
        modifier
            .height(40.dp)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(prefix, style = MaterialTheme.typography.labelMedium, color = Champagne)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            // ~40dp field: numeral fills box height; end-aligned like a speed readout.
            textStyle = TextStyle(
                color = textColor,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                textAlign = TextAlign.End,
            ),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                    if (value.isEmpty()) {
                        Text("—", color = Color(0xFF8A8580), fontSize = 22.sp, lineHeight = 28.sp, textAlign = TextAlign.End)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun SegmentRow(
    compact: Boolean,
    draft: SegmentDraft,
    enabled: Boolean,
    onToggle: () -> Unit,
    onStart: (String) -> Unit,
    onEnd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF202127)),
        modifier = modifier,
    ) {
        val header: @Composable () -> Unit = {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Checkbox(
                    checked = draft.selected,
                    onCheckedChange = { onToggle() },
                    enabled = enabled,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    draft.mode.shortLabel(),
                    color = Champagne,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
        val fields: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                DenseSpeedField(
                    prefix = "起",
                    value = draft.startKmh,
                    onValueChange = { onStart(it.filter { ch -> ch.isDigit() || ch == '.' }) },
                    enabled = enabled && draft.mode != PerformanceMode.ACCEL_0_TO_X,
                    modifier = Modifier.weight(1f),
                )
                DenseSpeedField(
                    prefix = "终",
                    value = draft.endKmh,
                    onValueChange = { onEnd(it.filter { ch -> ch.isDigit() || ch == '.' }) },
                    enabled = enabled && draft.mode != PerformanceMode.BRAKE_X_TO_0,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (compact) Row(Modifier.fillMaxWidth().padding(3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            header()
            Box(Modifier.weight(1f)) { fields() }
        } else Column(Modifier.padding(6.dp)) { header(); fields() }
    }
}

@Composable
private fun ResultsTable(attempts: List<AttemptSummary>, showTitle: Boolean = true, onOpen: (AttemptSummary) -> Unit) {
    if (showTitle) Text("成绩表", color = Champagne, style = MaterialTheme.typography.titleSmall)
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF202127)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ResultTableRow("测试时间", "段/类型", "距离", "时间", header = true)
            HorizontalDivider(color = Color(0xFF3A3D46))
            if (attempts.isEmpty()) {
                Text("暂无成绩", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            } else {
                attempts.forEach { a ->
                    ResultTableRow(
                        time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(a.modifiedMs)),
                        segment = formatSegment(a),
                        distance = formatDistance(a),
                        duration = formatDuration(a),
                        header = false,
                        onClick = { onOpen(a) },
                    )
                    HorizontalDivider(color = Color(0xFF2A2D36))
                }
            }
        }
    }
}

@Composable
private fun ResultTableRow(
    time: String,
    segment: String,
    distance: String,
    duration: String,
    header: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
    val color = if (header) Champagne else Color(0xFFE7E1E5)
    val mod = Modifier.fillMaxWidth().padding(vertical = 4.dp).then(
        if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
    Row(mod, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(time, style = style, color = color, modifier = Modifier.weight(1.0f), maxLines = 2)
        Text(segment, style = style, color = color, modifier = Modifier.weight(1.1f), maxLines = 2)
        Text(distance, style = style, color = color, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End, maxLines = 1)
        Text(duration, style = style, color = color, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End, maxLines = 1)
    }
}

private fun formatSegment(a: AttemptSummary): String =
    "${a.mode.shortLabel()} ${trimNum(a.startSpeedKmh)}→${trimNum(a.endSpeedKmh)}"

private fun formatDistance(a: AttemptSummary): String {
    if (a.latestPhase != PerformancePhase.Completed) return "—"
    return a.latestDistanceMeters?.let { "%.1fm".format(it) } ?: "—"
}

private fun formatDuration(a: AttemptSummary): String {
    if (a.latestPhase != PerformancePhase.Completed) return a.latestPhase?.name ?: "—"
    return a.latestDurationNs?.let { "%.2fs".format(it / 1e9) } ?: "—"
}

private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun StatusCard(snap: EngineSnapshot, segments: List<Pair<PerformanceConfig, EngineSnapshot>>) {
    val speed = snap.lastSpeedMps?.let { "%.1f km/h".format(it * 3.6) }
    val hz = snap.measuredHz?.let { "%.1f Hz".format(it) }
    val quality = (segments.map { it.second.quality } + snap.quality).maxBy { it.ordinal }
    val warning = when (quality) {
        QualityTier.PASS -> null
        QualityTier.WARN -> "质量提醒"
        QualityTier.FAIL -> "质量异常"
    }
    val phase = when (snap.phase) {
        PerformancePhase.Idle -> "空闲"
        PerformancePhase.Armed -> "待命"
        PerformancePhase.Ready -> "就绪"
        PerformancePhase.Running -> "测试中"
        PerformancePhase.StartCandidate -> "确认起点"
        PerformancePhase.FinishCandidate -> "确认终点"
        PerformancePhase.Completed -> "已完成"
        PerformancePhase.Cancelled -> "已取消"
        PerformancePhase.Invalid -> "无效"
    }
    var expanded by remember { mutableStateOf(false) }
    val indicator = if (segments.isEmpty()) null else "${segments.size} 段 ${if (expanded) "▴" else "▾"}"
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF202127)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(enabled = segments.isNotEmpty()) { expanded = !expanded }.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(listOfNotNull(phase, speed, hz, warning, indicator).joinToString("  ·  "), color = Champagne, style = MaterialTheme.typography.labelMedium)
            if (expanded) segments.forEach { (cfg, s) ->
                Text("${trimNum(cfg.startSpeedKmh)}→${trimNum(cfg.endSpeedKmh)} · ${s.phase.name}" +
                    if (s.quality == QualityTier.PASS) "" else " · ${s.quality.name}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
@Composable
private fun ResultCard(result: PerformanceResult?) {
    if (result == null) return
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF202127)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("本组成绩", color = Champagne, style = MaterialTheme.typography.titleSmall)
            Text("阶段 ${result.phase.name} · ${result.quality.name}")
            result.durationSeconds()?.let { Text("时间 ${"%.2f".format(it)} s") }
            result.distanceMeters?.let { Text("距离 ${"%.2f".format(it)} m") }
            Text("算法 ${result.algorithmVersion}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PerformanceHistoryPane(
    filesDir: File,
    revision: Int,
    detail: AttemptSummary?,
    onDetail: (AttemptSummary?) -> Unit,
    onBack: () -> Unit,
    onRecompute: (AttemptSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val attempts = remember(revision) { PerformanceController.listAttempts(filesDir) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text("← 返回测试") }
        if (detail == null) {
            Text("性能历史", style = MaterialTheme.typography.titleMedium, color = Champagne)
            ResultsTable(attempts = attempts, onOpen = onDetail)
        } else {
            Text("尝试详情", style = MaterialTheme.typography.titleMedium, color = Champagne)
            Text(detail.id, style = MaterialTheme.typography.labelSmall)
            Text(formatSegment(detail))
            val repo = PerformanceController.ensureRepo(filesDir)
            val latest = remember(detail.id, revision) { repo.loadLatestResult(detail.dir) }
            latest?.let { (ver, result) ->
                Text("最新 result-v$ver")
                ResultCard(result)
            }
            Button(onClick = { onRecompute(detail) }, modifier = Modifier.fillMaxWidth()) {
                Text("用原始速度样本重算（新版本）")
            }
        }
    }
}



@Composable
internal fun CompactPerformanceResults(filesDir: File) {
 // Current / just-finished run only (same as the live 成绩 table); history stays behind 详情.
 val rows = PerformanceController.sessionResults
 Column(Modifier.fillMaxSize().padding(6.dp)) {
  Text("性能成绩", color = Champagne)
  Column(Modifier.verticalScroll(rememberScrollState())) {
   if (rows.isEmpty()) Text("暂无成绩")
   rows.forEach {
    Text(formatSegment(it), style = MaterialTheme.typography.labelSmall)
    Text("${formatDuration(it)} · ${formatDistance(it)}")
    HorizontalDivider()
   }
  }
 }
}

