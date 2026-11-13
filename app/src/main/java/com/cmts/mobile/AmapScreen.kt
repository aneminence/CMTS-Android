package com.cmts.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.*
import com.amap.api.maps.model.*

@Composable
fun TrackMap(modifier: Modifier = Modifier, offlineTrack: List<TrackPoint>? = null, controlsOnStart: Boolean = false) {
 val context = LocalContext.current
 val prefs = remember { context.getSharedPreferences("map", 0) }
 var rgb by remember { mutableStateOf(prefs.getString("trackRgb", "D4AF37") ?: "D4AF37") }
 val trackColor = android.graphics.Color.parseColor("#" + rgb)
 var editing by remember { mutableStateOf(false) }
 if (editing) {
  TrackColorPicker(trackColor, onDismiss = { editing = false }) { selected ->
   rgb = "%06X".format(selected and 0xFFFFFF)
   prefs.edit().putString("trackRgb", rgb).apply()
   editing = false
  }
 }
 var amap by rememberSaveable { mutableStateOf(prefs.getBoolean("amap", false)) }
 var consent by remember { mutableStateOf(prefs.getBoolean("amapConsent", false)) }
 var prompt by remember { mutableStateOf(false) }
 Box(modifier) {
  if(amap && consent) AmapTrackMap(Modifier.fillMaxSize(), offlineTrack, controlsOnStart, trackColor) else GoogleTrackMap(Modifier.fillMaxSize(), offlineTrack, controlsOnStart, androidx.compose.ui.graphics.Color(trackColor))
  val mapBtnAlign = if (controlsOnStart) Alignment.TopStart else Alignment.TopEnd
  MapControl(onClick = { editing = true }, modifier = Modifier.align(mapBtnAlign).statusBarsPadding().padding(top = 64.dp, start = 12.dp, end = 12.dp)) { Text("轨迹色") }
  val mapBtnPad = if (controlsOnStart) Modifier.padding(top = 116.dp, start = 12.dp) else Modifier.padding(top = 116.dp, end = 12.dp)
  MapControl(onClick = {
   if(!amap && !consent) prompt = true else { amap = !amap; prefs.edit().putBoolean("amap", amap).apply() }
  }, modifier = Modifier.align(mapBtnAlign).statusBarsPadding().then(mapBtnPad)) { Text(if(amap) "卫星" else "高德") }
 }
 if(prompt) AlertDialog(onDismissRequest = { prompt = false }, title = { Text("使用高德地图") },
  text = { Column {
   Text("高德地图 SDK 将联网加载地图，并按其隐私政策处理设备及网络信息；应用会将轨迹坐标交给 SDK 用于地图显示。原始记录和导出坐标保持 WGS84。")
   TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lbs.amap.com/pages/privacy/"))) }) { Text("查看高德隐私政策") }
  } }, confirmButton = { TextButton(onClick = { consent = true; amap = true; prompt = false; prefs.edit().putBoolean("amapConsent", true).putBoolean("amap", true).apply() }) { Text("同意并使用") } },
  dismissButton = { TextButton(onClick = { prompt = false }) { Text("取消") } })
}

@Composable
private fun AmapTrackMap(modifier: Modifier, offlineTrack: List<TrackPoint>?, controlsOnStart: Boolean, trackColor: Int) {
 val context = LocalContext.current
 val owner = LocalLifecycleOwner.current
 val view = remember {
  MapsInitializer.updatePrivacyShow(context, true, true)
  MapsInitializer.updatePrivacyAgree(context, true)
  MapView(context).apply { onCreate(null) }
 }
 var follow by remember { mutableStateOf(true) }
 val track = offlineTrack ?: Live.track
 DisposableEffect(view, owner) {
  val observer = LifecycleEventObserver { _, event -> when(event) {
   Lifecycle.Event.ON_RESUME -> view.onResume()
   Lifecycle.Event.ON_PAUSE -> view.onPause()
   else -> Unit
  } }
  owner.lifecycle.addObserver(observer)
  if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
  view.map.setOnMapTouchListener { follow = false }
  onDispose { owner.lifecycle.removeObserver(observer); view.onPause(); view.onDestroy() }
 }
 val converted = remember(view) { TrackCoordinateCache { point ->
  CoordinateConverter(context).from(CoordinateConverter.CoordType.GPS).coord(LatLng(point.latitude,point.longitude)).convert()
 } }
 val points = remember(track, view) { converted.get(track) }
 val overlays = remember(view) { arrayOfNulls<Any>(2) }
 LaunchedEffect(track) {
  val line = overlays[0] as? Polyline
  if (points.size > 1) {
   if (line == null) overlays[0] = view.map.addPolyline(PolylineOptions().addAll(points).color(trackColor).width(5f))
   else line.points = points
  } else { line?.remove(); overlays[0] = null }
  if (points.isEmpty()) { (overlays[1] as? Marker)?.remove(); overlays[1] = null }
  points.lastOrNull()?.let {
   val marker = overlays[1] as? Marker
   if (marker == null) overlays[1] = view.map.addMarker(MarkerOptions().position(it).title("GNSS"))
   else marker.position = it
  }
 }
 LaunchedEffect(trackColor, track) { (overlays[0] as? Polyline)?.color = trackColor }
 LaunchedEffect(points.lastOrNull(), follow) {
  if (follow) points.lastOrNull()?.let { view.map.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 16f)) }
 }
 Box(modifier) {
  AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
  MapControl(onClick = { follow = !follow }, modifier = Modifier.align(if(controlsOnStart) Alignment.TopStart else Alignment.TopEnd).statusBarsPadding().padding(top = 168.dp, start = 12.dp, end = 12.dp)) { Text(if(follow) "已锁定" else "锁定定位") }
 }
}


@Composable
internal fun MapControl(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
 FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
  shape = MaterialTheme.shapes.medium,
  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
  colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface), content = content)
}



private val trackSwatches = listOf(
 "香槟金" to 0xD4AF37, "橙色" to 0xFF922B, "红色" to 0xF44336, "粉色" to 0xFF77AA,
 "青色" to 0x26D9D0, "天蓝" to 0x42A5FF, "紫色" to 0xAF87FF, "白色" to 0xFFFFFF,
 "亮绿" to 0x70DB49, "黄色" to 0xFFEB3B, "深蓝" to 0x244EB8, "黑色" to 0x121212,
)

@Composable
private fun TrackColorPicker(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
 var rgb by rememberSaveable { mutableIntStateOf(initial) }
 var custom by rememberSaveable { mutableStateOf(false) }
 AlertDialog(onDismissRequest = onDismiss, title = { Text("轨迹颜色") }, text = {
  Column(Modifier.verticalScroll(rememberScrollState())) {
   Canvas(Modifier.fillMaxWidth().height(24.dp)) {
    drawLine(Color(rgb), androidx.compose.ui.geometry.Offset(0f,center.y),
     androidx.compose.ui.geometry.Offset(size.width,center.y), 5.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
   }
   trackSwatches.chunked(4).forEach { row ->
    Row(Modifier.fillMaxWidth()) {
     row.forEach { (name, value) ->
      val color = value or 0xFF000000.toInt()
      Box(Modifier.weight(1f).height(48.dp).selectable(rgb == color, onClick = { rgb = color })
       .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
       Box(Modifier.size(36.dp).then(if (rgb == color) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
        .padding(4.dp).background(Color(color), CircleShape))
      }
     }
    }
   }
   TextButton(onClick = { custom = !custom }) { Text(if (custom) "收起微调" else "微调颜色") }
   if (custom) listOf("红" to 16, "绿" to 8, "蓝" to 0).forEach { (name, shift) ->
    Row(verticalAlignment = Alignment.CenterVertically) {
     Text(name)
     Slider(((rgb ushr shift) and 255).toFloat(), onValueChange = {
      rgb = (rgb and (255 shl shift).inv()) or (it.toInt() shl shift)
     }, modifier = Modifier.weight(1f).semantics { contentDescription = name }, valueRange = 0f..255f)
    }
   }
  }
 }, confirmButton = { TextButton(onClick = { onSave(rgb) }) { Text("保存") } },
 dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
