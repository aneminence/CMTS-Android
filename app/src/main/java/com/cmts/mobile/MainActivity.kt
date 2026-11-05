package com.cmts.mobile
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import com.cmts.mobile.performance.PerformanceController
import com.cmts.mobile.performance.PerformancePane
class MainActivity : ComponentActivity() {
 private var filesVisible by mutableStateOf(false)
 private var pendingPerfCapture by mutableStateOf(false)
 private var fileStatus by mutableStateOf("")
 private var busy by mutableStateOf(false)
 private var imported by mutableStateOf<VboData?>(null)
 private var replay by mutableStateOf<ReplayData?>(null)
 private var replayIndex by mutableIntStateOf(0)
 private var replaySpeed by mutableDoubleStateOf(1.0)
 private var playing by mutableStateOf(false)
 private fun openReplay(session: File) = job {
  fileStatus = "正在读取历史记录…"
  replay = withContext(Dispatchers.IO) { Replay.read(session) { percent -> runOnUiThread { fileStatus = "正在读取历史记录：$percent%" } } }; replayIndex = 0; playing = false; filesVisible = false
  "已打开历史记录"
 }
 private var csvSession: File? = null
 private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
  val session = csvSession
  if(uri != null && session != null) job { withContext(Dispatchers.IO) {
   require(File(session,"closed.marker").exists()) { "请先停止并保存记录" }
   requireNotNull(contentResolver.openOutputStream(uri)).use { out -> File(session,"samples.csv").inputStream().use { it.copyTo(out) } }
   "CSV 已导出（完整原始采样）"
  } }
 }
 private var preparedVbo: File? = null
 private fun job(block: suspend () -> String) {
  busy = true
  lifecycleScope.launch {
   fileStatus = try { block() } catch(e: Exception) { "操作失败：${e.message}" }
   busy = false
  }
 }
 private val exportVbo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
  val prepared = preparedVbo
  if(uri != null && prepared != null) job { withContext(Dispatchers.IO) {
   requireNotNull(contentResolver.openOutputStream(uri)).use { out -> prepared.inputStream().use { it.copyTo(out) } }
   "VBO 已导出"
  } }
 }
 private val exportKml = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri ->
  val prepared = File(cacheDir, "export.kml")
  if(uri != null) job { withContext(Dispatchers.IO) {
   require(prepared.exists()) { "导出临时文件已丢失，请重新生成 KML" }
   requireNotNull(contentResolver.openOutputStream(uri)).use { out -> prepared.inputStream().use { it.copyTo(out) } }
   "KML 已导出"
  } }
 }
 private fun prepareKml(session: File) = job {
  val count = withContext(Dispatchers.IO) {
   File(cacheDir, "export.kml").bufferedWriter().use { Kml.export(session, it) }
  }
  exportKml.launch("cmts-${session.name}.kml")
  "KML 已生成（$count 个真实 GNSS 点），请选择保存位置"
 }
 private val openVbo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if(uri != null) job {
   val data = withContext(Dispatchers.IO) { requireNotNull(contentResolver.openInputStream(uri)).bufferedReader().use { Vbo.read(it) } }
   imported = data
   "已打开 ${data.rows.size} 行、${data.columns.size} 个通道；离线文件"
  }
 }
 private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
  if(result[Manifest.permission.ACCESS_FINE_LOCATION] == true) { if (pendingPerfCapture) { pendingPerfCapture = false; beginPerformanceCapture() } else beginRecording() } else { pendingPerfCapture = false; Live.status = "需要精确定位授权；未开始采集" }
 }
 private fun beginRecording() {
  startForegroundService(CaptureService.acquireIntent(this, CaptureService.CLIENT_RECORD))
 }
 private fun beginPerformanceCapture() {
  startForegroundService(CaptureService.acquireIntent(this, CaptureService.CLIENT_PERF))
 }
 private fun releasePerformanceCaptureIfIdle() {
  if (!PerformanceController.holdCapture) {
   startService(CaptureService.releaseIntent(this, CaptureService.CLIENT_PERF))
  }
 }
 private fun prepareVbo(session: File) = job {
  val prepared = withContext(Dispatchers.IO) {
   File(cacheDir, "export.vbo").also { file -> file.bufferedWriter().use { Vbo.export(session, it) } }
  }
  preparedVbo = prepared; exportVbo.launch("cmts-${session.name}.vbo")
  "VBO 已生成，请选择保存位置"
 }
 private fun toggleRecording() {
  if (Live.running) {
   if (PerformanceController.holdCapture) {
    Live.status = "停止记录不会中断进行中的性能测试；仅结束普通录制会话"
   }
   startService(CaptureService.releaseIntent(this, CaptureService.CLIENT_RECORD))
  } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
   beginRecording()
  } else {
   permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
  }
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
       onCsv = { csvSession = it; exportCsv.launch("cmts-${it.name}.csv") }, onReplay = { openReplay(it) }, onVbo = { prepareVbo(it) }, onKml = { prepareKml(it) },
       onCloseFile = { imported = null })
     } else {
      MainCaptureScreen(
       history = replay,
       replayIndex = replayIndex,
       replaySpeed = replaySpeed,
       playing = playing,
       onReplayIndex = { replayIndex = it },
       onReplaySpeed = { replaySpeed = it },
       onPlaying = { playing = it },
       onExitReplay = { playing = false; replay = null },
       onToggleRecording = { toggleRecording() },
       onOpenFiles = { filesVisible = true },
       onUsbDebug = { startActivity(Intent(this@MainActivity, UsbDebugActivity::class.java)) },
       filesDir = filesDir,
       onEnsurePerformanceCapture = {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) beginPerformanceCapture()
        else { pendingPerfCapture = true; permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
       },
       onReleasePerformanceCaptureIfIdle = { releasePerformanceCaptureIfIdle() },
      )
     }
    }
   }
  }
 }
}

@Composable
private fun PageDots(pagerState: PagerState, modifier: Modifier = Modifier) {
 val scope = rememberCoroutineScope()
 Row(
  modifier.fillMaxWidth().padding(vertical = 6.dp),
  horizontalArrangement = Arrangement.Center,
  verticalAlignment = Alignment.CenterVertically,
 ) {
  repeat(pagerState.pageCount) { index ->
   val selected = pagerState.currentPage == index
   Box(
    Modifier
     .padding(horizontal = 5.dp)
     .size(if (selected) 9.dp else 7.dp)
     .background(
      color = if (selected) Color(0xFFD4AF37) else Color(0xFF5C5F69),
      shape = CircleShape,
     )
     .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
   )
  }
 }
}

private enum class SidePanelAnchor { Hidden, Peek, Half, Full }

@Composable
private fun LandscapePeekChrome(title: String, onStart: () -> Unit, pullHint: String, frame: ReplayFrame? = null) {
 Column(
  Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
  verticalArrangement = Arrangement.spacedBy(4.dp)
 ) {
  Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
  Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(if (Live.running) "停止并保存记录" else "开始记录") }
  var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
  LaunchedEffect(Unit) { while(true) { tick = SystemClock.elapsedRealtimeNanos(); delay(1000) } }
  val samples = frame?.samples ?: Live.samples
  val now = frame?.nanos ?: tick
  val gps = samples["phone.gps"]
  val sat = samples["phone.satellites"]
  fun display(sample: Sample?, index: Int, scale: Double = 1.0, digits: Int = 1) = dashboardValue(
   if(isFresh(sample, now, frame != null || Live.running)) sample?.values?.getOrNull(index) else null, scale, digits)
  PeekGauge("速度", display(gps,3,3.6), "km/h", Modifier.fillMaxWidth().weight(1f))
  PeekGauge("高度", display(gps,2), "m", Modifier.fillMaxWidth().weight(1f))
  PeekGauge("大气压力", display(samples["phone.pressure"],0), "mbar", Modifier.fillMaxWidth().weight(1f))
  PeekGauge("卫星 可见 / 定位", "${display(sat,0,digits=0)} / ${display(sat,1,digits=0)}", "颗", Modifier.fillMaxWidth().weight(1f))
  Text(pullHint, color = Color(0xFFD4AF37), style = MaterialTheme.typography.labelSmall, maxLines = 1)
  Text(if(frame != null) "历史回放" else Live.status, style = MaterialTheme.typography.labelSmall, maxLines = 1)
 }
}


@Composable
private fun PeekGauge(title: String, value: String, unit: String, modifier: Modifier) {
 Card(modifier) {
  Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    Text(unit, style = MaterialTheme.typography.labelSmall, color = Color(0xFFD4AF37))
   }
   Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
    Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
   }
  }
 }
}

/** Natural-portrait phone: ROTATION_90 puts the old bottom edge on the right; ROTATION_270 on the left. */
internal fun panelOnEndForRotation(rotation: Int): Boolean = when (rotation) {
 Surface.ROTATION_90 -> true
 Surface.ROTATION_270 -> false
 Surface.ROTATION_180 -> true
 else -> true
}
@Composable
internal fun currentDisplayRotation(): Int {
 val configuration = LocalConfiguration.current
 val context = LocalContext.current
 return remember(configuration) {
  if (android.os.Build.VERSION.SDK_INT >= 30) context.display?.rotation ?: Surface.ROTATION_0
  else @Suppress("DEPRECATION") (context.getSystemService(WindowManager::class.java)).defaultDisplay.rotation
 }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MainCaptureScreen(
 history: ReplayData?,
 replayIndex: Int,
 replaySpeed: Double,
 playing: Boolean,
 onReplayIndex: (Int) -> Unit,
 onReplaySpeed: (Double) -> Unit,
 onPlaying: (Boolean) -> Unit,
 onExitReplay: () -> Unit,
 onToggleRecording: () -> Unit,
 onOpenFiles: () -> Unit,
 onUsbDebug: () -> Unit,
 filesDir: java.io.File,
 onEnsurePerformanceCapture: () -> Unit,
 onReleasePerformanceCaptureIfIdle: () -> Unit,
) {
 val frame = history?.frames?.getOrNull(replayIndex)
 LaunchedEffect(playing, history, replaySpeed) {
  val wallStart = SystemClock.elapsedRealtimeNanos()
  val dataStart = frame?.nanos ?: 0L
  var index = replayIndex
  while(playing && history != null && index < history.frames.lastIndex) {
   delay(33)
   val target = dataStart + ((SystemClock.elapsedRealtimeNanos() - wallStart) * replaySpeed).toLong()
   var next = index
   while(next < history.frames.lastIndex && history.frames[next+1].nanos <= target) next++
   if (next != index) { index = next; onReplayIndex(next) }
  }
  onPlaying(false)
 }
 val replayTrack = remember(history, replayIndex) { history?.track?.takeWhile { it.nanos <= (frame?.nanos ?: 0) } }
 var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
 LaunchedEffect(Live.running) { while(Live.running) { now = SystemClock.elapsedRealtimeNanos(); delay(1000) } }
 val configuration = LocalConfiguration.current
 val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
 val rotation = currentDisplayRotation()
 val panelOnEnd = panelOnEndForRotation(rotation)
 val controlsOnStart = landscape && panelOnEnd
 // Survives rotation via Activity configChanges + rememberSaveable.
 var landscapeExpanded by rememberSaveable { mutableStateOf(true) }
 val sheet = rememberBottomSheetScaffoldState(bottomSheetState = rememberStandardBottomSheetState(skipHiddenState = false))
 // Keep sheetSwipeEnabled always true. Gating on scroll-at-top tore nested scroll mid-gesture
 // (Performance stutter; Dashboard stuck at bottom). Material nested scroll prefers child scroll.
 val scope = rememberCoroutineScope()
 // When returning to portrait, collapsed side panel maps to hidden sheet.
 LaunchedEffect(landscape) {
  if (!landscape && !landscapeExpanded) {
   sheet.bottomSheetState.hide()
  }
 }
 val title = when {
  history != null -> "历史回放 · ${elapsedLabel(history.frames.first().nanos, frame!!.nanos)}"
  Live.running -> "记录中 · ${elapsedLabel(Live.startedNanos, now)}"
  else -> "准备记录"
 }
 @Composable
 fun PortraitPanelBody(fullscreenAction: () -> Unit, expandLabel: String, onToggleExpand: () -> Unit, sheetExpanded: Boolean) {
  // Always request full screen height so Expanded can cover the map. Peek only shows the
  // top compact summary; HorizontalPager exists only when fully Expanded (no half-open swipe).
  val screenH = LocalConfiguration.current.screenHeightDp.dp
  val portraitPager = rememberPagerState(pageCount = { 2 })
  val dashScroll = rememberScrollState()
  LaunchedEffect(sheetExpanded) {
   if (!sheetExpanded && portraitPager.currentPage != 0) portraitPager.scrollToPage(0)
  }

  @Composable
  fun SummaryGauges() {
   var summaryClock by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
   LaunchedEffect(Unit) { while(true) { summaryClock = SystemClock.elapsedRealtimeNanos(); delay(1000) } }
   val summarySamples = frame?.samples ?: Live.samples
   val summaryNow = frame?.nanos ?: summaryClock
   fun summary(source: String, index: Int, scale: Double = 1.0, digits: Int = 1): String {
    val sample = summarySamples[source]
    return dashboardValue(if(isFresh(sample, summaryNow, frame != null || Live.running)) sample?.values?.getOrNull(index) else null, scale, digits)
   }
   Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    PeekGauge("速度", summary("phone.gps",3,3.6), "km/h", Modifier.weight(1f).fillMaxHeight())
    PeekGauge("高度", summary("phone.gps",2), "m", Modifier.weight(1f).fillMaxHeight())
    PeekGauge("气压", summary("phone.pressure",0), "mbar", Modifier.weight(1f).fillMaxHeight())
    PeekGauge("可见/定位", "${summary("phone.satellites",0,digits=0)}/${summary("phone.satellites",1,digits=0)}", "颗", Modifier.weight(1f).fillMaxHeight())
   }
  }

  @Composable
  fun PeekOrDashChrome(includeFullDash: Boolean) {
   Text(title, style = MaterialTheme.typography.titleMedium)
   if(history != null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
     TextButton(onClick = { if(replayIndex == history.frames.lastIndex) onReplayIndex(0); onPlaying(!playing) }) { Text(if(playing) "暂停" else "播放") }
     var speedMenu by remember { mutableStateOf(false) }
     Box {
      TextButton(onClick = { speedMenu = true }) { Text("${replaySpeed}× ▾") }
      DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
       listOf(0.5,1.0,2.0,4.0,8.0).forEach { speed ->
        DropdownMenuItem(text = { Text("${speed}×") }, onClick = { onReplaySpeed(speed); speedMenu = false })
       }
      }
     }
     Spacer(Modifier.weight(1f))
     TextButton(onClick = onExitReplay) { Text("退出回放") }
    }
    Slider(replayIndex.toFloat(), onValueChange = { onPlaying(false); onReplayIndex(it.toInt()) }, valueRange = 0f..history.frames.lastIndex.coerceAtLeast(1).toFloat(), enabled = history.frames.size > 1)
   }
   if(history == null) Button(onClick = onToggleRecording, modifier = Modifier.fillMaxWidth()) { Text(if(Live.running) "停止并保存记录" else "开始记录") }
   SummaryGauges()
   TextButton(onClick = onToggleExpand, modifier = Modifier.fillMaxWidth()) { Text(expandLabel) }
   if (includeFullDash) {
    Text(if(history != null) "历史文件 · 非实时数据" else Live.status, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onUsbDebug) { Text("USB 设备调试") }
    val context = LocalContext.current
    val power = context.getSystemService(android.os.PowerManager::class.java)
    if(power.isPowerSaveMode) {
     Text("省电模式已开启：三星手机可能在锁屏时暂停采样。长时间录制前请关闭省电模式。", color = MaterialTheme.colorScheme.error)
     TextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)) }) { Text("打开省电设置") }
    }
    Dashboard(frame, landscape = false)
    Spacer(Modifier.height(24.dp))
   }
  }

  Column(
   Modifier
    .fillMaxWidth()
    .height(screenH)
    .navigationBarsPadding()
    .padding(horizontal = 16.dp),
   verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
   if (!sheetExpanded) {
    // Half-open: fixed summary only — no HorizontalPager / page dots / swipe hints.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
     Text("Dashboard", style = MaterialTheme.typography.titleMedium, color = Color(0xFFD4AF37), modifier = Modifier.weight(1f))
     TextButton(onClick = fullscreenAction) { Text("全屏地图 ↓") }
    }
    PeekOrDashChrome(includeFullDash = false)
    Spacer(Modifier.weight(1f))
   } else {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
     Text(
      if (portraitPager.currentPage == 0) "Dashboard · 左滑性能" else "性能测试 · 右滑仪表",
      style = MaterialTheme.typography.titleMedium,
      color = Color(0xFFD4AF37),
      modifier = Modifier.weight(1f),
     )
     TextButton(onClick = fullscreenAction) { Text("全屏地图 ↓") }
    }
    HorizontalPager(
     state = portraitPager,
     modifier = Modifier.fillMaxWidth().weight(1f),
     beyondViewportPageCount = 0,
     key = { it },
    ) { page ->
     when (page) {
      0 -> Column(Modifier.fillMaxSize().verticalScroll(dashScroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
       PeekOrDashChrome(includeFullDash = true)
      }
      else -> PerformancePane(
       filesDir = filesDir,
       onEnsureCapture = onEnsurePerformanceCapture,
       onReleaseCaptureIfIdle = onReleasePerformanceCaptureIfIdle,
       modifier = Modifier.fillMaxSize(),
       embedded = true,
      )
     }
    }
    PageDots(portraitPager)
   }
  }
 }
 @Composable
 fun LandscapePanelBody(onCollapse: () -> Unit, isFull: Boolean = false) {
  val context = LocalContext.current
  val power = context.getSystemService(android.os.PowerManager::class.java)
  var speedMenu by remember { mutableStateOf(false) }
  // Chrome + landscape compact grid Dashboard (horizontal browse for extras).
  Column(
   Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
   verticalArrangement = Arrangement.spacedBy(if (history != null) 4.dp else 8.dp)
  ) {
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
    if(history == null) TextButton(onClick = onToggleRecording) { Text(if(Live.running) "停止保存" else "开始记录") }
    TextButton(onClick = onUsbDebug) { Text("USB") }
    TextButton(onClick = onCollapse) { Text(if (panelOnEnd) "收起 →" else "收起 ←") }
   }
   if (history != null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
     TextButton(onClick = { if (replayIndex == history.frames.lastIndex) onReplayIndex(0); onPlaying(!playing) }) { Text(if (playing) "暂停" else "播放") }
     Box {
      TextButton(onClick = { speedMenu = true }) { Text("${replaySpeed}× ▾") }
      DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
       listOf(0.5, 1.0, 2.0, 4.0, 8.0).forEach { speed ->
        DropdownMenuItem(text = { Text("${speed}×") }, onClick = { onReplaySpeed(speed); speedMenu = false })
       }
      }
     }
     Spacer(Modifier.weight(1f))
     TextButton(onClick = onExitReplay) { Text("退出回放") }
    }
    Slider(replayIndex.toFloat(), onValueChange = { onPlaying(false); onReplayIndex(it.toInt()) }, valueRange = 0f..history.frames.lastIndex.coerceAtLeast(1).toFloat(), enabled = history.frames.size > 1)
   }
   Text("Dashboard ↑ · ↓ 性能测试", color = Color(0xFFD4AF37), style = MaterialTheme.typography.labelSmall)
   val landscapePager = rememberPagerState(pageCount = { 2 })
   // Vertical page switch; Dashboard keeps its own horizontalScroll; Performance scrolls vertically
   // via nested scroll (child consumes first). Side-panel drag stays on the edge handle only.
   VerticalPager(
    state = landscapePager,
    modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
    beyondViewportPageCount = 0,
    key = { it },
   ) { page ->
    when (page) {
     0 -> Box(Modifier.fillMaxSize()) { Dashboard(frame, landscape = true) }
     else -> PerformancePane(
      filesDir = filesDir,
      onEnsureCapture = onEnsurePerformanceCapture,
      onReleaseCaptureIfIdle = onReleasePerformanceCaptureIfIdle,
      modifier = Modifier.fillMaxSize(),
      embedded = true,
     )
    }
   }
   PageDots(landscapePager)
  }
 }

 Box(Modifier.fillMaxSize()) {
  if (!landscape) {
   BottomSheetScaffold(
    modifier = Modifier.fillMaxSize(), scaffoldState = sheet,
    sheetSwipeEnabled = true,
    containerColor = Color.Transparent,
    sheetShape = if(sheet.bottomSheetState.currentValue == SheetValue.Expanded) androidx.compose.ui.graphics.RectangleShape else BottomSheetDefaults.ExpandedShape,
    sheetPeekHeight = (if(history != null) 320.dp else 260.dp) + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    sheetContainerColor = Color(0xFF15171C), sheetContentColor = Color(0xFFE7E1E5),
    sheetContent = {
     PortraitPanelBody(
      fullscreenAction = { scope.launch { sheet.bottomSheetState.hide() } },
      expandLabel = if(sheet.bottomSheetState.currentValue == SheetValue.Expanded) "下拉返回地图 ↓" else "上拉全屏后可切换性能测试 ↑",
      onToggleExpand = {
       scope.launch {
        if(sheet.bottomSheetState.currentValue == SheetValue.Expanded) sheet.bottomSheetState.partialExpand() else sheet.bottomSheetState.expand()
       }
      },
      sheetExpanded = sheet.bottomSheetState.currentValue == SheetValue.Expanded,
     )
    }
   ) {
    // Keep map out of status bar (matches landscape safeDrawingPadding). Expanded sheet
    // stops under status insets; without this strip the map peeks as a top seam.
    Box(Modifier.fillMaxSize().background(Color(0xFF15171C))) {
     TrackMap(Modifier.fillMaxSize().statusBarsPadding(), replayTrack, controlsOnStart = false)
    }
   }
   if(sheet.bottomSheetState.currentValue != SheetValue.Expanded) {
    MapControl(onClick = onOpenFiles, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) { Text("记录与文件") }
   }
   if(sheet.bottomSheetState.currentValue == SheetValue.Hidden) {
    Button(onClick = { scope.launch { sheet.bottomSheetState.partialExpand() } }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
     Text(if(Live.running) "记录中 · 打开仪表盘 ↑" else "开始记录 / 仪表盘 ↑")
    }
   }
  } else {
   // Safe area: map + panel both stay out of status/nav bars (no map under system UI).
   Box(Modifier.fillMaxSize().safeDrawingPadding()) {
    TrackMap(Modifier.fillMaxSize(), replayTrack, controlsOnStart = controlsOnStart)
    val density = LocalDensity.current
    val panelOnEndNow = panelOnEnd
    BoxWithConstraints(Modifier.fillMaxSize()) {
     val maxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
     // Keep peek < half < full on all densities (never coerceIn with min>max).
     val peekPx = minOf(with(density) { 212.dp.toPx() }, maxW * 0.32f).coerceAtLeast(minOf(56f, maxW))
     val halfPx = minOf(maxW * 0.72f, maxW).coerceAtLeast(minOf(peekPx + 1f, maxW))
     val fullPx = maxW
     val anchors = remember(maxW, peekPx, halfPx, fullPx) {
      DraggableAnchors {
       SidePanelAnchor.Hidden at 0f
       SidePanelAnchor.Peek at peekPx
       SidePanelAnchor.Half at halfPx
       SidePanelAnchor.Full at fullPx
      }
     }
     val dragState = remember {
      AnchoredDraggableState(
       initialValue = if (landscapeExpanded) SidePanelAnchor.Half else SidePanelAnchor.Peek,
       positionalThreshold = { distance -> distance * 0.4f },
       velocityThreshold = { with(density) { 80.dp.toPx() } },
       snapAnimationSpec = tween(220),
       decayAnimationSpec = exponentialDecay(),
      )
     }
     // Must update anchors during composition/SideEffect — not only LaunchedEffect —
     // and never call requireOffset() during composition (crashes before layout).
     SideEffect { dragState.updateAnchors(anchors, dragState.currentValue) }
     LaunchedEffect(dragState.currentValue) {
      landscapeExpanded = dragState.currentValue != SidePanelAnchor.Peek
     }
     LaunchedEffect(landscape) {
      if (landscape) {
       val target = if (sheet.bottomSheetState.currentValue == SheetValue.Hidden) SidePanelAnchor.Peek else SidePanelAnchor.Half
       dragState.animateTo(target)
      }
     }
     val atPeek = dragState.currentValue == SidePanelAnchor.Peek
     val hidden = dragState.currentValue == SidePanelAnchor.Hidden
     val panelAlign = if (panelOnEndNow) Alignment.CenterEnd else Alignment.CenterStart
     Surface(
      color = Color(0xFF15171C),
      contentColor = Color(0xFFE7E1E5),
      modifier = Modifier
       .align(panelAlign)
       .fillMaxHeight()
       .layout { measurable, constraints ->
        val fallback = when (dragState.currentValue) {
         SidePanelAnchor.Hidden -> 0f
         SidePanelAnchor.Peek -> peekPx
         SidePanelAnchor.Half -> halfPx
         SidePanelAnchor.Full -> fullPx
        }
        val lo = 0
        val hi = maxOf(peekPx, fullPx).toInt().coerceAtLeast(lo)
        val w = runCatching { dragState.requireOffset() }.getOrDefault(fallback)
         .toInt()
         .coerceIn(lo, hi)
        val placeable = measurable.measure(
         constraints.copy(minWidth = w, maxWidth = w, minHeight = constraints.maxHeight, maxHeight = constraints.maxHeight)
        )
        layout(w, placeable.height) { placeable.placeRelative(0, 0) }
       }
     ) {
      if(!hidden) Row(Modifier.fillMaxSize()) {
       // Map-facing drag handle — primary way to peek / half / full
       if (panelOnEndNow) {
        Box(
         Modifier
          .fillMaxHeight()
          .width(22.dp)
          .background(Color(0xFF2A2C33))
          .anchoredDraggable(dragState, reverseDirection = true, orientation = Orientation.Horizontal),
         contentAlignment = Alignment.Center
        ) {
         Text("‖", color = Color(0xFFD4AF37), style = MaterialTheme.typography.titleMedium)
        }
       }
       Box(Modifier.weight(1f).fillMaxHeight()) {
        if (atPeek) {
         LandscapePeekChrome(
          title = title, frame = frame,
          onStart = onToggleRecording,
          pullHint = if (panelOnEndNow) "侧拉查看 Dashboard ←" else "侧拉查看 Dashboard →",
         )
        } else {
         LandscapePanelBody(
          onCollapse = { scope.launch { dragState.animateTo(SidePanelAnchor.Peek) } },
          isFull = dragState.currentValue == SidePanelAnchor.Full,
         )
        }
       }
       if (!panelOnEndNow) {
        Box(
         Modifier
          .fillMaxHeight()
          .width(22.dp)
          .background(Color(0xFF2A2C33))
          .anchoredDraggable(dragState, reverseDirection = false, orientation = Orientation.Horizontal),
         contentAlignment = Alignment.Center
        ) {
         Text("‖", color = Color(0xFFD4AF37), style = MaterialTheme.typography.titleMedium)
        }
       }
      }
     }
     if(hidden) {
      Box(Modifier.align(panelAlign).width(28.dp).height(80.dp)
       .background(Color(0xDD15171C))
       .anchoredDraggable(dragState, reverseDirection = panelOnEndNow, orientation = Orientation.Horizontal), contentAlignment = Alignment.Center) {
       Text(if(panelOnEndNow) "‹" else "›", color = Color(0xFFD4AF37))
      }
     }
     val filesAlign = if (controlsOnStart) Alignment.TopStart else Alignment.TopEnd
     if (dragState.currentValue != SidePanelAnchor.Full) {
      MapControl(
       onClick = onOpenFiles,
       modifier = Modifier.align(filesAlign).padding(8.dp)
      ) { Text("记录与文件") }
     }
    }
   }
  }
 }
}

