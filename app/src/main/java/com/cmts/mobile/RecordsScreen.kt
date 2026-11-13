package com.cmts.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecordsScreen(root: File, busy: Boolean, status: String, imported: VboData?, onBack: () -> Unit,
 onDelete: (File) -> Unit, onOpen: () -> Unit, onCsv: (File) -> Unit, onReplay: (File) -> Unit, onVbo: (File) -> Unit, onKml: (File) -> Unit, onCloseFile: () -> Unit) {
 var refresh by remember { mutableIntStateOf(0) }
 val scope=rememberCoroutineScope()
 var recomputing by remember { mutableStateOf(false) }
 var recomputeStatus by remember { mutableStateOf("") }
 var opened by remember { mutableStateOf<File?>(null) }
 val sessions = remember(refresh, Live.running, busy) { File(root, "sessions").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() }
 Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
   TextButton(onClick = onBack) { Text("← 实时地图") }
   Text("本地会话", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Text("记录与文件", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
  Text("查看已保存会话，回放轨迹或导出数据。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  FilledTonalButton(onClick = onOpen, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开 VBO 文件") }
  if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  if(status.isNotEmpty()) RecordNotice(status)
  if(recomputeStatus.isNotEmpty()) RecordNotice(recomputeStatus)
  if(imported != null) {
   Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
   Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   Text("离线 VBO", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
   Text("文件预览 · 非手机实时数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   if(imported.track.isNotEmpty()) TrackMap(Modifier.fillMaxWidth().height(280.dp), imported.track)
   else Text("无可识别的标准 VBO 经纬度轨迹，仍可查看原始通道")
   var index by remember(imported) { mutableIntStateOf(0) }
   Text("样本 ${index + 1} / ${imported.rows.size}", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
   if(imported.rows.size > 1) Slider(index.toFloat(), onValueChange = { index = it.toInt() }, valueRange = 0f..imported.rows.lastIndex.toFloat())
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { index = (index - 1).coerceAtLeast(0) }) { Text("上一条") }; TextButton(onClick = { index = (index + 1).coerceAtMost(imported.rows.lastIndex) }) { Text("下一条") } }
   Text("原始通道值；lat/long 为角分（西经为正），velocity 为 km/h", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   imported.columns.forEachIndexed { column, name ->
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
     Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
     Text(dashboardValue(imported.rows[index][column], digits = 6), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
    }
   }
   TextButton(onClick = onCloseFile) { Text("关闭文件") }
   }
   }
  }
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
   Text("试验记录 · ${sessions.size}", style = MaterialTheme.typography.titleMedium)
   TextButton(onClick = { refresh++ }, enabled = !busy) { Text("刷新") }
  }
  if (sessions.isEmpty()) {
   Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
     Text("暂无试验记录", style = MaterialTheme.typography.titleMedium)
     Text("开始并保存一次记录后，会话会显示在这里。也可以打开已有 VBO 文件。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
   }
  }
  sessions.forEach { session -> key(session) {
   val width = with(LocalDensity.current) { 76.dp.toPx() }
   var drag by remember { mutableStateOf<Float?>(null) }
   val resting by animateFloatAsState(if (opened == session) -width else 0f, label = "删除操作")
   val offset = drag ?: resting
   val canDelete = !busy && session != Live.activeSession
   Box(Modifier.fillMaxWidth().clipToBounds().draggable(
    state = rememberDraggableState { dx -> drag = ((drag ?: resting) + dx).coerceIn(-width, 0f) },
    orientation = Orientation.Horizontal, enabled = canDelete,
    onDragStarted = { drag = resting; opened = null },
    onDragStopped = { velocity ->
     opened = if (velocity < -500f || (velocity < 500f && (drag ?: 0f) < -width / 2)) session else null
     drag = null
    },
   )) {
   if (offset < 0f) TextButton(onClick = { opened = null; onDelete(session) }, enabled = canDelete && opened == session,
    modifier = Modifier.align(Alignment.CenterEnd).width(76.dp)) { Text("删除", color = MaterialTheme.colorScheme.error) }
   val closed = File(session, "closed.marker").exists()
   var exportMenu by remember { mutableStateOf(false) }
   var moreMenu by remember { mutableStateOf(false) }
   val canExport = !busy && !Live.running && closed
   Card(onClick = { if (opened == session) opened = null else onReplay(session) }, enabled = !busy && closed,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    modifier = Modifier.fillMaxWidth().offset { IntOffset(offset.roundToInt(), 0) }) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
     Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(java.util.Date(session.lastModified())), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(if(closed) "已保存 · 点击回放" else "进行中或意外中断", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     }
     Box {
      TextButton(enabled = canExport, onClick = { exportMenu = true }) { Text("导出") }
      DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
       DropdownMenuItem(text = { Text("VBO · 测试数据") }, enabled = canExport, onClick = { exportMenu = false; onVbo(session) })
       DropdownMenuItem(text = { Text("KML · 地图轨迹") }, enabled = canExport, onClick = { exportMenu = false; onKml(session) })
       DropdownMenuItem(text = { Text("CSV · 原始样本") }, enabled = canExport, onClick = { exportMenu = false; onCsv(session) })
      }
     }
     Box {
     IconButton(enabled = !busy && closed, onClick = { moreMenu = true }, modifier = Modifier.semantics { contentDescription = "会话更多操作" }) {
      Text("⋮", style = MaterialTheme.typography.titleLarge)
     }
     DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
     DropdownMenuItem(text = { Text("姿态重算") }, enabled = !busy && !recomputing && !Live.running && closed, onClick = {
      moreMenu=false
      recomputing=true
      scope.launch {
       recomputeStatus=withContext(Dispatchers.IO) {
        runCatching { AttitudeRecompute.export(session); "姿态重算完成：attitude-recomputed.csv（原记录未改动）" }
         .getOrElse { "不能重算：${it.message}" }
       }
       recomputing=false
      }
     })
     }
     }
    }
   }
   }
   }
  }
  Spacer(Modifier.height(12.dp))
 }
}

@Composable
private fun RecordNotice(message: String) {
 Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
  Text(message, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium)
 }
}

