package com.cmts.mobile
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.progressSemantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clipToBounds

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.widget.Toast
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import com.cmts.mobile.performance.PerformanceController
import com.cmts.mobile.performance.PerformancePane
import com.cmts.mobile.remote.RemoteBridge
import com.cmts.mobile.remote.RemoteIdentityStore
import com.cmts.mobile.remote.LocalIpv4
class MainActivity : ComponentActivity() {
 private var previewBound = false
 private var previewBinder: CaptureService.PreviewBinder? = null
 private val previewPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
  previewBinder?.refresh()
 }
 private val previewConnection = object : android.content.ServiceConnection {
  override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
   previewBinder = service as CaptureService.PreviewBinder
   previewBinder?.refresh()
  }
  override fun onServiceDisconnected(name: android.content.ComponentName) { previewBinder = null }
 }
 override fun onStart() {
  super.onStart()
  previewBound = bindService(Intent(this, CaptureService::class.java), previewConnection, BIND_AUTO_CREATE)
  val prefs = getSharedPreferences("preview", MODE_PRIVATE)
  if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("requested", false)) {
   prefs.edit().putBoolean("requested", true).apply()
   previewPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
  }
  if (GnssSourceSettings.load(this) == GnssSource.EXTERNAL) startExternalGnss()
 }
 override fun onStop() {
  if (previewBound) unbindService(previewConnection)
  previewBound = false
  previewBinder = null
  super.onStop()
 }
 private var filesVisible by mutableStateOf(false)
 private var pendingPerfCapture by mutableStateOf(false)
 private var pendingExternalGnss = false
 private val usbPermissionAction get() = "$packageName.USB_PERMISSION"
 private val usbPermissionReceiver = object : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
   if (intent.action != usbPermissionAction) return
   val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
   if (granted) startExternalGnss()
   else Live.gnssStatus = "USB 授权被拒绝，未改用外接定位"
  }
 }
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
  if(result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
   if (pendingExternalGnss) { pendingExternalGnss = false; startExternalGnss() }
   else if (pendingPerfCapture) { pendingPerfCapture = false; beginPerformanceCapture() }
   else beginRecording()
  } else {
   pendingPerfCapture = false
   if (pendingExternalGnss) {
    pendingExternalGnss = false
    Live.gnssStatus = "需要定位权限才能在后台使用外接定位"
   } else {
    Live.status = "需要精确定位授权；未开始采集"
   }
  }
 }
 private fun beginRecording() {
  RecordingCommands.start(this)
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
  if (Live.running || RecordingStateMachine.snapshot() == RecordingState.RECORDING) {
   if (PerformanceController.holdCapture) {
    Live.status = "停止记录不会中断进行中的性能测试；仅结束普通录制会话"
   }
   RecordingCommands.stop(this)
  } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
   beginRecording()
  } else {
   permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
  }
 }

 override fun onDestroy() {
  runCatching { unregisterReceiver(usbPermissionReceiver) }
  super.onDestroy()
 }

 private fun selectGnssSource(source: GnssSource) {
  GnssSourceSettings.save(this, source)
  if (source == GnssSource.PHONE) {
   startService(CaptureService.releaseIntent(this, CaptureService.CLIENT_USB_GNSS))
   Live.gnssStatus = ""
  } else {
   startExternalGnss()
  }
 }

 private fun startExternalGnss() {
  if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
   pendingExternalGnss = true
   Live.gnssStatus = "外接定位后台运行需要定位权限"
   permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
   return
  }
  val usb = getSystemService(UsbManager::class.java)
  val device = GnssSourceSettings.findCh344q(usb)
  if (device == null) {
   Live.gnssStatus = "未检测到紫板 CH344Q，请检查 OTG 连接"
   startForegroundService(CaptureService.acquireIntent(this, CaptureService.CLIENT_USB_GNSS))
   return
  }
  if (!usb.hasPermission(device)) {
   Live.gnssStatus = "正在申请 USB 权限…"
   try {
    usb.requestPermission(
     device,
     PendingIntent.getBroadcast(
      this,
      device.deviceId,
      Intent(usbPermissionAction).setPackage(packageName),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
     ),
    )
   } catch (e: Exception) {
    Live.gnssStatus = "USB 权限申请失败：${e.message}"
   }
   return
  }
  startForegroundService(CaptureService.acquireIntent(this, CaptureService.CLIENT_USB_GNSS))
 }

 @OptIn(ExperimentalMaterial3Api::class)
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState); enableEdgeToEdge()
  Live.gnssSource = GnssSourceSettings.load(this)
  val filter = IntentFilter(usbPermissionAction)
  if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
  else registerReceiver(usbPermissionReceiver, filter)
  RemoteBridge.start(this)
  setContent {
   MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFD4AF37))) {
    Surface(Modifier.fillMaxSize().background(Color(0xFF15171C))
     .safeDrawingPadding().clipToBounds()) {
     if(filesVisible) {
      RecordsScreen(filesDir, busy, fileStatus, imported,
       onBack = { filesVisible = false }, onOpen = { openVbo.launch(arrayOf("*/*")) },
       onDelete = { session -> job {
        withContext(Dispatchers.IO) {
         require(session.canonicalFile.parentFile == File(filesDir, "sessions").canonicalFile && session != Live.activeSession) { "无法删除当前记录" }
         check(session.deleteRecursively()) { "记录删除失败，请刷新后重试" }
        }
        if (replay?.session == session) { playing = false; replay = null; replayIndex = 0 }
        if (csvSession == session) csvSession = null
        "已删除记录"
       } },
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
       onSelectGnssSource = { selectGnssSource(it) },
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
private fun PageDots(pagerState: PagerState, modifier: Modifier = Modifier, vertical: Boolean = false) {
 val scope = rememberCoroutineScope()
 val dots: @Composable () -> Unit = {
  repeat(pagerState.pageCount) { index ->
   val selected = pagerState.currentPage == index
   Box(
    Modifier
     .clickable { scope.launch { pagerState.animateScrollToPage(index) } }
     .padding(5.dp)
     .size(if (selected) 9.dp else 7.dp)
     .background(
      color = if (selected) Color(0xFFD4AF37) else Color(0xFF5C5F69),
      shape = CircleShape,
     )
     ,
   )
  }
 }
 if (vertical) Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { dots() }
 else Row(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) { dots() }
}

private enum class SidePanelAnchor { Hidden, Peek, Half, Full }

@Composable
private fun LandscapePeekChrome(title: String, onStart: () -> Unit, pullHint: String, frame: ReplayFrame? = null, onSelectGnssSource: (GnssSource) -> Unit = {}) {
 Column(
  Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
  verticalArrangement = Arrangement.spacedBy(4.dp)
 ) {
  Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
  Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(if (Live.running) "停止并保存记录" else "开始记录") }
  if (frame == null) GnssSourceSwitch(onSelect = onSelectGnssSource)
  var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
  LaunchedEffect(Unit) { while(true) { tick = SystemClock.elapsedRealtimeNanos(); delay(1000) } }
  val samples = frame?.samples ?: Live.samples
  val now = frame?.nanos ?: tick
  val gps = samples["phone.gps"]
  val sat = samples["phone.satellites"]
  val live = frame != null || Live.running || Live.serviceAlive
  val lastGood = remember { mutableStateMapOf<String, String>() }
  fun display(key: String, sample: Sample?, index: Int, scale: Double = 1.0, digits: Int = 1): String {
   val raw = dashboardValue(if (isFresh(sample, now, live)) sample?.values?.getOrNull(index) else null, scale, digits)
   if (raw != "—") lastGood[key] = raw
   return lastGood[key] ?: raw
  }
  Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
   Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    PeekGauge("速度", display("spd", gps,3,3.6), "km/h", Modifier.weight(1f).fillMaxHeight())
    PeekGauge("高度", display("alt", gps,2), "m", Modifier.weight(1f).fillMaxHeight())
   }
   Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    PeekGauge("大气压力", display("prs", samples["phone.pressure"],0), "mbar", Modifier.weight(1f).fillMaxHeight())
    PeekGauge("卫星 可见 / 定位", "${display("sv", sat,0,digits=0)} / ${display("su", sat,1,digits=0)}", "颗", Modifier.weight(1f).fillMaxHeight())
   }
  }
  Text(pullHint, color = Color(0xFFD4AF37), style = MaterialTheme.typography.labelSmall, maxLines = 1)
  Text(if(frame != null) "历史回放" else Live.status, style = MaterialTheme.typography.labelSmall, maxLines = 1)
 }
}


@Composable
private fun PeekGauge(title: String, value: String, unit: String, modifier: Modifier) {
 Card(modifier) {
  Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Text(unit, style = MaterialTheme.typography.labelSmall, color = Color(0xFFD4AF37), maxLines = 1)
   }
   Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterEnd) {
    Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, textAlign = TextAlign.End, overflow = TextOverflow.Ellipsis)
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
 onSelectGnssSource: (GnssSource) -> Unit,
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
 var panelAnchor by rememberSaveable { mutableStateOf(SidePanelAnchor.Peek) }
 val sheet = key(landscape) {
  val initial = when (panelAnchor) {
   SidePanelAnchor.Hidden -> SheetValue.Hidden
   SidePanelAnchor.Peek -> SheetValue.PartiallyExpanded
   SidePanelAnchor.Half, SidePanelAnchor.Full -> SheetValue.Expanded
  }
  rememberBottomSheetScaffoldState(bottomSheetState = rememberStandardBottomSheetState(initialValue = initial, skipHiddenState = false))
 }
 val scope = rememberCoroutineScope()
 val panelPager = rememberPagerState(pageCount = { if (history == null) 3 else 4 })
 if (!landscape) {
  val current = sheet.bottomSheetState.currentValue
  SideEffect {
   panelAnchor = when (current) {
    SheetValue.Hidden -> SidePanelAnchor.Hidden
    SheetValue.PartiallyExpanded -> SidePanelAnchor.Peek
    SheetValue.Expanded -> SidePanelAnchor.Full
   }
  }
 }
 val title = when {
  history != null -> "历史回放 · ${elapsedLabel(history.frames.first().nanos, frame!!.nanos)}"
  Live.running -> "记录中 · ${elapsedLabel(Live.startedNanos, now)}"
  else -> "准备记录"
 }
 val portraitPeekHeight = if (history == null) 328.dp else 288.dp
 @Composable
 fun PortraitPanelBody(fullscreenAction: () -> Unit, expandLabel: String, onToggleExpand: () -> Unit, sheetExpanded: Boolean) {
  // Always request full screen height so Expanded can cover the map. Peek only shows the
  // top compact summary; HorizontalPager exists only when fully Expanded (no half-open swipe).


  val dashScroll = rememberScrollState()
  @Composable
  fun SummaryGauges() {
   var summaryClock by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
   LaunchedEffect(Unit) { while(true) { summaryClock = SystemClock.elapsedRealtimeNanos(); delay(1000) } }
   val summarySamples = frame?.samples ?: Live.samples
   val summaryNow = frame?.nanos ?: summaryClock
   val live = frame != null || Live.running || Live.serviceAlive
   // Hold last good readout so brief GNSS gaps / map identity swaps do not flash to em-dash.
   val lastGood = remember { mutableStateMapOf<String, String>() }
   fun summary(key: String, source: String, index: Int, scale: Double = 1.0, digits: Int = 1): String {
    val sample = summarySamples[source]
    val fresh = isFresh(sample, summaryNow, live)
    val raw = dashboardValue(if (fresh) sample?.values?.getOrNull(index) else null, scale, digits)
    if (raw != "—") lastGood[key] = raw
    return lastGood[key] ?: raw
   }
   Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    PeekGauge("速度", summary("spd", "phone.gps",3,3.6), "km/h", Modifier.weight(1f).fillMaxHeight())
    PeekGauge("高度", summary("alt", "phone.gps",2), "m", Modifier.weight(1f).fillMaxHeight())
    PeekGauge("气压", summary("prs", "phone.pressure",0), "mbar", Modifier.weight(1f).fillMaxHeight())
    PeekGauge("可见/定位", "${summary("sv", "phone.satellites",0,digits=0)}/${summary("su", "phone.satellites",1,digits=0)}", "颗", Modifier.weight(1f).fillMaxHeight())
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
    ReplaySeekBar(history, replayIndex, { onPlaying(false) }, onReplayIndex)
   }
   if(history == null) Button(onClick = onToggleRecording, modifier = Modifier.fillMaxWidth()) { Text(if(Live.running) "停止并保存记录" else "开始记录") }
   if(history == null) GnssSourceSwitch(onSelect = onSelectGnssSource)
   SummaryGauges()
   if (includeFullDash) TextButton(onClick = onToggleExpand, modifier = Modifier.fillMaxWidth()) { Text(expandLabel) }
   if (includeFullDash) {
    Text(if(history != null) "历史文件 · 非实时数据" else Live.status, style = MaterialTheme.typography.bodySmall)
    RemoteEndpointRow()
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

  Box(Modifier.fillMaxSize()) {
  Column(
   Modifier.fillMaxWidth()
    .then(if (sheetExpanded) Modifier.fillMaxHeight() else Modifier.height(portraitPeekHeight))
    .padding(horizontal = 16.dp),
   verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
   Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(listOf("Dashboard", "性能测试", "车辆姿态", "回放曲线")[panelPager.currentPage],
     Modifier.weight(1f), color = Color(0xFFD4AF37), style = MaterialTheme.typography.titleMedium)
    if (!sheetExpanded) TextButton(onClick = onToggleExpand) { Text("展开 ↑") }
    TextButton(onClick = fullscreenAction) { Text("全屏地图 ↓") }
   }
   val systemConfig = androidx.compose.ui.platform.LocalViewConfiguration.current
   val pagerConfig = remember(systemConfig) {
    object : androidx.compose.ui.platform.ViewConfiguration by systemConfig {
     override val touchSlop = systemConfig.touchSlop * 0.75f
    }
   }
   val pageArea = Modifier.weight(1f)
   CompositionLocalProvider(androidx.compose.ui.platform.LocalViewConfiguration provides pagerConfig) {
    HorizontalPager(panelPager, Modifier.fillMaxWidth().then(pageArea), key = { it }) { page ->
     CompositionLocalProvider(androidx.compose.ui.platform.LocalViewConfiguration provides systemConfig) {
     if (!sheetExpanded) when (page) {
      0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { PeekOrDashChrome(false) }
      1 -> com.cmts.mobile.performance.CompactPerformanceResults(filesDir)
      2 -> CompactAttitude(frame, horizontal = true)
      3 -> history?.let { ReplayCurves(it, replayIndex) { index -> onPlaying(false); onReplayIndex(index) } }
     } else when (page) {
      0 -> Column(Modifier.fillMaxSize().verticalScroll(dashScroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
       PeekOrDashChrome(includeFullDash = true)
      }
      2 -> AttitudePane(frame)
      3 -> history?.let { ReplayCurves(it, replayIndex) { index -> onPlaying(false); onReplayIndex(index) } }
      else -> PerformancePane(
       filesDir = filesDir,
       onEnsureCapture = onEnsurePerformanceCapture,
       onReleaseCaptureIfIdle = onReleasePerformanceCaptureIfIdle,
       modifier = Modifier.fillMaxSize(),
       embedded = true,
      )
     }
    }
    }
   }
   PageDots(panelPager)
  }
  }
 }

 @Composable
 fun LandscapePanelBody(onCollapse: () -> Unit, isFull: Boolean = false) {
  val context = LocalContext.current
  val power = context.getSystemService(android.os.PowerManager::class.java)
  var speedMenu by remember { mutableStateOf(false) }
  Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
   PageDots(panelPager, vertical = true)
   Column(
   Modifier.weight(1f).fillMaxHeight(),
   verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
   BoxWithConstraints(Modifier.fillMaxWidth()) {
    val inlineReplay = maxWidth >= 480.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    @Composable fun PlaybackActions() {
     if (history == null) return
     TextButton(onClick = { if (replayIndex == history.frames.lastIndex) onReplayIndex(0); onPlaying(!playing) }) { Text(if (playing) "暂停" else "播放") }
     Box {
      TextButton(onClick = { speedMenu = true }) { Text("${replaySpeed}× ▾") }
      DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
       listOf(0.5, 1.0, 2.0, 4.0, 8.0).forEach { speed ->
        DropdownMenuItem(text = { Text("${speed}×") }, onClick = { onReplaySpeed(speed); speedMenu = false })
       }
      }
     }

     TextButton(onClick = onExitReplay) { Text("退出回放") }
    }
    Column {
     Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
      if (history == null && panelPager.currentPage == 2) AttitudeZeroAction()
      if (history == null) TextButton(onClick = onToggleRecording) { Text(if(Live.running) "停止保存" else "开始记录") }
      if (inlineReplay) PlaybackActions()
      TextButton(onClick = onUsbDebug) { Text("USB") }
      TextButton(onClick = onCollapse) { Text(if (panelOnEnd) "收起 →" else "收起 ←") }
     }
     if (history != null && !inlineReplay) Row { PlaybackActions() }
    }
   }
   if (history == null) GnssSourceSwitch(onSelect = onSelectGnssSource)
   if (history != null) {
    ReplaySeekBar(history, replayIndex, { onPlaying(false) }, onReplayIndex)
   }

   // Vertical page switch; Dashboard keeps its own horizontalScroll; Performance scrolls vertically
   // via nested scroll (child consumes first). Side-panel drag stays on the edge handle only.
   VerticalPager(
    state = panelPager,
    modifier = Modifier.fillMaxSize(),
    beyondViewportPageCount = 0,
    key = { it },
   ) { page ->
    when (page) {
     0 -> Box(Modifier.fillMaxSize()) { Dashboard(frame, landscape = true) }
     2 -> AttitudePane(frame, landscape = true)
      3 -> history?.let { ReplayCurves(it, replayIndex) { index -> onPlaying(false); onReplayIndex(index) } }
      else -> PerformancePane(
      filesDir = filesDir,
      onEnsureCapture = onEnsurePerformanceCapture,
      onReleaseCaptureIfIdle = onReleasePerformanceCaptureIfIdle,
      modifier = Modifier.fillMaxSize(),
      landscape = true,
      embedded = true,
     )
    }
   }
   }
  }
 }

 Box(Modifier.fillMaxSize()) {
  if (!landscape) {
   BottomSheetScaffold(
    modifier = Modifier.fillMaxSize().background(Color(0xFF15171C)), scaffoldState = sheet,
    sheetDragHandle = null,
    sheetSwipeEnabled = true,
    containerColor = Color.Transparent,
    sheetShape = androidx.compose.ui.graphics.RectangleShape,
    sheetPeekHeight = portraitPeekHeight,
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
     TrackMap(Modifier.layout { measurable, bounds ->
      val visibleHeight = runCatching { sheet.bottomSheetState.requireOffset().toInt() }
       .getOrDefault(bounds.maxHeight).coerceIn(1, bounds.maxHeight.coerceAtLeast(1))
      val map = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(bounds.maxWidth, visibleHeight))
      layout(bounds.maxWidth, bounds.maxHeight) { map.placeRelative(0, 0) }
     }, replayTrack, controlsOnStart = false)
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
       initialValue = panelAnchor,
       positionalThreshold = { distance -> distance * 0.4f },
       velocityThreshold = { with(density) { 80.dp.toPx() } },
       snapAnimationSpec = tween(220),
       decayAnimationSpec = exponentialDecay(),
      )
     }
     // Must update anchors during composition/SideEffect — not only LaunchedEffect —
     // and never call requireOffset() during composition (crashes before layout).
     SideEffect { dragState.updateAnchors(anchors) }
     val settledAnchor = dragState.settledValue
     SideEffect { panelAnchor = settledAnchor }
     val atPeek = dragState.currentValue == SidePanelAnchor.Peek
     val hidden = dragState.settledValue == SidePanelAnchor.Hidden
     val panelAlign = if (panelOnEndNow) Alignment.CenterEnd else Alignment.CenterStart
     fun panelWidth(): Int {
      val fallback = when (dragState.currentValue) {
       SidePanelAnchor.Hidden -> 0f
       SidePanelAnchor.Peek -> peekPx
       SidePanelAnchor.Half -> halfPx
       SidePanelAnchor.Full -> fullPx
      }
      return runCatching { dragState.requireOffset() }.getOrDefault(fallback).toInt().coerceIn(0, maxW.toInt())
     }
     TrackMap(Modifier.layout { measurable, bounds ->
      val covered = panelWidth()
      val width = (bounds.maxWidth - covered).coerceAtLeast(1)
      val map = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, bounds.maxHeight))
      layout(bounds.maxWidth, bounds.maxHeight) { map.placeRelative(if (panelOnEndNow) 0 else covered, 0) }
     }, replayTrack, controlsOnStart = controlsOnStart)
     Surface(
      color = Color(0xFF15171C),
      contentColor = Color(0xFFE7E1E5),
      modifier = Modifier
       .align(panelAlign)
       .fillMaxHeight()
       .layout { measurable, constraints ->
        val w = panelWidth()
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
         VerticalPager(panelPager, Modifier.fillMaxSize()) { page ->
          when (page) {
           1 -> com.cmts.mobile.performance.CompactPerformanceResults(filesDir)
           2 -> CompactAttitude(frame)
           3 -> history?.let { ReplayCurves(it, replayIndex) { index -> onPlaying(false); onReplayIndex(index) } }
           else -> LandscapePeekChrome(
          title = title, frame = frame,
          onStart = onToggleRecording,
          pullHint = if (panelOnEndNow) "上下翻页 · 侧拉展开 ←" else "上下翻页 · 侧拉展开 →",
          onSelectGnssSource = onSelectGnssSource,
         )
          }
         }
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




















@Composable
private fun RemoteEndpointRow() {
 val context = LocalContext.current
 val store = remember { RemoteIdentityStore(context) }
 val deviceId = remember { store.deviceId }
 var running by remember { mutableStateOf(RemoteBridge.isRunning()) }
 var clients by remember { mutableStateOf(0) }
 var subscribers by remember { mutableStateOf(0) }
 var discovery by remember { mutableStateOf("") }
 var addresses by remember { mutableStateOf(emptyList<LocalIpv4.Candidate>()) }
 LaunchedEffect(Unit) {
  while (true) {
   running = RemoteBridge.isRunning()
   clients = RemoteBridge.connectedClients()
   subscribers = RemoteBridge.subscribers()
   discovery = RemoteBridge.discoveryStatus()
   addresses = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LocalIpv4.enumerateCandidates() }
   kotlinx.coroutines.delay(1000)
  }
 }
 fun copyConnectionString() {
  val ip = LocalIpv4.resolvePreferred()
  if (ip == null) {
   Toast.makeText(context, "无可用局域网 IP，请先连 Wi-Fi", Toast.LENGTH_SHORT).show()
   return
  }
  val uri = "cmts://$ip:${RemoteIdentityStore.DEFAULT_PORT}${RemoteIdentityStore.WS_PATH}"
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText("cmts-pairing-uri", uri))
  Toast.makeText(context, "已复制连接串", Toast.LENGTH_SHORT).show()
 }
 Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
  Text(
   "远程 WS ${if (running) "开" else "关"} · :${RemoteIdentityStore.DEFAULT_PORT}${RemoteIdentityStore.WS_PATH}",
   style = MaterialTheme.typography.labelSmall,
   color = Color(0xFFD4AF37),
  )
  Text(
   "${store.deviceModel} · 无需配对",
   style = MaterialTheme.typography.labelSmall,
   modifier = Modifier.clickable(onClick = { copyConnectionString() }),
  )
  com.cmts.mobile.remote.ConnectionQrButton(store)
  Text("热点状态：系统未提供可靠状态，请在系统设置确认", style = MaterialTheme.typography.labelSmall)
  Text("设备发现：$discovery · 已授权客户端 $clients · 订阅 $subscribers", style = MaterialTheme.typography.labelSmall)
  addresses.forEach { Text("本机地址 ${it.iface}: ${it.host}:8787（需电脑验证可达）", style = MaterialTheme.typography.labelSmall) }
  TextButton(onClick = {
   store.remoteEnabled = !running
   if (running) RemoteBridge.stop() else RemoteBridge.start(context)
   running = RemoteBridge.isRunning()
  }) { Text(if (running) "停止通信服务" else "启动通信服务") }
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
   TextButton(onClick = { copyConnectionString() }) { Text("复制连接串") }
  }
 }
}
@Composable
private fun ReplaySeekBar(history: ReplayData, index: Int, pause: () -> Unit, seek: (Int) -> Unit) {
 val end = history.frames.lastIndex
 var preview by remember(history.session) { mutableStateOf<Int?>(null) }
 val currentPause by rememberUpdatedState(pause)
 val currentSeek by rememberUpdatedState(seek)
 val shown = preview ?: index
 val fraction = shown.toFloat() / end.coerceAtLeast(1)
 Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
  Canvas(Modifier.fillMaxSize()
   .progressSemantics(fraction)
   .semantics {
    contentDescription = "回放进度"
    setProgress { value ->
     currentPause()
     currentSeek((value.coerceIn(0f, 1f) * end).roundToInt())
     true
    }
   }
   .pointerInput(history.session, end) {
    if (end <= 0) return@pointerInput
    val inset = 12.dp.toPx()
    fun target(x: Float) = (((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f))
     .coerceIn(0f, 1f) * end).roundToInt()
    try {
     awaitEachGesture {
      val down = awaitFirstDown(pass = PointerEventPass.Initial)
      down.consume()
      currentPause()
      preview = target(down.position.x)
      do {
       val touch = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
       val next = target(touch.position.x)
       touch.consume()
       preview = next
       if (!touch.pressed) { currentSeek(next); break }
      } while (true)
      preview = null
     }
    } finally { preview = null }
   }) {
   val inset = 12.dp.toPx()
   val y = size.height * 0.58f
   val start = Offset(inset, y)
   val finish = Offset((size.width - inset).coerceAtLeast(inset), y)
   val thumb = Offset(start.x + (finish.x - start.x) * fraction, y)
   val gold = Color(0xFFD4AF37)
   drawLine(Color(0xFF514959), start, finish, 5.dp.toPx())
   drawLine(gold, start, thumb, 5.dp.toPx())
   drawCircle(gold, 9.dp.toPx(), thumb)
  }
  if (preview != null) Text(elapsedLabel(history.frames.first().nanos, history.frames[shown].nanos),
   Modifier.align(Alignment.TopCenter), style = MaterialTheme.typography.labelSmall)
 }
}
