package com.cmts.mobile
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File
class ReplayTest {
 @Test fun preservesImuAndCoordinatesWithoutFutureSamples() {
  val d=Files.createTempDirectory("replay").toFile()
  try {
   File(d,"closed.marker").writeText("")
   File(d,"samples.csv").writeText("header\n1,vehicle.accel,100,100,,ok,1,2,3\n2,phone.gps,200000000,200000000,123,reported,39,117\n3,vehicle.accel,400000000,400000000,,ok,4,5,6\n")
   val r=Replay.read(d)
   assertNull(r.frames.first().samples["phone.gps"])
   assertEquals(117.0,r.track.single().longitude,0.0)
   assertEquals(4.0,r.frames.last().samples["vehicle.accel"]!!.values[0]!!,0.0)
  } finally { d.deleteRecursively() }
 }
}
