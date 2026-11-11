package com.cmts.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.cmts.mobile.performance.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How a replay curve reads its value; vehicle kinds go through [displayVehicleAxes] like Dashboard. */
internal enum class ChannelKind { RAW, ACCEL, GYRO, ATTITUDE }

/** One replay curve channel. Labels / units / scales match the live Dashboard. */
internal data class ReplayChannel(
 val label: String,
 val source: String,
 val axis: Int,
 val scale: Double = 1.0,
 val kind: ChannelKind = ChannelKind.RAW,
)

private const val RAD_TO_DEG = 180.0 / Math.PI

internal val replayChannels: List<ReplayChannel> = listOf(
 ReplayChannel("速度 km/h", "phone.gps", 3, 3.6),
 ReplayChannel("高度 m", "phone.gps", 2),
 ReplayChannel("航向 °", "phone.gps", 4),
 ReplayChannel("气压 mbar", "phone.pressure", 0),
 ReplayChannel("卫星 可见 颗", "phone.satellites", 0),
 ReplayChannel("卫星 定位 颗", "phone.satellites", 1),
 // Attitude axis order = Dashboard: 0 横滚(侧倾) X, 1 俯仰 Y, 2 偏航 Z.
 ReplayChannel("横滚(侧倾) X °", "vehicle.attitude", 0, kind = ChannelKind.ATTITUDE),
 ReplayChannel("俯仰 Y °", "vehicle.attitude", 1, kind = ChannelKind.ATTITUDE),
 ReplayChannel("偏航 Z °", "vehicle.attitude", 2, kind = ChannelKind.ATTITUDE),
) + listOf("X", "Y", "Z").mapIndexed { i, a -> ReplayChannel("线加速度 $a m/s²", "vehicle.accel", i, kind = ChannelKind.ACCEL) } +
 listOf("X", "Y", "Z").mapIndexed { i, a -> ReplayChannel("角速度 $a °/s", "vehicle.gyro", i, RAD_TO_DEG, ChannelKind.GYRO) }

/**
 * Value of [channel] at [frame], with the same freshness rule (≤3 s, not in the future) and the same
 * vehicle-axis transform (gravity-removed accel, saved install zero) as the replay Dashboard.
 */
internal fun replayChannelValue(channel: ReplayChannel, frame: ReplayFrame): Double? {
 val samples = frame.samples
 val now = frame.nanos
 fun fresh(source: String) = isFresh(samples[source], now, true)
 return when (channel.kind) {
  ChannelKind.RAW -> if (fresh(channel.source)) samples[channel.source]?.values?.getOrNull(channel.axis)?.times(channel.scale) else null
  ChannelKind.ACCEL -> if (fresh("vehicle.accel") && (fresh("phone.rotation_vector") || fresh("vehicle.attitude")))
   displayVehicleAxes(frame.mountZero, samples).accel?.getOrNull(channel.axis)?.times(channel.scale) else null
  ChannelKind.GYRO -> if (fresh("vehicle.gyro")) displayVehicleAxes(frame.mountZero, samples).gyro?.getOrNull(channel.axis)?.times(channel.scale) else null
  ChannelKind.ATTITUDE -> if (fresh("vehicle.attitude")) displayVehicleAxes(frame.mountZero, samples).attitude?.getOrNull(channel.axis)?.times(channel.scale) else null
 }?.takeIf { it.isFinite() }
}

@Composable
internal fun ReplayCurves(data: ReplayData, index: Int, seek: (Int) -> Unit) {
 var channel by remember { mutableStateOf(replayChannels.first()) }
 var menu by remember { mutableStateOf(false) }
 var zoom by remember { mutableIntStateOf(1) }
 val frames = data.frames
 val values = remember(data,channel) { frames.map { replayChannelValue(channel, it) } }
 val first = frames.first().nanos
 val span = ((frames.last().nanos-first)/zoom).coerceAtLeast(1)
 val start = (frames[index].nanos-span/2).coerceIn(first,maxOf(first,frames.last().nanos-span))
 fun nearest(fraction: Float): Int {
  val target = start+(span*fraction.coerceIn(0f,1f).toDouble()).toLong()
  val pos = frames.binarySearchBy(target) { it.nanos }
  return (if(pos<0) -pos-1 else pos).coerceIn(frames.indices)
 }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
  Row {
   Box {
    TextButton(onClick={menu=true}) { Text(channel.label) }
    DropdownMenu(menu,{menu=false}) { replayChannels.forEach { c -> DropdownMenuItem({Text(c.label)}, {channel=c;menu=false}) } }
   }
   TextButton(onClick={zoom=if(zoom==8) 1 else zoom*2}) { Text("时间窗 1/$zoom") }
  }
  Text("${dashboardValue((frames[index].nanos-first)/1e9)} s   ${dashboardValue(values[index])} ${channel.label}")
  Canvas(Modifier.fillMaxWidth().height(100.dp).pointerInput(start,span,data) { detectTapGestures { seek(nearest(it.x/size.width)) } }) {
   val range = nearest(0f)..nearest(1f)
   val subset = values.subList(range.first,range.last+1).filterNotNull()
   val low = subset.minOrNull() ?: 0.0
   val extent = ((subset.maxOrNull() ?: low)-low).coerceAtLeast(.01)
   fun x(i:Int) = ((frames[i].nanos-start).toDouble()/span*size.width).toFloat()
   var prev: Offset? = null
   for(i in range step (range.count()/size.width.toInt().coerceAtLeast(1)).coerceAtLeast(1)) {
    val point=values[i]?.let { Offset(x(i),size.height*(1-((it-low)/extent).toFloat())) }
    if(prev!=null && point!=null) drawLine(Color(0xFFD4AF37),prev,point,2f)
    prev=point
   }
   drawLine(Color.White,Offset(x(index),0f),Offset(x(index),size.height))
  }
  ReplayScores(data.session)
 }
}

@Composable
private fun ReplayScores(session: File) {
 var input by remember(session) { mutableStateOf("0-60,0-100,100-0") }
 var busy by remember { mutableStateOf(false) }
 var rows by remember(session) { mutableStateOf(emptyList<String>()) }
 val scope=rememberCoroutineScope()
 OutlinedTextField(input,{input=it},label={Text("起速-终速 km/h，多段用逗号分隔")},enabled=!busy)
 TextButton(enabled=!busy,onClick={
  busy=true
  rows=emptyList()
  scope.launch {
   try {
    val configs=input.replace('，',',').split(',').map {
     val pair=it.trim().split('-')
     require(pair.size==2)
     val a=pair[0].trim().toDouble()
     val b=pair[1].trim().toDouble()
     val mode=when { a==0.0 -> PerformanceMode.ACCEL_0_TO_X
      b==0.0 -> PerformanceMode.BRAKE_X_TO_0
      b>a -> PerformanceMode.ACCEL_X_TO_Y
      else -> PerformanceMode.BRAKE_X_TO_Y }
     PerformanceConfig(mode,a,b)
    }.distinct()
    val raw=withContext(Dispatchers.IO) { rawSpeed(session) }
    for(config in configs) {
     val result=withContext(Dispatchers.IO) { saveScore(session,config,raw) }
     rows += "${config.startSpeedKmh} → ${config.endSpeedKmh} km/h   ${dashboardValue(result.durationSeconds(),digits=3)} s   ${result.phase} · ${result.quality} ${result.reasons.joinToString()}"
    }
   } catch(e: Exception) { rows += "计算失败 · ${e.localizedMessage ?: "请检查边界条件"}" }
   finally { busy=false }
  }
 }) { Text(if(busy) "正在计算…" else "应用边界并计算") }
 rows.forEach { Text(it) }
}

private fun rawSpeed(session: File): List<SpeedSample> = File(session,"samples.csv").useLines { lines ->
 lines.drop(1).filter { it.substringAfter(',').startsWith("phone.gps,") }.map { line ->
  val c=line.split(',')
  val speed=c.getOrNull(9)?.toDoubleOrNull()
  SpeedSample("phone.gps","gnss",c[0].toLong(),"session:${session.name}",c[2].toLong(),c[3].toLong(),
   c[4].toLongOrNull(),speed,c.getOrNull(13)?.toDoubleOrNull(),speed?.isFinite()==true,c[5]=="MOCK")
 }.toList().also { require(it.isNotEmpty()) { "会话缺少原始 GNSS 速度流" } }
}

@Synchronized
private fun saveScore(session: File, config: PerformanceConfig, raw: List<SpeedSample>): PerformanceResult {
 require(File(session,"closed.marker").isFile)
 val result=PerformanceEngine().recompute(config,raw)
 val source=File(session,"samples.csv")
 val handle=AttemptHandle(session.name,session,config,java.io.StringWriter().buffered(),source,true)
 PerformanceRepository(session).writeResult(handle,result)
 return result
}
