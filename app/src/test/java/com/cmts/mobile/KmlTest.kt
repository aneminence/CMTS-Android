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
   File(dir,"samples.csv").writeText("header\n1,phone.gps,1,1,1,provider_reported,39.1,117.3\n2,phone.gps,2,2,2,MOCK,1,2\n3,phone.gps,3,3,3,provider_reported,NaN,117\n4,phone.gps,4,4,4,provider_reported,39.2,117.4\n")
   val out = StringWriter()
   assertEquals(2,Kml.export(dir,out))
   val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(out.toString().byteInputStream())
   assertEquals("117.3,39.1\n117.4,39.2",xml.getElementsByTagName("coordinates").item(0).textContent.trim())
  } finally { dir.deleteRecursively() }
 }
 @Test fun rejectsImuOnly() {
  val dir = Files.createTempDirectory("kml-empty").toFile()
  try {
   File(dir,"closed.marker").writeText(""); File(dir,"samples.csv").writeText("header\n1,phone.accel,1,1,,ok,1,2,3\n")
   assertThrows(IllegalArgumentException::class.java) { Kml.export(dir,StringWriter()) }
  } finally { dir.deleteRecursively() }
 }
}
