package com.cmts.mobile

import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
internal fun Dashboard(replay: ReplayFrame? = null, landscape: Boolean = false, landscapeSource: (@Composable () -> Unit)? = null, supplementaryReadings: @Composable () -> Unit = {}) {
 if (landscape) {
  DashboardLandscape(replay, landscapeSource)
 } else {
  DashboardPortrait(replay, supplementaryReadings)
  Spacer(Modifier.height(12.dp))
  EstimatePanel(replay)
 }
}

@Composable
private fun EstimatePanel(replay: ReplayFrame?, landscape: Boolean = false, source: (@Composable () -> Unit)? = null) {
 var show by rememberSaveable { mutableStateOf(false) }
 if (landscape) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
   if (source != null) {
    Box(Modifier.width(176.dp)) { source() }
    Spacer(Modifier.weight(1f))
   } else Text(if (replay != null) "历史仪表" else "实时仪表", style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.weight(1f))
   TextButton(onClick = { show = true }) { Text("姿态估算 · 实验对照") }
  }
  if (show) AlertDialog(
   onDismissRequest = { show = false },
   title = { Text("车辆姿态估算 · 实验对照") },
   text = { Column(Modifier.verticalScroll(rememberScrollState())) { EstimateDetails(replay) } },
   confirmButton = { TextButton(onClick = { show = false }) { Text("关闭") } },
  )
 } else {
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
   Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
     Column(Modifier.weight(1f)) {
      Text("车辆姿态估算", style = MaterialTheme.typography.titleSmall)
      Text("实验对照 · 独立于系统姿态", style = MaterialTheme.typography.bodySmall,
       color = MaterialTheme.colorScheme.onSurfaceVariant)
     }
     Switch(checked = show, onCheckedChange = { show = it })
    }
    if (show) EstimateDetails(replay)
   }
  }
 }
}

@Composable
private fun EstimateDetails(replay: ReplayFrame?) {
 var tick by remember { mutableIntStateOf(0) }
 LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
 val samples = replay?.samples ?: Live.samples
 val s = samples["estimate.attitude"]
 val status = samples["estimate.status"]
 val fresh = isFresh(s, replay?.nanos ?: tick.let { SystemClock.elapsedRealtimeNanos() }, replay != null || Live.serviceAlive)
 Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
  Text("Roll ${dashboardValue(if (fresh) s?.values?.getOrNull(0) else null)}° / Pitch ${dashboardValue(if (fresh) s?.values?.getOrNull(1) else null)}°",
   style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
  Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
   Text(if (!fresh && s != null) "降级：姿态数据已过期" else status?.quality ?: "初始化：等待停车校准",
    Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSecondaryContainer)
  }
  Text("加计年龄 ${dashboardValue(status?.values?.getOrNull(1))}s；GNSS年龄 ${dashboardValue(status?.values?.getOrNull(3))}s",
   style = MaterialTheme.typography.bodySmall)
  Text("加速度权重 ${dashboardValue(status?.values?.getOrNull(0), digits = 2)}；距可信校正 ${dashboardValue(status?.values?.getOrNull(2))}s",
   style = MaterialTheme.typography.bodySmall)
  Text("固定安装：屏幕朝上、顶部朝车头。Yaw无长期绝对参考。保留原系统姿态；原归零不改变本估算。",
   style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  if (replay == null) OutlinedButton(enabled = Live.serviceAlive, onClick = { Live.attitudeCalibrationRequest++ }, modifier = Modifier.fillMaxWidth()) {
   Text("确认已停车：校准陀螺零偏与初始化")
  }
 }
}

@Composable
private fun DashboardPortrait(replay: ReplayFrame?, supplementaryReadings: @Composable () -> Unit) {
 val d = rememberDashboardModel(replay)
 Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
  SpeedModule(d, Modifier.fillMaxWidth())
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
   Gauge("航向", d.heading, "°", Modifier.weight(1f))
   Gauge("水平精度", d.hAcc, "m", Modifier.weight(1f))
  }
  supplementaryReadings()
  AxisGroup("线加速度", "车辆坐标 · 已去重力", listOf("X · 向前", "Y · 向左", "Z · 向上"), d.accel, "m/s²")
  AxisGroup("角速度", if (d.zeroEnabled) "车辆坐标 · 已归零" else "车辆坐标", listOf("X 轴", "Y 轴", "Z 轴"), d.gyro, "°/s")
  AxisGroup("系统姿态", if (d.zeroEnabled) "相对安装 · 已归零" else "车辆坐标 · 未归零",
   listOf("横滚 X", "俯仰 Y", "偏航 Z"), d.attitude, "°")
  if (replay == null) {
   ZeroButtons(d)
   Text(d.zeroHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
   Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("坐标与姿态说明", style = MaterialTheme.typography.labelLarge)
    Text("顶部朝车头、屏幕朝上：X 前 / Y 左 / Z 上。", style = MaterialTheme.typography.bodySmall)
    Text(if (d.zeroEnabled) "偏航为相对角度" else "偏航以磁北为参考", style = MaterialTheme.typography.bodySmall)
    Text(d.axisHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(d.attitudeHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  GnssCard(d)
 }
}

@Composable
private fun AxisGroup(title: String, subtitle: String, labels: List<String>, values: List<String>, unit: String) {
 Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
   Text(title, style = MaterialTheme.typography.titleSmall)
   Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.weight(1f))
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
   values.indices.forEach { i -> Gauge(labels[i], values[i], unit, Modifier.weight(1f)) }
  }
 }
}

@Composable
private fun DashboardLandscape(replay: ReplayFrame?, source: (@Composable () -> Unit)?) {
 val d = rememberDashboardModel(replay)
 BoxWithConstraints(Modifier.fillMaxSize()) {
 val columnScale = ((maxWidth - 25.dp).value / 510f).coerceIn(0f, 1f)
 // Fit the four measurement columns in the viewport; details remain available by scrolling.
 Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  Column(Modifier.width(150.dp * columnScale).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
    if (source != null) source() else Text("历史仪表", style = MaterialTheme.typography.titleSmall)
   }
   Text("GNSS", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
   LandscapeGauge("速度", d.speed, "km/h", Modifier.fillMaxWidth().weight(1f))
   LandscapeGauge("高度", d.alt, "m", Modifier.fillMaxWidth().weight(1f))
   LandscapeGauge("可见/定位", d.satellites, "颗", Modifier.fillMaxWidth().weight(1f))
  }
  @Composable fun axis(title: String, labels: List<String>, values: List<String>, unit: String, extraTitle: String, extraValue: String, extraUnit: String) {
   Column(Modifier.width(120.dp * columnScale).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    LandscapeGauge(extraTitle, extraValue, extraUnit, Modifier.fillMaxWidth().height(60.dp))
    Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    values.indices.forEach { i -> LandscapeGauge(labels[i], values[i], unit, Modifier.fillMaxWidth().weight(1f)) }
   }
  }
  axis("线加速度", listOf("X 轴", "Y 轴", "Z 轴"), d.accel, "m/s²", "气压", d.pressure, "mbar")
  axis(if (d.zeroEnabled) "角速度·已归零" else "角速度", listOf("X 轴", "Y 轴", "Z 轴"), d.gyro, "°/s", "水平精度", d.hAcc, "m")
  axis(if (d.zeroEnabled) "姿态·已归零" else "姿态", listOf("横滚 X", "俯仰 Y", "偏航 Z"), d.attitude, "°", "垂直精度", d.vAcc, "m")
  Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { EstimatePanel(replay, landscape = true) }
   GnssCard(d, landscape = true)
   if (replay == null) ZeroButtons(d)
  }
 }
 }
}

@Composable
private fun LandscapeGauge(title: String, value: String, unit: String, modifier: Modifier) {
 Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
  Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
     var high = 48
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
 Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
  Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Text("当前速度 · GNSS", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
   Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(d.speed, color = MaterialTheme.colorScheme.onSurface,
     style = MaterialTheme.typography.displayLarge.copy(fontSize = 52.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"), maxLines = 1)
    Text("km/h", modifier = Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.titleSmall,
     color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
   Text(d.gpsStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 val pressure: String,
 val satellites: String,
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
  pressure = value(samples["phone.pressure"], 0),
  satellites = "${value(samples["phone.satellites"], 0, digits = 0)}/${value(samples["phone.satellites"], 1, digits = 0)}",
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
private fun GnssCard(d: DashboardModel, landscape: Boolean = false) {
 Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Text("GNSS / WGS84", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
   Text("纬度  ${d.lat} °", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
   Text("经度  ${d.lon} °", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
   Text(if (landscape) "航向  ${d.heading} °" else "高度  ${d.alt} m    垂直精度  ${d.vAcc} m", style = MaterialTheme.typography.bodySmall)
   HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
   for ((label, rate, fresh) in d.rates) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
     Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(38.dp))
     Text(if (fresh) "更新中" else "无当前数据", style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
     Text(rate, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
    }
   }
   Text("实际相邻样本频率；超过 3 秒无更新显示 —", style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

@Composable
internal fun Gauge(title: String, value: String, unit: String, modifier: Modifier) {
 Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
   Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
   BoxWithConstraints(Modifier.fillMaxWidth()) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum", letterSpacing = 0.sp, fontWeight = FontWeight.Medium)
    val template = if (value.substringBefore('.').removePrefix("-").length <= 3) "-888.8"
     else value.map { if (it.isDigit()) '8' else it }.joinToString("")
    val width = constraints.maxWidth
    val numberSize = remember(template, width, style, measurer) {
     var size = 26
     while (size > 12 && measurer.measure(template, style.copy(fontSize = size.sp), softWrap = false).size.width > width) size--
     size.sp
    }
    Text(value, modifier = Modifier.fillMaxWidth(), style = style.copy(fontSize = numberSize), maxLines = 1)
   }
   Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
  if (!landscape) {
   Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(if (frame != null) "历史姿态 · 车辆坐标" else "系统姿态 · 车辆坐标",
     style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
     modifier = Modifier.weight(1f))
    if (frame == null) TextButton(onClick = model.onZero, enabled = model.canZero) { Text("归零") }
   }
  }
  if (landscape) {
   Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    for (side in listOf(true, false)) {
     val reading = angles[if (side) 1 else 0]
     Card(Modifier.weight(1f).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
      Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
       Text(if (side) "俯仰 · 左视图" else "侧倾 · 正后视图", style = MaterialTheme.typography.titleSmall)
       AttitudeDial(side, reading, Modifier.weight(1f).fillMaxWidth())
       Text(if (reading.toFloatOrNull() == null) "—" else "$reading°",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium))
       if (side) GradeReading(reading)
      }
     }
    }
   }
  } else {
   Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    AttitudeCard(true, angles[1])
    AttitudeCard(false, angles[0])
   }
  }
 }
}

@Composable
private fun AttitudeCard(side: Boolean, reading: String) {
 val angle = reading.toFloatOrNull()
 Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
  Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
   Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
     Text(if (side) "俯仰" else "侧倾", style = MaterialTheme.typography.titleMedium)
     Text(if (side) "左视图 · 车头朝左" else "正后视图", color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium)
    }
    Text(if (angle == null) "—" else "$reading°", color = MaterialTheme.colorScheme.primary,
     style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium))
   }
   AttitudeDial(side, reading, Modifier.fillMaxWidth().height(158.dp))
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(if (angle == null) "等待当前姿态数据" else if (side) "正向：车头向下" else "正向：左侧抬起",
     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (side) GradeReading(reading)
   }
  }
 }
}

@Composable
private fun AttitudeDial(side: Boolean, reading: String, modifier: Modifier) {
 VehicleIllustration(side, reading, modifier)
}

@Composable
internal fun CompactAttitude(frame: ReplayFrame?, horizontal: Boolean = false) {
 val model = rememberDashboardModel(frame)
 val angles = model.attitude
 Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
  // Portrait uses the existing outer panel header for zero, preserving space for the dials.
  if (!horizontal) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
   Text(if (frame == null) "车辆姿态" else "历史姿态",
    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary, maxLines = 1)
   if (frame == null) TextButton(
    onClick = model.onZero,
    enabled = model.canZero,
    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
   ) { Text("归零") }
  }
  if (horizontal) {
   Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    for (side in listOf(true, false)) {
     val angle = angles[if (side) 1 else 0]
     Card(Modifier.weight(1f).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
      Column(Modifier.fillMaxSize().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
       Text(if (side) "俯仰 · 左视" else "侧倾 · 后视", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
       AttitudeDial(side, angle, Modifier.weight(1f).fillMaxWidth())
       Text(if (angle == "—") angle else "$angle°", style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.primary)
       if (side) GradeReading(angle)
      }
     }
    }
   }
  } else {
   Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    for (side in listOf(true, false)) {
     val angle = angles[if (side) 1 else 0]
     Card(Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
      Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
       Text(if (side) "俯仰 · 左视" else "侧倾 · 后视", style = MaterialTheme.typography.labelSmall)
       AttitudeDial(side, angle, Modifier.weight(1f).fillMaxWidth())
       Text(if (angle == "—") angle else "$angle°", style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.primary)
       if (side) GradeReading(angle)
      }
     }
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
