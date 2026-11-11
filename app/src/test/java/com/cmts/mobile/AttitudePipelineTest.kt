package com.cmts.mobile

import org.junit.Assert.*
import org.junit.Test

class AttitudePipelineTest {
 @Test fun pauseRequiresNewCalibrationAndNeverReusesOldPose() {
  val p=initialized()
  val invalid=tick(p,10_000_000_000L)
  assertTrue(invalid.quality.contains("重新停车校准"))
  val result=p.observe(Sample("vehicle.gyro",10_010_000_000L,null,listOf(0.0,0.0,0.0)),2,true)
  assertTrue(result.first { it.source=="estimate.attitude" }.values.all { it==null })
 }
 @Test fun delayedFutureAndOutOfOrderGnssCannotRemainActive() {
  val p=initialized()
  p.observe(gps(9_000_000_000L,20.0,180.0),1,true)
  assertNull(tick(p,7_010_000_000L).values[3])
  p.observe(gps(6_000_000_000L,0.0,0.0),1,true)
  assertNull(tick(p,7_020_000_000L).values[3])
 }
 private fun initialized(): AttitudePipeline = AttitudePipeline().also { p ->
  history().forEach { e ->
   val s=e.sample
   p.observe(if(s.source=="phone.gps") s else s.copy(source=s.source.replace("phone.","vehicle."),values=vehicleAxes(s.values.map { it!! })),1,true)
  }
 }
 private fun gps(t: Long, speed: Double, course: Double, accuracy: Double=0.1) =
  Sample("phone.gps",t,null,listOf(0.0,0.0,0.0,speed,course,1.0,1.0,accuracy,1.0),"provider_reported")
 private fun tick(p: AttitudePipeline,t: Long,phone: Boolean=true): Sample {
  p.observe(Sample("vehicle.accel",t,null,listOf(0.0,0.0,9.80665)),1,phone)
  return p.observe(Sample("vehicle.gyro",t,null,listOf(-0.002,-0.001,0.003)),1,phone).first { it.source=="estimate.status" }
 }
 @Test fun courseWrapLowSpeedReverseAndQuality() {
  for ((speed,course,accuracy,expectedRisk) in listOf(
   listOf(10.0,1.0,0.1,1.0), listOf(0.1,180.0,0.1,0.0),
   listOf(10.0,359.0,0.1,0.0), listOf(10.0,1.0,5.0,0.0))) {
   val p=initialized()
   p.observe(gps(7_000_000_000L,speed,359.0,accuracy),1,true)
   for(i in 1..100) tick(p,7_000_000_000L+i*10_000_000L)
   p.observe(gps(8_000_000_000L,speed,course,accuracy),1,true)
   val s=tick(p,8_010_000_000L)
   if(expectedRisk==1.0) assertTrue(s.quality.contains("dynamic_gyro"))
   // Forward or reverse travel with unchanging course is not inferred body heading.
   if(speed==0.1 || accuracy>1) assertFalse(s.quality.contains("dynamic_gyro"))
  }
 }
 @Test fun staleGnssAndSourceSwitchDisableAid() {
  val p=initialized()
  p.observe(gps(7_000_000_000L,10.0,0.0),1,true)
  var last: Sample?=null
  for(i in 1..400) last=tick(p,7_000_000_000L+i*10_000_000L)
  assertNull(last!!.values[3])
  assertFalse(last.quality.contains("dynamic_gyro"))
  p.observe(gps(11_000_000_000L,20.0,0.0),1,true)
  assertNull(tick(p,11_010_000_000L,false).values[3])
 }
 @Test fun csvRoundTripPreservesCausalContext() {
  val file=java.io.File.createTempFile("cmts-attitude-", ".csv")
  try {
   val events=history()
   file.bufferedWriter().use { w ->
    w.appendLine("sequence,source,nanos,callbackNanos,utcMillis,quality,values")
    events.forEachIndexed { i,e ->
     w.appendLine(Sample("estimate.context",e.sample.nanos,null,listOf(e.calibrationRequest.toDouble(),1.0),"${VehicleAttitudeEstimator.VERSION};${e.sample.source};fixed_FLU").csv(2L*i,e.sample.nanos))
     w.appendLine(e.sample.csv(2L*i+1,e.sample.nanos))
    }
   }
   assertEquals(AttitudeRecompute.run(events),AttitudeRecompute.fromCsv(file))
  } finally { file.delete() }
 }
 private fun history(): List<AttitudeRecompute.Event> = buildList {
  for(i in 0..600) {
   val t=1_000_000_000L+i*10_000_000L
   if(i%100==0) add(AttitudeRecompute.Event(Sample("phone.gps",t,null,listOf(0.0,0.0,0.0,0.0,0.0,1.0,1.0,0.1,1.0),"provider_reported"),1,true))
   add(AttitudeRecompute.Event(Sample("phone.accel",t,null,listOf(0.0,0.0,9.80665)),1,true))
   add(AttitudeRecompute.Event(Sample("phone.gyro",t,null,listOf(0.001,-0.002,0.003)),1,true))
  }
 }
 @Test fun replayDeterministicAndInputsUnchanged() {
  val input=history(); val before=input.toList()
  val a=AttitudeRecompute.run(input); val b=AttitudeRecompute.run(input)
  assertEquals(a,b); assertEquals(before,input)
  val expected=listOf(-0.002,-0.001,0.003)
  expected.forEachIndexed { i,v -> assertEquals(v,a.last { it.source=="estimate.bias" }.values[i]!!,1e-12) }
  assertTrue(a.last { it.source=="estimate.attitude" }.values.all { kotlin.math.abs(it!!)<0.001 })
 }
 @Test(expected=IllegalArgumentException::class) fun oldFilesCannotInventRawData() {
  AttitudeRecompute.run(listOf(AttitudeRecompute.Event(Sample("vehicle.attitude",1,null,listOf(0.0,0.0,0.0)),1,true)))
 }
 @Test fun stableMotionIsNotParking() {
  val pipeline=AttitudePipeline()
  val outputs=history().flatMap { e ->
   val s=if(e.sample.source=="phone.gps") e.sample.copy(values=e.sample.values.toMutableList().also { it[3]=10.0 }) else e.sample
   val mapped=if(s.source=="phone.accel" || s.source=="phone.gyro") s.copy(source=s.source.replace("phone.","vehicle.")) else s
   pipeline.observe(mapped,1,true)
  }
  assertFalse(outputs.any { it.source=="estimate.attitude" && it.values.all { v -> v!=null } })
 }
}
