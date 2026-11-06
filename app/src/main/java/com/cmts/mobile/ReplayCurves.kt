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

private data class Channel(val label: String, val source: String, val axis: Int, val scale: Double = 1.0)
private val channels = listOf(Channel("速度 km/h","phone.gps",3,3.6), Channel("俯仰 °","vehicle.attitude",1),
 Channel("侧倾 °","vehicle.attitude",0)) + listOf("X","Y","Z").mapIndexed { i, axis -> Channel("加速度 $axis m/s²","vehicle.accel",i) }

@Composable
internal fun ReplayCurves(data: ReplayData, index: Int, seek: (Int) -> Unit) {
 var channel by remember { mutableStateOf(channels.first()) }
 var menu by remember { mutableStateOf(false) }
 var zoom by remember { mutableIntStateOf(1) }
 val frames = data.frames
 val values = remember(data,channel) { frames.map { it.samples[channel.source]?.values?.getOrNull(channel.axis)?.times(channel.scale) } }
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
    DropdownMenu(menu,{menu=false}) { channels.forEach { c -> DropdownMenuItem({Text(c.label)}, {channel=c;menu=false}) } }
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
