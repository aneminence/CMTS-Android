package com.cmts.mobile.performance

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cmts.mobile.Live
import com.cmts.mobile.CmtsColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private val Champagne: Color
    @Composable get() = MaterialTheme.colorScheme.primary

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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("测试段", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("可多选 · km/h", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Row1: two accel · Row2: two brake — compact dual columns
        listOf(0 to 1, 2 to 3).forEach { (left, right) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("待命") }
            OutlinedButton(
                onClick = { PerformanceController.cancel() },
                enabled = active,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("取消") }
            TextButton(onClick = { showHistory = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("历史详情") }
        }

        Text(
            if (PerformanceController.stayArmed) "连续待命：开 · 每轮结束自动待命下一轮，取消后关闭"
            else "连续待命：关 · 点「待命」开启",
            style = MaterialTheme.typography.bodySmall,
            color = if (PerformanceController.stayArmed) Champagne else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(12.dp),
        ) { Text(error!!, modifier = Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall) }
    }
    val results: @Composable () -> Unit = {
        StatusCard(snap, segmentSnaps)
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (landscape) {
        val pager = rememberPagerState { 2 }
        val scope = rememberCoroutineScope()
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("成绩", "设置与状态").forEachIndexed { index, label ->
                    FilterChip(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                }
            }
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp)) { page ->
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (page == 0) scores() else { results(); actions(); settings() }
                    }

                }
            }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!embedded) Text("性能测试", style = MaterialTheme.typography.headlineSmall)
            results()
            actions()
            scores()
            settings()
            Text("一次跑程可输出多个测试段。成绩使用原始 GNSS 速度；插值不提高测量精度。计分待命与普通录制独立。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant
    Surface(modifier = modifier.heightIn(min = 48.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
        Text(prefix, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = TextStyle(
                color = textColor,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
            ),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                contentDescription = if (prefix == "起") "起始速度，单位千米每小时" else "结束速度，单位千米每小时"
            },
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                    if (value.isEmpty()) {
                        Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp, textAlign = TextAlign.End)
                    }
                    inner()
                }
            },
        )
        }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        val header: @Composable () -> Unit = {
            FilterChip(
                selected = draft.selected,
                onClick = onToggle,
                enabled = enabled,
                label = { Text(draft.mode.shortLabel(), style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                leadingIcon = if (draft.selected) { { Text("✓", style = MaterialTheme.typography.labelLarge) } } else null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            )
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
        Column(Modifier.fillMaxWidth().padding(if (compact) 8.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            header()
            fields()
        }
    }
}

@Composable
private fun ResultsTable(attempts: List<AttemptSummary>, showTitle: Boolean = true, onOpen: (AttemptSummary) -> Unit) {
    if (showTitle) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("成绩表", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text("${attempts.size} 项", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            ResultTableRow("测试时间", "段/类型", "距离", "时间", header = true)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (attempts.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("暂无成绩", style = MaterialTheme.typography.bodyMedium)
                    Text("完成测试段后在这里查看时间与距离。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
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
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val mod = Modifier.fillMaxWidth().then(
        if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ).heightIn(min = if (header) 28.dp else 48.dp).padding(vertical = 4.dp)
    Row(mod, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = style, color = color, modifier = Modifier.weight(1.0f), textAlign = TextAlign.Center, maxLines = 2)
        Text(segment, style = style, color = color, modifier = Modifier.weight(1.1f), textAlign = TextAlign.Center, maxLines = 2)
        Text(distance, style = style.copy(fontFeatureSettings = "tnum", fontWeight = if (header) FontWeight.Medium else FontWeight.SemiBold),
            color = color, modifier = Modifier.weight(0.8f), textAlign = TextAlign.Center, maxLines = 2)
        Text(duration, style = style.copy(fontFeatureSettings = "tnum", fontWeight = if (header) FontWeight.Medium else FontWeight.SemiBold),
            color = if (header) color else Champagne, modifier = Modifier.weight(0.8f), textAlign = TextAlign.Center, maxLines = 2)
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable(enabled = segments.isNotEmpty()) { expanded = !expanded }
            .heightIn(min = 64.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(phase, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (warning != null) QualityBadge(quality)
                else Text("性能测试", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(listOfNotNull(speed, hz, indicator).joinToString("  ·  ").ifEmpty { "等待速度样本" },
                color = Champagne, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
            if (expanded) segments.forEach { (cfg, s) ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("${trimNum(cfg.startSpeedKmh)}→${trimNum(cfg.endSpeedKmh)} · ${s.phase.name}" +
                    if (s.quality == QualityTier.PASS) "" else " · ${s.quality.name}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun QualityBadge(quality: QualityTier) {
    val (container, content) = when (quality) {
        QualityTier.PASS -> CmtsColors.SuccessContainer to CmtsColors.Success
        QualityTier.WARN -> CmtsColors.WarningContainer to CmtsColors.Warning
        QualityTier.FAIL -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(when (quality) {
            QualityTier.PASS -> "✓ PASS · 通过"
            QualityTier.WARN -> "! WARN · 提醒"
            QualityTier.FAIL -> "× FAIL · 异常"
        }, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun ResultCard(result: PerformanceResult?) {
    if (result == null) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本组成绩", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                QualityBadge(result.quality)
            }
            Text("阶段 ${result.phase.name}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            result.durationSeconds()?.let { Text("时间  ${"%.2f".format(it)} s", color = Champagne,
                style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum")) }
            result.distanceMeters?.let { Text("距离 ${"%.2f".format(it)} m",
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")) }
            Text("算法 ${result.algorithmVersion}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← 返回测试") }
        if (detail == null) {
            Text("性能历史", style = MaterialTheme.typography.titleLarge)
            ResultsTable(attempts = attempts, onOpen = onDetail)
        } else {
            Text("尝试详情", style = MaterialTheme.typography.titleLarge)
            Text(detail.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatSegment(detail), style = MaterialTheme.typography.titleMedium, color = Champagne)
            val repo = PerformanceController.ensureRepo(filesDir)
            val latest = remember(detail.id, revision) { repo.loadLatestResult(detail.dir) }
            latest?.let { (ver, result) ->
                Text("最新 result-v$ver", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ResultCard(result)
            }
            Button(onClick = { onRecompute(detail) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("用原始速度样本重算（新版本）")
            }
        }
    }
}



@Composable
internal fun CompactPerformanceResults(filesDir: File) {
 // Current / just-finished run only (same as the live 成绩 table); history stays behind 详情.
 val rows = PerformanceController.sessionResults
 Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
   Text("性能成绩", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
   Text("${rows.size} 项", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   if (rows.isEmpty()) Text("暂无成绩", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   rows.forEach {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
     Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(formatSegment(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("${formatDuration(it)} · ${formatDistance(it)}", color = Champagne,
       style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
     }
    }
   }
  }
 }
}

