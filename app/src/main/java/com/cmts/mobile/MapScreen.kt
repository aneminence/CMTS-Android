package com.cmts.mobile

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

// Provider-specific configuration stays outside sensor/storage code. WGS84 is never modified.
internal object SatelliteTiles {
 const val attribution = "© Google"
 fun url(z: Int, x: Int, y: Int) = "https://mt0.google.com/vt/lyrs=s&x=$x&y=$y&z=$z"
}

@Composable
fun TrackMap(modifier: Modifier = Modifier, offlineTrack: List<TrackPoint>? = null) {
 var center by remember { mutableStateOf(WorldPoint(0.5, 0.5)) }
 var zoom by remember { mutableDoubleStateOf(2.0) }
 var follow by remember { mutableStateOf(true) }
 var positioned by remember { mutableStateOf(false) }
 var dimensions by remember { mutableStateOf(IntSize.Zero) }
 var tiles by remember { mutableStateOf(mapOf<String, ImageBitmap>()) }
 var failed by remember { mutableStateOf(false) }
 var retry by remember { mutableIntStateOf(0) }
 val track = offlineTrack ?: Live.track
 val latest = track.lastOrNull()
 LaunchedEffect(latest, follow) {
  if (latest != null && follow) {
   center = project(latest.latitude, latest.longitude)
   if (!positioned) { zoom = 16.0; positioned = true }
  }
 }
 val z = floor(zoom).toInt().coerceIn(1, 19)
 val count = 1 shl z
 val tileSize = 256.0 * 2.0.pow(zoom - z)
 val worldSize = tileSize * count
 val left = center.x * worldSize - dimensions.width / 2.0
 val top = center.y * worldSize - dimensions.height / 2.0
 val visible = remember(z, tileSize, center, dimensions) {
  buildList {
   if (dimensions.width > 0 && dimensions.height > 0) {
    for (y in floor(top / tileSize).toInt()..floor((top + dimensions.height) / tileSize).toInt()) {
     if (y !in 0 until count) continue
     for (x in floor(left / tileSize).toInt()..floor((left + dimensions.width) / tileSize).toInt()) {
      add(Triple(x, y, "$z/${Math.floorMod(x, count)}/$y"))
     }
    }
   }
  }
 }
 val keys = visible.map { it.third }.toSet()
 LaunchedEffect(keys, retry) {
  failed = false
  tiles = tiles.filterKeys { it in keys }
  for (key in keys) {
   if (tiles.containsKey(key)) continue
   val bitmap = withContext(Dispatchers.IO) {
    try {
     // Keep only visible tiles in memory. No offline Google imagery storage.
     val bytes = run {
      val parts = key.split('/').map { it.toInt() }
      val connection = URL(SatelliteTiles.url(parts[0], parts[1], parts[2])).openConnection() as HttpURLConnection
      try {
       connection.setRequestProperty("User-Agent", "CMTS-Mobile/0.1.0 (Android; com.cmts.mobile)")
       connection.connectTimeout = 5000; connection.readTimeout = 5000
       check(connection.responseCode == 200)
       connection.inputStream.use { it.readBytes() }.also {
        check(BitmapFactory.decodeByteArray(it, 0, it.size) != null)
       }
      } finally { connection.disconnect() }
     }
     BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Exception) {
     android.util.Log.w("CMTS.Map", "Tile request failed: ${e.javaClass.simpleName}: ${e.message}")
     null
    }
   }
   if (bitmap != null) tiles = tiles + (key to bitmap) else failed = true
  }
 }
 Box(modifier.clipToBounds().background(Color(0xFF20272D))) {
  Canvas(Modifier.fillMaxSize().onSizeChanged { dimensions = it }.pointerInput(Unit) {
   detectTransformGestures { _, pan, scale, _ ->
    follow = false
    val size = 256 * 2.0.pow(zoom)
    center = WorldPoint(wrapX(center.x - pan.x / size), (center.y - pan.y / size).coerceIn(0.0, 1.0))
    zoom = (zoom + ln(scale.toDouble()) / ln(2.0)).coerceIn(1.0, 19.0)
   }
  }) {
   for ((x, y, key) in visible) {
    val origin = IntOffset((x * tileSize - left).roundToInt(), (y * tileSize - top).roundToInt())
    tiles[key]?.let { drawImage(it, dstOffset = origin, dstSize = IntSize(ceil(tileSize).toInt() + 1, ceil(tileSize).toInt() + 1)) }
   }
   fun screen(p: TrackPoint): Offset {
    val w = project(p.latitude, p.longitude)
    return Offset((wrappedDelta(w.x - center.x) * worldSize + size.width / 2).toFloat(), ((w.y - center.y) * worldSize + size.height / 2).toFloat())
   }
   track.zipWithNext().forEach { (a, b) ->
    if (b.nanos - a.nanos in 1..10_000_000_000L) drawLine(Color(0xFFD4AF37), screen(a), screen(b), 5f)
   }
   latest?.let {
    val p = screen(it)
    val accuracy = if (offlineTrack == null) Live.samples["phone.gps"]?.values?.getOrNull(5) else null
    if (accuracy != null) {
     val radius = (accuracy * worldSize / (40075016.686 * cos(Math.toRadians(it.latitude)))).toFloat().coerceIn(0f, 2000f)
     drawCircle(Color(0x334EA5FF), radius, p)
     drawCircle(Color(0xFF4EA5FF), radius, p, style = Stroke(1f))
    }
    drawCircle(Color.White, 12f, p); drawCircle(Color(0xFFD4AF37), 8f, p)
   }
  }
  Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) {
   Surface(color = Color(0xDD15171C), shape = MaterialTheme.shapes.medium) {
    Column(Modifier.padding(10.dp)) {
     Text("CMTS · 地图", color = Color(0xFFD4AF37))
     Text(SatelliteTiles.attribution, style = MaterialTheme.typography.labelSmall)
     Text(if (offlineTrack != null) "离线 VBO · WGS84" else if (Live.emulator) "模拟器数据 · 非真机验收" else "手机 GNSS · WGS84", style = MaterialTheme.typography.labelSmall)
     if (latest == null) Text("开始记录后显示定位与轨迹", style = MaterialTheme.typography.labelSmall)
     if (failed) Text("底图加载失败，采集不受影响", color = Color(0xFFFFC07A), style = MaterialTheme.typography.labelSmall)
     if (failed) TextButton(onClick = { retry++ }) { Text("重试底图") }
    }
   }
  }
  Column(Modifier.align(Alignment.CenterEnd).padding(8.dp)) {
   FilledTonalButton(onClick = { zoom = (zoom + 1).coerceAtMost(19.0) }) { Text("＋") }
   FilledTonalButton(onClick = { zoom = (zoom - 1).coerceAtLeast(1.0) }) { Text("－") }
   FilledTonalButton(onClick = { follow = true }, enabled = latest != null) { Text(if (follow) "跟随中" else "回定位") }
  }
 }
}
