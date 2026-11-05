package com.cmts.mobile

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
 var amap by rememberSaveable { mutableStateOf(prefs.getBoolean("amap", false)) }
 var consent by remember { mutableStateOf(prefs.getBoolean("amapConsent", false)) }
 var prompt by remember { mutableStateOf(false) }
 Box(modifier) {
  if(amap && consent) AmapTrackMap(Modifier.fillMaxSize(), offlineTrack, controlsOnStart) else GoogleTrackMap(Modifier.fillMaxSize(), offlineTrack, controlsOnStart)
  val mapBtnAlign = if (controlsOnStart) Alignment.TopStart else Alignment.TopEnd
  val mapBtnPad = if (controlsOnStart) Modifier.padding(top = 64.dp, start = 12.dp) else Modifier.padding(top = 64.dp, end = 12.dp)
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
private fun AmapTrackMap(modifier: Modifier, offlineTrack: List<TrackPoint>?, controlsOnStart: Boolean) {
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
 LaunchedEffect(track, follow) {
  val points = track.map { CoordinateConverter(context).from(CoordinateConverter.CoordType.GPS).coord(LatLng(it.latitude,it.longitude)).convert() }
  view.map.clear()
  if(points.size > 1) view.map.addPolyline(PolylineOptions().addAll(points).color(0xFFD4AF37.toInt()).width(5f))
  points.lastOrNull()?.let {
   view.map.addMarker(MarkerOptions().position(it).title("GNSS"))
   if(follow) view.map.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 16f))
  }
 }
 Box(modifier) {
  AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
  MapControl(onClick = { follow = !follow }, modifier = Modifier.align(if(controlsOnStart) Alignment.TopStart else Alignment.TopEnd).statusBarsPadding().padding(top = 116.dp, start = 12.dp, end = 12.dp)) { Text(if(follow) "已锁定" else "锁定定位") }
 }
}


@Composable
internal fun MapControl(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
 FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
  shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
  colors = ButtonDefaults.filledTonalButtonColors(containerColor = androidx.compose.ui.graphics.Color(0xE615171C), contentColor = androidx.compose.ui.graphics.Color(0xFFD4AF37)), content = content)
}
