package com.cmts.mobile

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory

class KmlTest {
 @Test fun exportsLongitudeFirstAndSkipsInvalidAndMock() {
  val dir = Files.createTempDirectory("kml").toFile()
  try {
   File(dir,"closed.marker").writeText("")
   File(dir,"samples.csv").writeText("header\n1,phone.gps,1,1,1,provider_reported,39.1,117.3\n2,phone.gps,2,2,2,MOCK,1,2\n3,phone.gps,3,3,3,provider_reported,NaN,117\n4,phone.gps,4,4,4,provider_reported,39.2,117.4,-9.094,4.38,90,2.5\n")
   val out = StringWriter()
   assertEquals(2,Kml.export(dir,out))
   val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(out.toString().byteInputStream())
   assertEquals("117.3 39.1",xml.getElementsByTagName("gx:coord").item(0).textContent)
   assertEquals("117.4 39.2 -9.094",xml.getElementsByTagName("gx:coord").item(1).textContent)
   assertEquals(",;4.38,2.5",xml.getElementsByTagName("value").item(0).textContent)
   assertEquals("1970-01-01T00:00:00.004Z",xml.getElementsByTagName("when").item(1).textContent)
  } finally { dir.deleteRecursively() }
 }
 @Test fun rejectsImuOnly() {
  val dir = Files.createTempDirectory("kml-empty").toFile()
  try {
   File(dir,"closed.marker").writeText(""); File(dir,"samples.csv").writeText("header\n1,phone.accel,1,1,,ok,1,2,3\n")
   assertThrows(IllegalArgumentException::class.java) { Kml.export(dir,StringWriter()) }
  } finally { dir.deleteRecursively() }
 }

 @Test fun readsExportedGxTrackAndLineString() {
  val dir = Files.createTempDirectory("kml-read").toFile()
  try {
   File(dir,"closed.marker").writeText("")
   File(dir,"samples.csv").writeText("header\n1,phone.gps,1,1,1000,provider_reported,39.1,117.3,2.0,4.0\n2,phone.gps,2,2,2000,provider_reported,39.2,117.4,3.0,5.0\n")
   val exported = StringWriter()
   assertEquals(2, Kml.export(dir, exported))
   val data = Kml.read(exported.toString().reader())
   assertEquals(2, data.track.size)
   assertEquals(39.1, data.track[0].latitude, 1e-9)
   assertEquals(117.3, data.track[0].longitude, 1e-9)
   assertEquals(39.2, data.track[1].latitude, 1e-9)
   val line = """
    <kml><Document><name>Line</name><Placemark><LineString>
    <coordinates>117.3,39.1,0 117.4,39.2,10</coordinates>
    </LineString></Placemark></Document></kml>
   """.trimIndent()
   val fromLine = Kml.read(line.reader())
   assertEquals("Line", fromLine.name)
   assertEquals(2, fromLine.track.size)
   assertEquals(39.1, fromLine.track[0].latitude, 1e-9)
   assertEquals(117.4, fromLine.track[1].longitude, 1e-9)
  } finally { dir.deleteRecursively() }
 }
 @Test fun rejectsEmptyKml() {
  assertThrows(IllegalArgumentException::class.java) { Kml.read("<kml></kml>".reader()) }
 }
}
