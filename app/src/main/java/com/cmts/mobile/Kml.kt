package com.cmts.mobile

import java.io.File
import java.io.Writer

/** WGS84 ground track. No inferred location or conversion to map-provider coordinates. */
object Kml {
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

