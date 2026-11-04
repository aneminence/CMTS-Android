package com.cmts.mobile

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringWriter

class VboTest {
 @get:Rule val temp = TemporaryFolder()
 @Test fun readsStandardArcminutesAndMidnight() {
  val data = Vbo.read("[column names]\ntime lat long velocity extra\n[data]\n235959.900 1800 -7200 36\n000000.100 1800.01 -7200.01 37\n".reader())
  assertEquals(30.0, data.track[0].latitude, 1e-9)
  assertEquals(120.0, data.track[0].longitude, 1e-9)
  assertTrue(data.rows[0][4].isNaN())
  assertEquals(200000000L, data.track[1].nanos - data.track[0].nanos)
 }
 @Test fun exportsRoundTripAndDoesNotInventMissingData() {
  val session = temp.newFolder()
  File(session, "closed.marker").writeText("closed")
  File(session, "samples.csv").writeText("header\n1,phone.accel,1000000000,1000000000,,accuracy_3,1,2,9.8\n2,phone.gps,1100000000,1100000000,1704067200000,MOCK,30,120,10,10,90,2,3\n")
  val out = StringWriter(); assertEquals(1, Vbo.export(session, out))
  val data = Vbo.read(out.toString().reader())
  assertEquals(120.0, data.track.single().longitude, 1e-9)
  assertEquals(36.0, data.rows[0][data.columns.indexOf("velocity")], 1e-9)
  assertTrue(data.rows[0][data.columns.indexOf("Vehicle_Acc_Z")].isNaN())
  assertTrue(data.rows[0][data.columns.indexOf("Vehicle_Pitch")].isNaN())
  assertEquals(1.0, data.rows[0].last(), 0.0)
 }
 @Test(expected = IllegalArgumentException::class) fun rejectsNoGnssExport() {
  val session = temp.newFolder(); File(session,"closed.marker").writeText(""); File(session,"samples.csv").writeText("header\n")
  Vbo.export(session, StringWriter())
 }
 @Test fun suppliedSamplesHaveUsableTracks() {
  val root = File("D:/CMTS/test data")
  org.junit.Assume.assumeTrue(root.exists())
  for(name in listOf("ons shot 7.vbo", "CMTS_测试文件_供VBTS验证.vbo", "GSpot-000-1162347959.vbo")) {
   // Read complete headers plus first 100 data rows without loading large files.
   val excerpt = StringBuilder(); var inData = false; var rows = 0
   File(root,name).bufferedReader().use { reader ->
    while(rows < 100) {
     val line = reader.readLine() ?: break
     excerpt.appendLine(line)
     if(inData && line.isNotBlank()) rows++
     if(line.trim().equals("[data]", true)) inData = true
    }
   }
   val data = Vbo.read(excerpt.toString().reader())
   assertEquals(name, 100, data.rows.size)
   assertTrue(name, data.track.isNotEmpty())
   assertTrue(name, data.track.first().longitude > 0)
  }
 }
}
