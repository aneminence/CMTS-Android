package com.cmts.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun RecordsScreen(root: File, busy: Boolean, status: String, imported: VboData?, onBack: () -> Unit,
 onOpen: () -> Unit, onVbo: (File) -> Unit, onZip: (File) -> Unit, onCloseFile: () -> Unit) {
 var refresh by remember { mutableIntStateOf(0) }
 val sessions = remember(refresh, Live.running) { File(root, "sessions").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() }
 Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
  sessions.forEach { session ->
   val closed = File(session, "closed.marker").exists()
   Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp)) {
     Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date(session.lastModified())))
     Text(if(closed) "已保存" else "进行中或意外中断")
     Row {
      TextButton(enabled = !busy && !Live.running && closed, onClick = { onVbo(session) }) { Text("导出 VBO") }
      TextButton(enabled = !busy && !Live.running && closed, onClick = { onZip(session) }) { Text("原始数据 ZIP") }
     }
    }
   }
  }
 }
}
