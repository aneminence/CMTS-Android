package com.cmts.mobile

import java.io.File
import java.io.Reader
import java.io.Writer
import java.time.Instant

data class KmlData(val name: String, val track: List<TrackPoint>)

/** WGS84 ground track. No inferred location or conversion to map-provider coordinates. */
object Kml {
 private val whenTag = Regex("<when>(.*?)</when>", RegexOption.IGNORE_CASE)
 private val gxCoord = Regex("<(?:gx:)?coord>(.*?)</(?:gx:)?coord>", RegexOption.IGNORE_CASE)
 private val coordinateBlock = Regex("<coordinates\\b[^>]*>(.*?)</coordinates>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
 private val nameTag = Regex("<name>(.*?)</name>", RegexOption.IGNORE_CASE)
 private val isoOffset = Regex("([+-]\\d{2}):?(\\d{2})$")

 fun read(reader: Reader): KmlData {
  val text = reader.readText().trim().removePrefix("\uFEFF")
  require(text.isNotEmpty()) { "文件是空的" }
  val title = nameTag.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "KML 轨迹"
  val track = readGxTrack(text).ifEmpty { readCoordinates(text) }
  require(track.isNotEmpty()) { "文件没有可识别的 KML 轨迹点" }
  return KmlData(title, track)
 }

 private fun readGxTrack(text: String): List<TrackPoint> {
  val times = whenTag.findAll(text).map { parseWhen(it.groupValues[1].trim()) }.toList()
  val coords = gxCoord.findAll(text).mapNotNull { parseGxCoord(it.groupValues[1]) }.toList()
  if (coords.isEmpty()) return emptyList()
  return coords.mapIndexed { index, latlon ->
   val time = times.getOrNull(index) ?: (index * 1_000_000_000L)
   TrackPoint(latlon.first, latlon.second, time)
  }
 }

 private fun readCoordinates(text: String): List<TrackPoint> {
  val points = mutableListOf<TrackPoint>()
  coordinateBlock.findAll(text).forEach { block ->
   block.groupValues[1].trim().split(Regex("\\s+")).forEach { token ->
    val parts = token.split(',')
    val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: return@forEach
    val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return@forEach
    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEach
    points += TrackPoint(lat, lon, points.size * 1_000_000_000L)
   }
  }
  return points
 }

 private fun parseGxCoord(raw: String): Pair<Double, Double>? {
  val parts = raw.trim().split(Regex("\\s+"))
  val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
  val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
  if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
  return lat to lon
 }

 private fun parseWhen(raw: String): Long {
  val instant = runCatching { Instant.parse(raw) }.getOrNull()
   ?: runCatching { Instant.parse(normalizeIso(raw)) }.getOrNull()
  return instant?.toEpochMilli()?.let { it * 1_000_000L } ?: 0L
 }

 private fun normalizeIso(raw: String): String {
  val t = raw.trim()
  if (t.endsWith("Z") || isoOffset.containsMatchIn(t)) return t
  return t + "Z"
 }

 fun export(session: File, writer: Writer): Int {
  require(File(session, "closed.marker").exists()) { "记录尚未关闭，不能导出 KML" }
  val times = StringBuilder()
  val coords = StringBuilder()
  val speeds = StringBuilder()
  var count = 0
  writer.write("""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document><name>CMTS track</name>
<description>WGS84 GNSS track with UTC, receiver altitude (m) and speed (m/s).</description>
<Style id="track"><LineStyle><color>ff37afd4</color><width>3</width></LineStyle></Style>
<Placemark><name>GNSS track</name><styleUrl>#track</styleUrl><gx:Track><altitudeMode>absolute</altitudeMode>
""")
  File(session,"samples.csv").bufferedReader().useLines { lines ->
   lines.drop(1).forEach { line ->
    val c = line.split(',')
    if(c.getOrNull(1) != "phone.gps") return@forEach
    // Do not silently export simulated/mock locations as a real driven track.
    if(c.getOrNull(5)?.let { "MOCK" in it || "SIMULATED" in it } == true) return@forEach
    val lat = c.getOrNull(6)?.toDoubleOrNull() ?: return@forEach
    val lon = c.getOrNull(7)?.toDoubleOrNull() ?: return@forEach
    if(!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEach
    fun number(i: Int) = c.getOrNull(i)?.toDoubleOrNull()?.takeIf(Double::isFinite)
    val utc = c.getOrNull(4)?.toLongOrNull()?.let(java.time.Instant::ofEpochMilli)
    times.append("<when>").append(utc?.toString().orEmpty()).append("</when>\n")
    coords.append("<gx:coord>").append(lon).append(' ').append(lat)
    number(8)?.let { coords.append(' ').append(it) }
    coords.append("</gx:coord>\n")
    if (count > 0) speeds.append(';')
    speeds.append(number(9)?.toString().orEmpty()).append(',').append(number(11)?.toString().orEmpty())
    count++
   }
  }
  require(count >= 2) { "该记录不足两个有效真实 GNSS 点，无法生成 KML 轨迹" }
  writer.append(times).append(coords)
  writer.append("<ExtendedData><Data name=\"GxTrackExtendedData\"><value>").append(speeds)
  writer.write("</value></Data></ExtendedData></gx:Track></Placemark></Document></kml>\n")
  writer.flush()
  return count
 }
}
