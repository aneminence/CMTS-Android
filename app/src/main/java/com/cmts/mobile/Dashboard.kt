package com.cmts.mobile

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

internal fun dashboardValue(value: Double?, scale: Double = 1.0, digits: Int = 1): String =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.${digits}f", it * scale) } ?: "—"

internal fun isFresh(sample: Sample?, now: Long, running: Boolean): Boolean =
    running && sample != null && now >= sample.nanos && now - sample.nanos <= 3_000_000_000L

@Composable
fun Dashboard() {
 var clockTick by remember { mutableIntStateOf(0) }
 LaunchedEffect(Unit) { while(true) { delay(1000); clockTick++ } }
 // Read time on EVERY recomposition: a cached clock can precede a new sample,
 // incorrectly marking it unavailable until the next timer tick.
 val now = clockTick.let { SystemClock.elapsedRealtimeNanos() }
 val gps = Live.samples["phone.gps"]
 val accel = Live.samples["vehicle.accel"]
 val gyro = Live.samples["vehicle.gyro"]
 val attitude = Live.samples["vehicle.attitude"]
 fun value(s: Sample?, index: Int, scale: Double = 1.0, digits: Int = 1) =
  dashboardValue(if (isFresh(s, now, Live.running)) s?.values?.getOrNull(index) else null, scale, digits)
 Text("DASHBOARD", color = Color(0xFFD4AF37), style = MaterialTheme.typography.labelLarge)
 Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF202127))) {
  Column(Modifier.padding(20.dp)) {
   Text("速度 · GNSS", color = Color(0xFFB9BAC2))
   Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
    Text(value(gps, 3, 3.6), color = Color(0xFFD4AF37), fontSize = 64.sp, fontWeight = FontWeight.Light)
    Text(" km/h", modifier = Modifier.padding(bottom = 12.dp))
   }
   Text(if(!Live.running) "未采集" else if(!isFresh(gps, now, true)) "等待定位 / 数据超过 3 秒未更新" else "定位更新中 · ${gps?.quality}")
  }
 }
 Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
  Gauge("航向", value(gps, 4), "°", Modifier.weight(1f))
  Gauge("水平精度", value(gps, 5), "m", Modifier.weight(1f))
 }
 Text("加速度 · 车辆坐标 · 含重力", style = MaterialTheme.typography.titleSmall)
 Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  for(i in 0..2) Gauge("${"XYZ"[i]} 轴", value(accel, i, digits = 2), "m/s²", Modifier.weight(1f))
 }
 Text("角速度 · 车辆坐标", style = MaterialTheme.typography.titleSmall)
 Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  for(i in 0..2) Gauge("${"XYZ"[i]} 轴", value(gyro, i, 180.0 / Math.PI, 2), "°/s", Modifier.weight(1f))
 }
 Text("姿态角 · 车辆坐标", style = MaterialTheme.typography.titleSmall)
 Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  Gauge("横滚 · X", value(attitude, 0), "°", Modifier.weight(1f))
  Gauge("俯仰 · Y", value(attitude, 1), "°", Modifier.weight(1f))
  Gauge("偏航 · Z", value(attitude, 2), "° 磁北", Modifier.weight(1f))
 }
 Text("顶部朝车头、屏幕朝上：X 前 / Y 左 / Z 上。", style = MaterialTheme.typography.bodySmall)
 Text("右手正向：横滚左侧抬起、俯仰车头向下、偏航向左；磁北为 0°，未归零。", style = MaterialTheme.typography.bodySmall)
 Text(if (!isFresh(attitude, now, Live.running)) "姿态无当前数据 / 传感器未就绪" else
  "姿态精度状态：${attitude?.quality?.substringAfterLast("accuracy_")}（0 不可靠，1 低，2 中，3 高）；磁场干扰会影响偏航",
  style = MaterialTheme.typography.bodySmall)
 Card(Modifier.fillMaxWidth()) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   Text("GNSS / WGS84", color = Color(0xFFD4AF37))
   Text("纬度  ${value(gps, 0, digits = 6)} °")
   Text("经度  ${value(gps, 1, digits = 6)} °")
   Text("高度  ${value(gps, 2)} m    垂直精度  ${value(gps, 6)} m")
   for ((key, title) in listOf("phone.gps" to "GNSS", "vehicle.accel" to "加速度计", "vehicle.gyro" to "陀螺仪", "vehicle.attitude" to "姿态角")) {
    val s = Live.samples[key]
    Text("$title  ·  ${if(isFresh(s,now,Live.running)) "更新中" else "无当前数据"}  ·  ${dashboardValue(if(isFresh(s,now,Live.running)) Live.rates[key] else null, digits=2)} Hz")
   }
   Text("实际相邻样本频率；超过 3 秒无更新显示 —", style = MaterialTheme.typography.bodySmall)
  }
 }
}

@Composable
private fun Gauge(title: String, value: String, unit: String, modifier: Modifier) {
 Card(modifier) {
  Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
   Text(title, style = MaterialTheme.typography.labelMedium)
   Text(value, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1)
   Text(unit, style = MaterialTheme.typography.labelSmall, color = Color(0xFFD4AF37))
  }
 }
}
