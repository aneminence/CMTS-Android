package com.cmts.mobile
import androidx.compose.ui.graphics.drawscope.rotate

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import com.cmts.mobile.remote.RemoteBridge

internal fun dashboardValue(value: Double?, scale: Double = 1.0, digits: Int = 1): String =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.${digits}f", it * scale).let { text -> if (text == "-0.0") "0.0" else text } } ?: "—"

internal fun isFresh(sample: Sample?, now: Long, live: Boolean): Boolean =
    live && sample != null && now >= sample.nanos && now - sample.nanos <= 3_000_000_000L

/**
 * Vehicle accel / gyro / attitude exactly as the Dashboard shows them: gravity-removed linear accel
 * (rotation vector, attitude fallback), and — when [zero] is enabled — the install-zero transform.
 * Shared by live Dashboard, replay Dashboard/attitude panes and ReplayCurves.
 */
internal fun displayVehicleAxes(zero: MountZero, samples: Map<String, Sample>): MountZeroAxes {
 val accel = samples["vehicle.accel"]?.values
 val gyro = samples["vehicle.gyro"]?.values
 val attitude = samples["vehicle.attitude"]?.values
 val rotation = samples["phone.rotation_vector"]?.values
 val linear = vehicleLinearAccel(accel, rotation, attitude)
 val zeroed = if (zero.enabled) applyEnabledMountZero(zero, accel, gyro, attitude, rotation) else null
 return MountZeroAxes(accel = zeroed?.accel ?: linear, gyro = zeroed?.gyro ?: gyro, attitude = zeroed?.attitude ?: attitude)
}

@Composable
internal fun Dashboard(replay: ReplayFrame? = null, landscape: Boolean = false) {
 if (landscape) DashboardLandscape(replay) else DashboardPortrait(replay)
}

@Composable
private fun DashboardPortrait(replay: ReplayFrame?) {
 val d = rememberDashboardModel(replay)
 Text(if(replay != null) "历史回放 · DASHBOARD" else "DASHBOARD", color = Color(0xFFD4AF37), style = MaterialTheme.typography.labelLarge)
 Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
  Gauge("航向", d.heading, "°", Modifier.weight(1f))
  Gauge("水平精度", d.hAcc, "m", Modifier.weight(1f))
 }
 Text("加速度 · 车辆坐标 · 线加速度（已去重力）", style = MaterialTheme.typography.titleSmall)
 Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  for(i in 0..2) Gauge("${"XYZ"[i]} 轴", d.accel[i], "m/s²", Modifier.weight(1f))
 }
 Text(if (d.zeroEnabled) "角速度 · 车辆坐标 · 已归零" else "角速度 · 车辆坐标", style = MaterialTheme.typography.titleSmall)
 Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  for(i in 0..2) Gauge("${"XYZ"[i]} 轴", d.gyro[i], "°/s", Modifier.weight(1f))
 }
 Text(if (d.zeroEnabled) "姿态角 · 车辆坐标 · 已归零（相对安装）" else "姿态角 · 车辆坐标", style = MaterialTheme.typography.titleSmall)
 Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  Gauge("横滚 · X", d.attitude[0], "°", Modifier.weight(1f))
  Gauge("俯仰 · Y", d.attitude[1], "°", Modifier.weight(1f))
  Gauge("偏航 · Z", d.attitude[2], if (d.zeroEnabled) "° 相对" else "° 磁北", Modifier.weight(1f))
 }
 if (replay == null) {
  ZeroButtons(d)
  Text(d.zeroHint, style = MaterialTheme.typography.bodySmall)
 }
 Text("顶部朝车头、屏幕朝上：X 前 / Y 左 / Z 上。", style = MaterialTheme.typography.bodySmall)
 Text(d.axisHint, style = MaterialTheme.typography.bodySmall)
 Text(d.attitudeHint, style = MaterialTheme.typography.bodySmall)
 GnssCard(d)
}

@Composable
private fun DashboardLandscape(replay: ReplayFrame?) {
 val d = rememberDashboardModel(replay)
 // Portrait rows become landscape columns, without page-width panels.
 Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
  Column(Modifier.width(142.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
   Text("GNSS", color = Color(0xFFD4AF37), style = MaterialTheme.typography.labelMedium)
   LandscapeGauge("速度", d.speed, "km/h", Modifier.fillMaxWidth().weight(1f))
   LandscapeGauge("航向", d.heading, "°", Modifier.fillMaxWidth().weight(1f))
   LandscapeGauge("水平精度", d.hAcc, "m", Modifier.fillMaxWidth().weight(1f))
  }
  @Composable fun axis(title: String, labels: List<String>, values: List<String>, unit: String) {
   Column(Modifier.width(112.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, color = Color(0xFFD4AF37), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    values.indices.forEach { i -> LandscapeGauge(labels[i], values[i], unit, Modifier.fillMaxWidth().weight(1f)) }
   }
  }
  axis("线加速度", listOf("X 轴","Y 轴","Z 轴"), d.accel, "m/s²")
  axis(if(d.zeroEnabled) "角速度·已归零" else "角速度", listOf("X 轴","Y 轴","Z 轴"), d.gyro, "°/s")
  axis(if(d.zeroEnabled) "姿态·已归零" else "姿态", listOf("横滚 X","俯仰 Y","偏航 Z"), d.attitude, "°")
  Column(Modifier.width(240.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
   Text("GNSS / WGS84", color = Color(0xFFD4AF37), style = MaterialTheme.typography.labelMedium)
   Text("纬度 ${d.lat}°", style = MaterialTheme.typography.bodySmall)
   Text("经度 ${d.lon}°", style = MaterialTheme.typography.bodySmall)
   Text("高度 ${d.alt} m · 精度 ${d.vAcc} m", style = MaterialTheme.typography.bodySmall)
   d.rates.forEach { (label, rate, _) -> Text("$label  $rate", style = MaterialTheme.typography.labelSmall) }
   if(replay == null) ZeroButtons(d)
  }
 }
}

@Composable
private fun LandscapeGauge(title: String, value: String, unit: String, modifier: Modifier) {
 Card(modifier) {
  Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    Text(unit, style = MaterialTheme.typography.labelSmall, color = Color(0xFFD4AF37), maxLines = 1)
   }
   BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge.copy(
     fontWeight = FontWeight.Medium,
     fontFeatureSettings = "tnum",
     lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified,
     letterSpacing = 0.sp,
    )
    val display = if (value == "-0.0") "0.0" else value
    val template = if (display.substringBefore('.').removePrefix("-").length <= 3) "-888.8"
     else display.map { if (it.isDigit()) '8' else it }.joinToString("")
    val bounds = constraints
    val numberSize = remember(template, bounds, style, measurer) {
     var low = 1
     var high = 128
     while (low < high) {
      val mid = (low + high + 1) / 2
      val size = measurer.measure(template, style.copy(fontSize = mid.sp), softWrap = false).size
      if (size.width <= bounds.maxWidth && size.height <= bounds.maxHeight) low = mid else high = mid - 1
     }
     low.sp
    }
    Text(display, style = style.copy(fontSize = numberSize), softWrap = false, maxLines = 1)
   }
  }
 }
}

@Composable
private fun SpeedModule(d: DashboardModel, modifier: Modifier) {
 Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF202127))) {
  Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
   Text("速度 · GNSS", color = Color(0xFFB9BAC2), style = MaterialTheme.typography.labelMedium)
   Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(d.speed, color = Color(0xFFD4AF37), fontSize = 48.sp, fontWeight = FontWeight.Light, maxLines = 1)
    Text("km/h", modifier = Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleSmall)
   }
   Text(d.gpsStatus, style = MaterialTheme.typography.bodySmall, maxLines = 2)
  }
 }
}

@Composable
private fun AxisModule(title: String, values: List<String>, labels: List<String>, unit: String, modifier: Modifier) {
 Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF202127))) {
  Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
   Text(title, color = Color(0xFFD4AF37), style = MaterialTheme.typography.titleSmall, maxLines = 1)
   Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    values.indices.forEach { i ->
     CompactAxisCell(labels.getOrElse(i) { "?" }, values[i], unit, Modifier.weight(1f).fillMaxHeight())
    }
   }
  }
 }
}

@Composable
private fun CompactAxisCell(label: String, value: String, unit: String, modifier: Modifier) {
 Surface(modifier, color = Color(0xFF15171C), shape = MaterialTheme.shapes.small) {
  Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
   Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9BAC2), maxLines = 1)
   Text(value, color = Color(0xFFE7E1E5), fontSize = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1)
   Text(unit, style = MaterialTheme.typography.labelSmall, color = Color(0xFFD4AF37), maxLines = 1)
  }
 }
}

@Composable
private fun GnssModule(d: DashboardModel, modifier: Modifier) {
 Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF202127))) {
  Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Text("GNSS / WGS84", color = Color(0xFFD4AF37), style = MaterialTheme.typography.titleSmall)
   Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CompactAxisCell("纬度", d.lat, "°", Modifier.weight(1f).fillMaxHeight())
    CompactAxisCell("经度", d.lon, "°", Modifier.weight(1f).fillMaxHeight())
    CompactAxisCell("高度", d.alt, "m", Modifier.weight(1f).fillMaxHeight())
    CompactAxisCell("VAcc", d.vAcc, "m", Modifier.weight(1f).fillMaxHeight())
   }
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    d.rates.forEach { (label, rate, fresh) ->
     Text("$label ${if (fresh) rate else "—"}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1)
    }
   }
   Text(d.attitudeHint, style = MaterialTheme.typography.bodySmall, maxLines = 2)
  }
 }
}

private data class DashboardModel(
 val speed: String,
 val heading: String,
 val hAcc: String,
 val lat: String,
 val lon: String,
 val alt: String,
 val vAcc: String,
 val gpsStatus: String,
 val accel: List<String>,
 val gyro: List<String>,
 val attitude: List<String>,
 val zeroEnabled: Boolean,
 val canZero: Boolean,
 val zeroHint: String,
 val axisHint: String,
 val attitudeHint: String,
 val rates: List<Triple<String, String, Boolean>>,
 val onZero: () -> Unit,
 val onReset: () -> Unit,
)

@Composable
private fun rememberDashboardModel(replay: ReplayFrame?): DashboardModel {
 val samples = replay?.samples ?: Live.samples
 val running = replay != null || Live.running || Live.serviceAlive
 var clockTick by remember { mutableIntStateOf(0) }
 LaunchedEffect(Unit) { while(true) { delay(1000); clockTick++ } }
 val now = replay?.nanos ?: clockTick.let { SystemClock.elapsedRealtimeNanos() }
 val gps = samples["phone.gps"]
 val accelRaw = samples["vehicle.accel"]
 val gyroRaw = samples["vehicle.gyro"]
 val attitudeRaw = samples["vehicle.attitude"]
 val rotationRaw = samples["phone.rotation_vector"]
 // Replay uses the session's saved install zero (metadata.json) so axes match what was shown live.
 val zero = replay?.mountZero ?: Live.mountZero
 val axes = displayVehicleAxes(zero, samples)
 val accelValues = axes.accel
 val gyroValues = axes.gyro
 val attitudeValues = axes.attitude
 val accelFresh = isFresh(accelRaw, now, running) && (
  isFresh(rotationRaw, now, running) || isFresh(attitudeRaw, now, running)
 )
 fun value(s: Sample?, values: List<Double?>?, index: Int, scale: Double = 1.0, digits: Int = 1) =
  dashboardValue(if (isFresh(s, now, running)) values?.getOrNull(index) else null, scale, digits)
 fun value(s: Sample?, index: Int, scale: Double = 1.0, digits: Int = 1) =
  value(s, s?.values, index, scale, digits)
 val canZero = replay == null && Live.serviceAlive && isFresh(gyroRaw, now, true) && isFresh(attitudeRaw, now, true)
 val zeroHint = when {
  replay != null -> ""
  !canZero -> "等待角速度与姿态有当前数据后再归零"
  zero.enabled -> "已归零：姿态/陀螺相对安装零点；加速度始终为去重力线加速度"
  else -> "未归零：姿态相对磁北；加速度为去重力线加速度（静止约 0）"
 }
 val axisHint = if (zero.enabled)
  "立装默认屏幕朝车尾，平放默认手机顶部朝前；可直线起步校准车头方向。"
 else
  "右手正向：横滚左侧抬起、俯仰车头向下、偏航向左；磁北为 0°，未归零。加速度已去重力。"
 val attitudeHint = if (!isFresh(attitudeRaw, now, running)) "姿态无当前数据 / 传感器未就绪"
 else "姿态精度状态：${attitudeRaw?.quality?.substringAfterLast("accuracy_")}（0 不可靠，1 低，2 中，3 高）；磁场干扰会影响偏航"
 val gpsStatus = if(!running) "未采集" else if(!isFresh(gps, now, true)) "等待定位 / 数据超过 3 秒未更新" else "定位更新中 · ${gps?.quality}"
 val rates = listOf(
  "GNSS" to "phone.gps",
  "加计" to "vehicle.accel",
  "陀螺" to "vehicle.gyro",
  "姿态" to "vehicle.attitude",
 ).map { (label, key) ->
  val s = samples[key]
  val fresh = isFresh(s, now, running)
  val hz = dashboardValue(if (replay == null && fresh) Live.rates[key] else null, digits = 1)
  Triple("$label", "${hz}Hz", fresh)
 }
 return DashboardModel(
  speed = value(gps, 3, 3.6),
  heading = value(gps, 4),
  hAcc = value(gps, 5),
  lat = value(gps, 0, digits = 6),
  lon = value(gps, 1, digits = 6),
  alt = value(gps, 2),
  vAcc = value(gps, 6),
  gpsStatus = gpsStatus,
  accel = List(3) { i -> dashboardValue(if (accelFresh) accelValues?.getOrNull(i) else null) },
  gyro = List(3) { i -> value(gyroRaw, gyroValues, i, 180.0 / Math.PI) },
  attitude = List(3) { i -> value(attitudeRaw, attitudeValues, i) },
  zeroEnabled = zero.enabled,
  canZero = canZero,
  zeroHint = zeroHint,
  axisHint = axisHint,
  attitudeHint = attitudeHint,
  rates = rates,
  onZero = {
   val snapped = captureMountZero(accelRaw, gyroRaw, attitudeRaw, SystemClock.elapsedRealtimeNanos())
   if (snapped != null) {
    LaunchCalibration.cancel()
    Live.mountZero = snapped
    RemoteBridge.notifyStreamReset(
     reason = "mount_zero",
     mountZeroEnabled = true,
     zeroedAtElapsedNanos = snapped.zeroedAtElapsedNanos,
    )
   }
  },
  onReset = {
   LaunchCalibration.cancel()
   Live.mountZero = MountZero()
   RemoteBridge.notifyStreamReset(
    reason = "mount_zero_clear",
    mountZeroEnabled = false,
    zeroedAtElapsedNanos = null,
   )
  },
 )
}

@Composable
private fun ZeroButtons(d: DashboardModel) {
 Column {
 if(d.zeroEnabled && d.canZero) {
  OutlinedButton(onClick={ LaunchCalibration.arm(SystemClock.elapsedRealtimeNanos(), Live.samples["phone.gps"]) }) { Text("直线起步标定（可选）") }
  if(LaunchCalibration.status.isNotEmpty()) Text(LaunchCalibration.status)
 }

 Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
  Button(enabled = d.canZero, onClick = d.onZero, modifier = Modifier.weight(1f)) { Text("归零") }
  OutlinedButton(enabled = d.zeroEnabled, onClick = d.onReset, modifier = Modifier.weight(1f)) { Text("复位") }
 }
}

}
@Composable
private fun GnssCard(d: DashboardModel) {
 Card(Modifier.fillMaxWidth()) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   Text("GNSS / WGS84", color = Color(0xFFD4AF37))
   Text("纬度  ${d.lat} °")
   Text("经度  ${d.lon} °")
   Text("高度  ${d.alt} m    垂直精度  ${d.vAcc} m")
   for ((label, rate, fresh) in d.rates) {
    Text("$label  ·  ${if (fresh) "更新中" else "无当前数据"}  ·  $rate")
   }
   Text("实际相邻样本频率；超过 3 秒无更新显示 —", style = MaterialTheme.typography.bodySmall)
  }
 }
}

@Composable
internal fun Gauge(title: String, value: String, unit: String, modifier: Modifier) {
 Card(modifier) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(title, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1)
    Text(unit, style = MaterialTheme.typography.labelSmall, color = Color(0xFFD4AF37), maxLines = 1)
   }
   Text(value, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
    fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1)
  }
 }
}
@Composable
internal fun AttitudeZeroAction() {
 val model = rememberDashboardModel(null)
 TextButton(onClick = model.onZero, enabled = model.canZero) { Text("归零") }
}

@Composable
internal fun AttitudePane(frame: ReplayFrame?, landscape: Boolean = false) {
 val model = rememberDashboardModel(frame)
 val angles = model.attitude
 Column(Modifier.fillMaxSize()) {
 if (frame == null && !landscape) {
  TextButton(onClick = model.onZero, enabled = model.canZero, modifier = Modifier.align(Alignment.End).heightIn(min = 32.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("归零") }
 }
 if (landscape) {
  Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
   for (side in listOf(true, false)) {
    val reading = angles[if (side) 1 else 0]
    Card(Modifier.weight(1f).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = Color(0xFF202227))) {
     Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(if (side) "俯仰 · 左视图" else "侧倾 · 后视图", style = MaterialTheme.typography.labelLarge)
      AttitudeDial(side, reading, Modifier.weight(1f).fillMaxWidth())
      Text(if (reading.toFloatOrNull() == null) "—" else "$reading°", color = Color(0xFFD4AF37), fontSize = 23.sp)
      if (side) GradeReading(reading)
     }
    }
   }
  }
 } else
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
  verticalArrangement = Arrangement.spacedBy(12.dp)) {
  AttitudeCard(true, angles[1])
  AttitudeCard(false, angles[0])
 }
}

}
@Composable
private fun AttitudeCard(side: Boolean, reading: String) {
 val gold = Color(0xFFD4AF37)
 val angle = reading.toFloatOrNull()
 Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF202227))) {
  Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
   AttitudeDial(side, reading, Modifier.weight(1f).height(136.dp))
   Column(Modifier.padding(start=12.dp).widthIn(min=82.dp)) {
    Text(if(side) "俯仰" else "侧倾", style=MaterialTheme.typography.titleMedium)
    Text(if(side) "左视图" else "后视图", color=Color(0xFFB9B7B0), style=MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(8.dp))
    Text(if(angle==null) "—" else "$reading°", fontSize=27.sp, color=gold)
    if (side) GradeReading(reading)
   }
  }
 }
}



@Composable
private fun AttitudeDial(side: Boolean, reading: String, modifier: Modifier) {
 val gold = Color(0xFFD4AF37)
 val angle = reading.toFloatOrNull()
 Canvas(modifier) {
  val unit = minOf(size.width, size.height) / 116f
  withTransform({
   translate(center.x, center.y)
   scale(unit, unit, Offset.Zero)
  }) {
   val guide = Color(0xFF525351)
   drawCircle(guide, 53f, Offset.Zero, style = Stroke(.7f))
   for (tick in -90..90 step 15) rotate(tick.toFloat(), Offset.Zero) {
    for (sign in listOf(-1,1)) drawLine(guide, Offset(sign*48f,0f), Offset(sign*53f,0f), 1f)
   }
   drawLine(guide, Offset(-55f,0f), Offset(55f,0f), 1f)
   if (angle != null) rotate(if (side) -angle else angle, Offset.Zero) {
    val body = Path().apply {
     if (side) {
      moveTo(-43f,5f); quadraticTo(-45f,-4f,-38f,-7f); lineTo(-22f,-10f)
      lineTo(-12f,-21f); quadraticTo(1f,-24f,16f,-20f); lineTo(27f,-10f)
      lineTo(39f,-8f); quadraticTo(44f,-7f,43f,5f)
     } else {
      moveTo(-28f,9f); quadraticTo(-32f,-2f,-27f,-11f); lineTo(-21f,-23f)
      quadraticTo(0f,-27f,21f,-23f); lineTo(27f,-11f); quadraticTo(32f,-2f,28f,9f)
     }
     close()
    }
    drawPath(body, Color(0xFF4B483D))
    drawPath(body, Color(0xFFE3CB8F), style = Stroke(1.5f))
    val window = Path().apply {
     moveTo(if(side) -18f else -23f,-10f)
     lineTo(if(side) -10f else -18f,-19f)
     lineTo(if(side) 14f else 18f,-19f)
     lineTo(23f,-10f); close()
    }
    drawPath(window, Color(0xFF15191E))
    if(side) {
     drawLine(gold,Offset(2f,-20f),Offset(2f,-9f),1.4f)
     for(x in listOf(-27f,27f)) {
      drawCircle(Color(0xFF15171B),7f,Offset(x,5f))
      drawCircle(gold,4f,Offset(x,5f),style=Stroke(1.5f))
     }
    } else {
     for(x in listOf(-25f,14f)) drawRect(Color(0xFFEBA18A),Offset(x,-5f),Size(11f,3f))
     drawRect(Color(0xFFBDB5A2),Offset(-7f,1f),Size(14f,4f))
    }
   }
  }
 }
}





@Composable
internal fun CompactAttitude(frame: ReplayFrame?, horizontal: Boolean = false) {
 val angles = rememberDashboardModel(frame).attitude
 if (horizontal) {
  Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
   for (side in listOf(true, false)) {
    val angle = angles[if (side) 1 else 0]
    Card(Modifier.weight(1f).fillMaxHeight()) {
     Column(Modifier.fillMaxSize().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(if (side) "俯仰 · 左视" else "侧倾 · 后视")
      AttitudeDial(side, angle, Modifier.weight(1f).fillMaxWidth())
      Text(if (angle == "—") angle else "$angle°")
     if (side) GradeReading(angle)
     }
    }
   }
  }
  return
 }
 Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
  Text("车辆姿态", color = Color(0xFFD4AF37))
  for (side in listOf(true,false)) {
   val angle = angles[if(side) 1 else 0]
   Card(Modifier.fillMaxWidth().weight(1f)) {
    Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
     Text(if(side) "俯仰 · 左视" else "侧倾 · 后视", style = MaterialTheme.typography.labelSmall)
     AttitudeDial(side, angle, Modifier.weight(1f).fillMaxWidth())
     Text(if(angle == "—") angle else "$angle°")
     if (side) GradeReading(angle)
    }
   }
  }
 }
}





internal fun gradeLabel(pitch: String): String {
 val degrees = pitch.toDoubleOrNull()?.takeIf { kotlin.math.abs(it) < 90 } ?: return "—"
 val percent = -100.0 * kotlin.math.tan(Math.toRadians(degrees))
 return if (kotlin.math.abs(percent) < 0.05) "0.0%" else String.format(Locale.US, "%+.1f%%", percent)
}

@Composable
private fun GradeReading(pitch: String) {
 Text("坡度 ${gradeLabel(pitch)}", style = MaterialTheme.typography.labelMedium)
}
