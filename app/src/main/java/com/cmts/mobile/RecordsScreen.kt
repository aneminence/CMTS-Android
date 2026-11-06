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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun RecordsScreen(root: File, busy: Boolean, status: String, imported: VboData?, onBack: () -> Unit,
 onDelete: (File) -> Unit, onOpen: () -> Unit, onCsv: (File) -> Unit, onReplay: (File) -> Unit, onVbo: (File) -> Unit, onKml: (File) -> Unit, onCloseFile: () -> Unit) {
 var refresh by remember { mutableIntStateOf(0) }
 var opened by remember { mutableStateOf<File?>(null) }
 val sessions = remember(refresh, Live.running, busy) { File(root, "sessions").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() }
 Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
  TextButton(onClick = onBack) { Text("← 返回实时地图") }
  Text("记录与文件", style = MaterialTheme.typography.headlineSmall)
  Button(onClick = onOpen, enabled = !busy) { Text("打开 VBO 文件") }
  if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  if(status.isNotEmpty()) Text(status)
  if(imported != null) {
   Text("离线 VBO · 不代表手机实时数据")
   if(imported.track.isNotEmpty()) TrackMap(Modifier.fillMaxWidth().height(280.dp), imported.track)
   else Text("无可识别的标准 VBO 经纬度轨迹，仍可查看原始通道")
   var index by remember(imported) { mutableIntStateOf(0) }
   Text("样本 ${index + 1} / ${imported.rows.size}")
   if(imported.rows.size > 1) Slider(index.toFloat(), onValueChange = { index = it.toInt() }, valueRange = 0f..imported.rows.lastIndex.toFloat())
   Row { TextButton(onClick = { index = (index - 1).coerceAtLeast(0) }) { Text("上一条") }; TextButton(onClick = { index = (index + 1).coerceAtMost(imported.rows.lastIndex) }) { Text("下一条") } }
   Text("原始通道值；lat/long 为角分（西经为正），velocity 为 km/h")
   imported.columns.forEachIndexed { column, name -> Text("$name  ${dashboardValue(imported.rows[index][column], digits = 6)}") }
   TextButton(onClick = onCloseFile) { Text("关闭文件") }
  }
  TextButton(onClick = { refresh++ }, enabled = !busy) { Text("刷新试验记录") }
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
   Card(onClick = { if (opened == session) opened = null else onReplay(session) }, enabled = !busy && closed, modifier = Modifier.fillMaxWidth().offset { IntOffset(offset.roundToInt(), 0) }) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
     Column(Modifier.weight(1f)) {
      Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date(session.lastModified())), style = MaterialTheme.typography.bodySmall)
      Text(if(closed) "已保存" else "进行中或意外中断", style = MaterialTheme.typography.labelSmall)
     }
     TextButton(enabled = !busy && !Live.running && closed, onClick = { onVbo(session) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("VBO") }
     TextButton(enabled = !busy && !Live.running && closed, onClick = { onKml(session) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("KML") }
     TextButton(enabled = !busy && !Live.running && closed, onClick = { onCsv(session) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("CSV") }
    }
   }
   }
   }
  }
 }
}

