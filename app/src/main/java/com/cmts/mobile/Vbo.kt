package com.cmts.mobile

import java.io.File
import java.io.Reader
import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class VboData(val columns: List<String>, val rows: List<DoubleArray>, val track: List<TrackPoint>)

object Vbo {
 private val whitespace = Regex("\\s+")
 fun read(reader: Reader): VboData {
  var section = ""
  val columns = mutableListOf<String>()
  val rows = mutableListOf<DoubleArray>()
  reader.buffered().forEachLine { raw ->
   val line = raw.trim().removePrefix("\uFEFF")
   if (line.startsWith("[") && line.endsWith("]")) section = line.lowercase(Locale.US)
   else if (line.isNotEmpty()) when (section) {
    "[column names]" -> columns.addAll(line.split(whitespace))
    "[data]" -> {
     require(columns.isNotEmpty()) { "VBO 缺少 [column names]" }
     require(rows.size < 200000) { "当前最多打开 200000 行，请先分段文件" }
     val cells = line.split(whitespace)
     // Some supplied GSpot files declare a trailing metadata column but omit its value.
     rows.add(DoubleArray(columns.size) { cells.getOrNull(it)?.toDoubleOrNull() ?: Double.NaN })
    }
   }
  }
  require(rows.isNotEmpty()) { "文件没有可读取的 [data] 数据" }
  val latIndex = columns.indexOfFirst { it.equals("lat", true) || it.equals("latitude", true) }
  val lonIndex = columns.indexOfFirst { it.equals("long", true) || it.equals("longitude", true) }
  val timeIndex = columns.indexOfFirst { it.equals("time", true) }
  var day = 0.0
  var previous = 0.0
  val track = rows.mapNotNull { row ->
   val lat = row.getOrNull(latIndex)?.div(60) ?: return@mapNotNull null
   val lon = row.getOrNull(lonIndex)?.div(-60) ?: return@mapNotNull null
   val time = row.getOrNull(timeIndex) ?: return@mapNotNull null
   if (!time.isFinite()) return@mapNotNull null
   val hour = (time / 10000).toInt(); val minute = (time / 100).toInt() % 100; val second = time % 100
   if (hour !in 0..23 || minute !in 0..59 || second !in 0.0..<60.0) return@mapNotNull null
   val tod = hour * 3600 + minute * 60 + second
   if (tod < previous - 43200) day += 86400
   previous = tod
   if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
   TrackPoint(lat, lon, ((day + tod) * 1e9).toLong())
  }
  return VboData(columns, rows, track)
 }

 // VBO is a GNSS-time-aligned view. Original full-rate samples remain in the CSV archive.
 fun export(session: File, writer: Writer): Int {
  require(File(session, "closed.marker").exists()) { "记录尚未关闭，不能导出 VBO" }
  val csv = File(session, "samples.csv")
  val names = listOf("time", "Elapsed_time", "velocity", "heading", "lat", "long", "height",
   "Vehicle_Acc_X", "Vehicle_Acc_Y", "Vehicle_Acc_Z", "Vehicle_Gyro_X", "Vehicle_Gyro_Y", "Vehicle_Gyro_Z",
   "Vehicle_Roll", "Vehicle_Pitch", "Vehicle_Yaw", "Horizontal_Accuracy", "Vertical_Accuracy", "Sample_Elapsed_ns", "UTC_ms", "Mock_or_Simulated")
  val headers = listOf("time", "Elapsed time", "velocity kmh", "heading", "latitude", "longitude", "height") + names.drop(7)
  val units = listOf("s", "s", "km/h", "deg", "arcmin", "arcmin", "m") + List(3) { "m/s2" } + List(3) { "deg/s" } + List(3) { "deg" } + listOf("m", "m", "ns", "ms", "flag")
  var count = 0
  var start = 0L
  val latest = mutableMapOf<String, Pair<Long, List<Double?>>>()
  val utc = DateTimeFormatter.ofPattern("HHmmss.SSS", Locale.US).withZone(ZoneOffset.UTC)
  csv.bufferedReader().useLines { lines -> lines.drop(1).forEach { line ->
   val cells = line.split(',')
   if (cells.size < 6) return@forEach
   val source = cells[1]; val nanos = cells[2].toLongOrNull() ?: return@forEach
   val values = cells.drop(6).map { it.toDoubleOrNull()?.takeIf(Double::isFinite) }
   if (source != "phone.gps") { latest[source] = nanos to values; return@forEach }
   val millis = cells[4].toLongOrNull() ?: return@forEach
   val lat = values.getOrNull(0) ?: return@forEach
   val lon = values.getOrNull(1) ?: return@forEach
   if (count == 0) {
    start = nanos
    val date = DateTimeFormatter.ofPattern("dd/MM/yyyy '@' HH:mm", Locale.US).withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millis))
    writer.write("File created on $date\n\n[header]\n${headers.joinToString("\n")}\n\n[channel units]\n${units.joinToString("\n")}\n\n[comments]\nCMTS Android; UTC; WGS84; latitude north positive arcmin, longitude west positive arcmin.\nNaN = unavailable; GNSS rows only; latest preceding IMU sample up to 0.2s old.\nVehicle axes: X forward, Y left, Z up (phone flat, screen up, top toward nose). Acceleration includes gravity. Vehicle_Yaw is right-handed from magnetic north (NOT GNSS heading).\nFull-rate sensor timestamps, quality and raw rotation vectors remain in CSV + metadata.\n\n[SessionData]\nSessionId:${session.name}\n\n[column names]\n${names.joinToString(" ")}\n\n[data]\n")
   }
   fun sensor(key: String, scale: Double = 1.0): List<Double?> {
    val sample = latest[key]
    return List(3) { index -> if (sample != null && nanos - sample.first in 0..200_000_000L) sample.second.getOrNull(index)?.times(scale) else null }
   }
   val data: List<Double?> = listOf((nanos - start) / 1e9, values.getOrNull(3)?.times(3.6), values.getOrNull(4), lat * 60, -lon * 60, values.getOrNull(2)) +
    sensor("vehicle.accel") + sensor("vehicle.gyro", 180 / Math.PI) + sensor("vehicle.attitude") +
    listOf(values.getOrNull(5), values.getOrNull(6), nanos.toDouble(), millis.toDouble(), if (cells[5].contains("MOCK") || cells[5].contains("SIMULATED")) 1.0 else 0.0)
   writer.write(utc.format(Instant.ofEpochMilli(millis)) + " " + data.joinToString(" ") { it?.let { number -> String.format(Locale.US, "%.9f", number) } ?: "NaN" } + "\n")
   count++
  } }
  require(count > 0) { "该记录没有 GNSS 定位数据，无法生成轨迹 VBO" }
  writer.flush()
  return count
 }
}
