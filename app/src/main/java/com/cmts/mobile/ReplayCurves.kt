package com.cmts.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.cmts.mobile.performance.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

private data class ReplayCurvePlot(val series: ReplayCurveSeries, val missingPrefix: IntArray)
private data class PreparedReplayCurve(val data: ReplayData, val channel: ReplayChannel, val plot: ReplayCurvePlot)

@Composable
internal fun ReplayCurves(data: ReplayData, index: Int, seek: (Int) -> Unit) {
 var channel by remember { mutableStateOf(replayChannels.first()) }
 var menu by remember { mutableStateOf(false) }
 var zoom by remember { mutableIntStateOf(1) }
 val frames = data.frames
 val selectedChannel = channel
 val channelCache = remember(data) { LinkedHashMap<ReplayChannel, ReplayCurvePlot>(4, .75f, true) }
 val prepared by produceState<PreparedReplayCurve?>(null, data, selectedChannel) {
  val plot = channelCache[selectedChannel] ?: withContext(Dispatchers.Default) {
   val context = currentCoroutineContext()
   // Cache missing intervals alongside the display series so pixel decimation cannot bridge a gap.
   val missingPrefix = IntArray(frames.size + 1)
   val displayValues = DoubleArray(frames.size) { i ->
    if (i % 1024 == 0) context.ensureActive()
    val value = replayChannelValue(selectedChannel, frames[i])
    missingPrefix[i + 1] = missingPrefix[i] + if (value == null) 1 else 0
    value ?: Double.NaN
   }
   ReplayCurvePlot(ReplayCurveSeries(displayValues), missingPrefix)
  }.also {
   channelCache[selectedChannel] = it
   while (channelCache.size > 3) channelCache.remove(channelCache.keys.first())
  }
  value = PreparedReplayCurve(data, selectedChannel, plot)
 }
 val plot = prepared?.takeIf { it.data === data && it.channel == selectedChannel }?.plot
 val values = plot?.series
 val first = frames.first().nanos
 val span = ((frames.last().nanos-first)/zoom).coerceAtLeast(1)
 val start = (frames[index].nanos-span/2).coerceIn(first,maxOf(first,frames.last().nanos-span))
 fun nearest(fraction: Float): Int {
  val target = start+(span*fraction.coerceIn(0f,1f).toDouble()).toLong()
  val pos = frames.binarySearchBy(target) { it.nanos }
  return (if(pos<0) -pos-1 else pos).coerceIn(frames.indices)
 }
 val range = nearest(0f)..nearest(1f)
 val bounds = remember(values, range) { values?.bounds(range.first, range.last) ?: (0.0 to 0.0) }
 val colors = MaterialTheme.colorScheme
 val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
 val chartHeight = if (isLandscape) 144.dp else 220.dp
 val low = bounds.first
 val extent = (bounds.second-low).coerceAtLeast(.01)
 val numberStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
  Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.small) {
   Text("历史回放 · 非实时数据", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = colors.onSecondaryContainer, style = MaterialTheme.typography.labelMedium)
  }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
   Box(Modifier.weight(1f)) {
    FilledTonalButton(onClick={menu=true}, modifier = Modifier.fillMaxWidth()) { Text(channel.label + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
    DropdownMenu(menu,{menu=false}) { replayChannels.forEach { c -> DropdownMenuItem({Text(c.label)}, {channel=c;menu=false}) } }
   }
   OutlinedButton(onClick={zoom=if(zoom==8) 1 else zoom*2}) { Text("时间窗 1/$zoom") }
  }
  Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
  Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
    Column(Modifier.weight(1f)) {
     Text("游标处 · ${channel.label}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
     Text(dashboardValue(values?.get(index)), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), color = colors.onSurface)
    }
    Text("${dashboardValue((frames[index].nanos-first)/1e9)} s", style = numberStyle, color = colors.primary)
   }
  if (values == null) LinearProgressIndicator(Modifier.fillMaxWidth())
  Row(Modifier.fillMaxWidth().height(chartHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
   Column(Modifier.width(56.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
    for (tick in 4 downTo 0) {
     Text(dashboardValue(low + extent * tick / 4.0, digits = if (extent < 1.0) 2 else 1), style = numberStyle, color = colors.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1)
    }
   }
  Canvas(Modifier.weight(1f).fillMaxHeight()
   .semantics { contentDescription = "${channel.label}随时间曲线；点击定位回放游标" }
   .pointerInput(start,span,data) { detectTapGestures { seek(nearest(it.x/size.width)) } }) {
   fun x(i:Int) = ((frames[i].nanos-start).toDouble()/span*size.width).toFloat()
   clipRect {
    for (tick in 0..4) {
     val y = size.height * tick / 4f
     drawLine(colors.outlineVariant.copy(alpha = .6f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    }
    var prev: Offset? = null
    var prevIndex = range.first
    for(i in range step (range.count()/size.width.toInt().coerceAtLeast(1)).coerceAtLeast(1)) {
     val point=values?.get(i)?.let { Offset(x(i),size.height*(1-((it-low)/extent).toFloat())) }
     val continuous = plot?.let { it.missingPrefix[i + 1] == it.missingPrefix[prevIndex] } == true
     if(prev!=null && point!=null && continuous) drawLine(colors.primary,prev,point,2.dp.toPx())
     prev=point
     prevIndex=i
    }
    drawLine(colors.onSurface.copy(alpha = .8f),Offset(x(index),0f),Offset(x(index),size.height),1.dp.toPx())
    values?.get(index)?.let { value ->
     drawCircle(colors.primary, 4.dp.toPx(), Offset(x(index),size.height*(1-((value-low)/extent).toFloat())))
    }
   }
  }
  }
  Row(Modifier.fillMaxWidth().padding(start = 64.dp), horizontalArrangement = Arrangement.SpaceBetween) {
   listOf(start, start + span / 2, start + span).forEach { time ->
    Text("${dashboardValue((time-first)/1e9)} s", style = numberStyle, color = colors.onSurfaceVariant)
   }
  }
  Text("点击曲线定位 · 缺失数据保留断点", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
  }
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
 Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
 Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
 Text("离线性能计分", style = MaterialTheme.typography.titleMedium)
 Text("使用会话原始 GNSS 速度；生成独立成绩版本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
 OutlinedTextField(input,{input=it},label={Text("起速-终速 km/h")},supportingText = { Text("多段用逗号分隔，例如 0-60,0-100,100-0") },enabled=!busy, modifier = Modifier.fillMaxWidth())
 FilledTonalButton(enabled=!busy,modifier = Modifier.fillMaxWidth(),onClick={
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
 rows.forEach {
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
 }
 }
 }
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
