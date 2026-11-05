package com.cmts.mobile

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

internal fun dashboardValue(value: Double?, scale: Double = 1.0, digits: Int = 1): String =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.${digits}f", it * scale) } ?: "—"

internal fun isFresh(sample: Sample?, now: Long, live: Boolean): Boolean =
    live && sample != null && now >= sample.nanos && now - sample.nanos <= 3_000_000_000L

@Composable
fun Dashboard(replay: ReplayFrame? = null, landscape: Boolean = false) {
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
   Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
    Text(value, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
 val zero = if (replay == null) Live.mountZero else MountZero()
 val accelValues = vehicleLinearAccel(accelRaw?.values, rotationRaw?.values, attitudeRaw?.values)
 val gyroValues = if (zero.enabled) applyMountZeroLinear(gyroRaw?.values ?: emptyList(), zero.gyro) else gyroRaw?.values
 val attitudeValues = if (zero.enabled) applyMountZeroAttitude(attitudeRaw?.values ?: emptyList(), zero.attitude) else attitudeRaw?.values
 val accelFresh = isFresh(accelRaw, now, running) && (
  isFresh(rotationRaw, now, running) || isFresh(attitudeRaw, now, running)
 )
 fun value(s: Sample?, values: List<Double?>?, index: Int, scale: Double = 1.0, digits: Int = 1) =
  dashboardValue(if (isFresh(s, now, running)) values?.getOrNull(index) else null, scale, digits)
 fun value(s: Sample?, index: Int, scale: Double = 1.0, digits: Int = 1) =
  value(s, s?.values, index, scale, digits)
 val canZero = replay == null && Live.running && isFresh(gyroRaw, now, true) && isFresh(attitudeRaw, now, true)
 val zeroHint = when {
  replay != null -> ""
  !Live.running -> "开始记录后可归零；归零作用于姿态/陀螺显示，CSV 仍写原始值"
  !canZero -> "等待角速度与姿态有当前数据后再归零"
  zero.enabled -> "已归零：姿态/陀螺相对安装零点；加速度始终为去重力线加速度"
  else -> "未归零：姿态相对磁北；加速度为去重力线加速度（静止约 0）"
 }
 val axisHint = if (zero.enabled)
  "右手正向不变；姿态/陀螺为相对归零（欧拉角简单相减）。加速度用旋转矢量估重力后扣除。"
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
  accel = List(3) { i -> dashboardValue(if (accelFresh) accelValues?.getOrNull(i) else null, digits = 2) },
  gyro = List(3) { i -> value(gyroRaw, gyroValues, i, 180.0 / Math.PI, 2) },
  attitude = List(3) { i -> value(attitudeRaw, attitudeValues, i) },
  zeroEnabled = zero.enabled,
  canZero = canZero,
  zeroHint = zeroHint,
  axisHint = axisHint,
  attitudeHint = attitudeHint,
  rates = rates,
  onZero = {
   val snapped = captureMountZero(accelRaw, gyroRaw, attitudeRaw, SystemClock.elapsedRealtimeNanos())
   if (snapped != null) Live.mountZero = snapped
  },
  onReset = { Live.mountZero = MountZero() },
 )
}

@Composable
private fun ZeroButtons(d: DashboardModel) {
 Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
  Button(enabled = d.canZero, onClick = d.onZero, modifier = Modifier.weight(1f)) { Text("归零") }
  OutlinedButton(enabled = d.zeroEnabled, onClick = d.onReset, modifier = Modifier.weight(1f)) { Text("复位") }
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
