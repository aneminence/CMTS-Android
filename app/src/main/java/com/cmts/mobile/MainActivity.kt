package com.cmts.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.util.zip.*

class MainActivity : ComponentActivity() {
 private var selected: File? = null
 private var filesVisible by mutableStateOf(false)
 private var fileStatus by mutableStateOf("")
 private var busy by mutableStateOf(false)
 private var imported by mutableStateOf<VboData?>(null)
 private var preparedVbo: File? = null
 private fun job(block: suspend () -> String) {
  busy = true
  lifecycleScope.launch {
   fileStatus = try { block() } catch(e: Exception) { "操作失败：${e.message}" }
   busy = false
  }
 }
 private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
  val session = selected
  if(uri != null && session != null) job { withContext(Dispatchers.IO) {
   requireNotNull(contentResolver.openOutputStream(uri)).use { out -> ZipOutputStream(out).use { zip ->
    session.listFiles()?.forEach { file -> zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
   } }; "原始数据已导出"
  } }
 }
 private val exportVbo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
  val prepared = preparedVbo
  if(uri != null && prepared != null) job { withContext(Dispatchers.IO) {
   requireNotNull(contentResolver.openOutputStream(uri)).use { out -> prepared.inputStream().use { it.copyTo(out) } }
   "VBO 已导出"
  } }
 }
 private val openVbo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if(uri != null) job {
   val data = withContext(Dispatchers.IO) { requireNotNull(contentResolver.openInputStream(uri)).bufferedReader().use { Vbo.read(it) } }
   imported = data
   "已打开 ${data.rows.size} 行、${data.columns.size} 个通道；离线文件"
  }
 }
 private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
  if(result[Manifest.permission.ACCESS_FINE_LOCATION] == true) begin() else Live.status = "需要精确定位授权；未开始记录"
 }
 private fun begin() { startForegroundService(Intent(this, CaptureService::class.java)) }
 private fun prepareVbo(session: File) = job {
  val prepared = withContext(Dispatchers.IO) {
   File(cacheDir, "export.vbo").also { file -> file.bufferedWriter().use { Vbo.export(session, it) } }
  }
  preparedVbo = prepared; exportVbo.launch("cmts-${session.name}.vbo")
  "VBO 已生成，请选择保存位置"
 }
 @OptIn(ExperimentalMaterial3Api::class)
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState); enableEdgeToEdge()
  setContent {
   MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFD4AF37))) {
    Surface(Modifier.fillMaxSize()) {
     if(filesVisible) {
      RecordsScreen(filesDir, busy, fileStatus, imported,
       onBack = { filesVisible = false }, onOpen = { openVbo.launch(arrayOf("*/*")) },
       onVbo = { prepareVbo(it) }, onZip = { selected = it; export.launch("cmts-${it.name}.zip") },
       onCloseFile = { imported = null })
     } else {
      val sheet = rememberBottomSheetScaffoldState(bottomSheetState = rememberStandardBottomSheetState(skipHiddenState = false))
      val scope = rememberCoroutineScope()
      var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
      LaunchedEffect(Live.running) { while(Live.running) { now = SystemClock.elapsedRealtimeNanos(); delay(1000) } }
      Box(Modifier.fillMaxSize()) {
       BottomSheetScaffold(
       modifier = Modifier.fillMaxSize(), scaffoldState = sheet,
       containerColor = Color.Transparent,
       sheetPeekHeight = 240.dp, sheetContainerColor = Color(0xFF15171C), sheetContentColor = Color(0xFFE7E1E5),
       sheetContent = {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
         Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(if(Live.running) "记录中 · ${elapsedLabel(Live.startedNanos, now)}" else "准备记录", style = MaterialTheme.typography.titleMedium)
          TextButton(onClick = { scope.launch { sheet.bottomSheetState.hide() } }) { Text("全屏地图 ↓") }
         }
         Button(onClick = {
          if(Live.running) { stopService(Intent(this@MainActivity, CaptureService::class.java)); Live.status = "已停止" }
          else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) begin()
          else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
         }, modifier = Modifier.fillMaxWidth()) { Text(if(Live.running) "停止并保存记录" else "开始记录") }
         TextButton(onClick = { scope.launch {
          if(sheet.bottomSheetState.currentValue == SheetValue.Expanded) sheet.bottomSheetState.partialExpand() else sheet.bottomSheetState.expand()
         } }, modifier = Modifier.fillMaxWidth()) { Text(if(sheet.bottomSheetState.currentValue == SheetValue.Expanded) "下拉返回地图 ↓" else "上拉查看 Dashboard ↑") }
         Text(Live.status, style = MaterialTheme.typography.bodySmall)
         Dashboard()
         Spacer(Modifier.height(24.dp))
        }
       }) { TrackMap(Modifier.fillMaxSize()) }
       FilledTonalButton(onClick = { filesVisible = true }, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) { Text("记录与文件") }
       if(sheet.bottomSheetState.currentValue == SheetValue.Hidden) {
        Button(onClick = { scope.launch { sheet.bottomSheetState.partialExpand() } }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
         Text(if(Live.running) "记录中 · 打开仪表盘 ↑" else "开始记录 / 仪表盘 ↑")
        }
       }
      }
     }
    }
   }
  }
 }
}
