package com.cmts.mobile

import java.io.File
import java.io.Writer

/** WGS84 ground track. No inferred location or conversion to map-provider coordinates. */
object Kml {
 fun export(session: File, writer: Writer): Int {
  require(File(session, "closed.marker").exists()) { "记录尚未关闭，不能导出 KML" }
  var count = 0
  writer.write("""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>CMTS track</name>
<description>WGS84 ground track; altitude clamped to ground. Raw timing and quality remain in CSV.</description>
<Style id="track"><LineStyle><color>ff37afd4</color><width>3</width></LineStyle></Style>
<Placemark><name>GNSS track</name><styleUrl>#track</styleUrl><LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>
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
    writer.write("$lon,$lat\n")
    count++
   }
  }
  require(count >= 2) { "该记录不足两个有效真实 GNSS 点，无法生成 KML 轨迹" }
  writer.write("</coordinates></LineString></Placemark></Document></kml>\n")
  writer.flush()
  return count
 }
}
