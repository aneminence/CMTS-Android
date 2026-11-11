package com.cmts.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One replay snapshot. [mountZero] is the session's saved install zero (from metadata.json), so
 * replay views apply the same vehicle-axis transform as the live Dashboard did; disabled when the
 * session was recorded without zero. Raw samples stay raw.
 */
internal data class ReplayFrame(val nanos: Long, val samples: Map<String, Sample>, val mountZero: MountZero = MountZero())
internal data class ReplayData(val session: File, val frames: List<ReplayFrame>, val track: List<TrackPoint>, val mountZero: MountZero = MountZero())

/** Sources loaded into replay frames (rotation vector is needed for gravity-removed linear accel). */
internal val REPLAY_SOURCES = setOf(
 "estimate.attitude", "estimate.status", "estimate.quaternion", "estimate.initial", "estimate.bias",
 "phone.pressure","phone.satellites","phone.gps","phone.rotation_vector",
 "vehicle.accel","vehicle.gyro","vehicle.attitude",
)

/**
 * Rebuild the install zero CaptureService saved at stop (mountZeroEnabled, finalMountBasis,
 * mountZeroAccel/Gyro/Attitude, mountZeroAtElapsedNanos). Missing or disabled → disabled zero
 * (raw axes); never invents a zero.
 */
internal fun mountZeroFromMetadata(meta: JSONObject?): MountZero {
 if (meta == null || !meta.optBoolean("mountZeroEnabled", false)) return MountZero()
 fun doubles(key: String): List<Double>? {
  val arr = meta.optJSONArray(key) ?: return null
  val out = List(arr.length()) { i -> arr.optDouble(i) }
  return out.takeIf { it.all(Double::isFinite) }
 }
 fun v3(arr: JSONArray?): V3? {
  if (arr == null || arr.length() < 3) return null
  val v = V3(arr.optDouble(0), arr.optDouble(1), arr.optDouble(2))
  return v.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
 }
 val gyro = doubles("mountZeroGyro") ?: return MountZero()
 val attitude = doubles("mountZeroAttitude") ?: return MountZero()
 if (gyro.size < 3 || attitude.size < 3) return MountZero()
 val basis = meta.optJSONArray("finalMountBasis")?.let { b ->
  val f = v3(b.optJSONArray(0)); val l = v3(b.optJSONArray(1)); val u = v3(b.optJSONArray(2))
  if (f != null && l != null && u != null) MountBasis(f, l, u) else null
 }
 return MountZero(
  basis = basis,
  enabled = true,
  accel = doubles("mountZeroAccel") ?: emptyList(),
  gyro = gyro,
  attitude = attitude,
  zeroedAtElapsedNanos = if (meta.has("mountZeroAtElapsedNanos") && !meta.isNull("mountZeroAtElapsedNanos")) meta.optLong("mountZeroAtElapsedNanos") else null,
 )
}

internal object Replay {
 private var cacheKey: String? = null
 private var cacheData: ReplayData? = null
 @Synchronized
 fun read(session: File, progress: (Int) -> Unit = {}): ReplayData {
  require(File(session,"closed.marker").exists()) { "请先停止并保存记录" }
  val csv = File(session,"samples.csv")
  val metaFile = File(session,"metadata.json")
  val key = "${session.absolutePath}:${csv.length()}:${csv.lastModified()}:${metaFile.lastModified()}"
  if(cacheKey == key) cacheData?.let { progress(100); return it }
  val fileSize = csv.length().coerceAtLeast(1)
  val sources = REPLAY_SOURCES
  val mountZero = runCatching { mountZeroFromMetadata(if (metaFile.isFile) JSONObject(metaFile.readText()) else null) }.getOrDefault(MountZero())
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
   if(last < 0 || source == "phone.gps" || t-last >= 50_000_000L) {
    require(frames.size < 288000) { "记录过长，当前回放最多约两小时，请分段" }
    rawLatest.forEach { (source, raw) -> latest[source] = parse(raw) }
    frames.add(ReplayFrame(t,latest.toMap(),mountZero)); last=t
   }
  } }
  require(frames.isNotEmpty()) { "记录中没有可回放数据" }
  rawLatest.forEach { (source, raw) -> latest[source] = parse(raw) }
  if(lastTime > frames.last().nanos) frames.add(ReplayFrame(lastTime,latest.toMap(),mountZero))
  progress(100)
  return ReplayData(session,frames,track.sortedBy { it.nanos },mountZero).also { cacheKey=key; cacheData=it }
 }
}


