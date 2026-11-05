package com.cmts.mobile

import java.io.File

data class ReplayFrame(val nanos: Long, val samples: Map<String, Sample>)
data class ReplayData(val frames: List<ReplayFrame>, val track: List<TrackPoint>)
object Replay {
 private var cacheKey: String? = null
 private var cacheData: ReplayData? = null
 @Synchronized
 fun read(session: File, progress: (Int) -> Unit = {}): ReplayData {
  require(File(session,"closed.marker").exists()) { "请先停止并保存记录" }
  val csv = File(session,"samples.csv")
  val key = "${session.absolutePath}:${csv.length()}:${csv.lastModified()}"
  if(cacheKey == key) cacheData?.let { progress(100); return it }
  val fileSize = csv.length().coerceAtLeast(1)
  val sources = setOf("phone.pressure","phone.satellites","phone.gps","vehicle.accel","vehicle.gyro","vehicle.attitude")
  var readChars = 0L
  var lastPercent = -1
  val rawLatest = mutableMapOf<String,String>()
  fun parse(line: String): Sample {
   val c=line.split(',')
   return Sample(c[1],c[2].toLong(),c[4].toLongOrNull(),c.drop(6).map { it.toDoubleOrNull()?.takeIf(Double::isFinite) },c[5])
  }
  val frames = mutableListOf<ReplayFrame>()
  val track = mutableListOf<TrackPoint>()
  val latest = mutableMapOf<String,Sample>()
  var last = -1L
  var lastTime = 0L
  File(session,"samples.csv").bufferedReader().useLines { lines -> lines.drop(1).forEach { line ->
   readChars += line.length + 1
   val percent = ((readChars * 100) / fileSize).toInt().coerceIn(0,99)
   if(percent != lastPercent) { progress(percent); lastPercent=percent }
   val first=line.indexOf(','); val second=line.indexOf(',',first+1); val third=line.indexOf(',',second+1)
   if(first < 0 || second < 0 || third < 0) return@forEach
   val source=line.substring(first+1,second)
   if(source !in sources) return@forEach
   val t=line.substring(second+1,third).toLongOrNull()?.takeIf { it >= 0 } ?: return@forEach
   rawLatest[source]=line; lastTime=maxOf(lastTime,t)
   if(source == "phone.gps") {
    val sample = parse(line)

    val lat=sample.values.getOrNull(0); val lon=sample.values.getOrNull(1)
    if(lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) track.add(TrackPoint(lat,lon,t))
   }
   if(last < 0 || t-last >= 100_000_000L) {
    require(frames.size < 72000) { "记录过长，当前回放最多约两小时，请分段" }
    rawLatest.forEach { (source, raw) -> latest[source] = parse(raw) }
    frames.add(ReplayFrame(t,latest.toMap())); last=t
   }
  } }
  require(frames.isNotEmpty()) { "记录中没有可回放数据" }
  rawLatest.forEach { (source, raw) -> latest[source] = parse(raw) }
  if(lastTime > frames.last().nanos) frames.add(ReplayFrame(lastTime,latest.toMap()))
  progress(100)
  return ReplayData(frames,track.sortedBy { it.nanos }).also { cacheKey=key; cacheData=it }
 }
}

