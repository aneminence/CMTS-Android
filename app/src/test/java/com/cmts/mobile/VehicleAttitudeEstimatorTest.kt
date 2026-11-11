package com.cmts.mobile

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class VehicleAttitudeEstimatorTest {
 @Test fun slowAccelerationWithNoiseReportsResidualInsteadOfClaimingAccuracy() {
  val random=java.util.Random(42)
  val c=VehicleAttitudeEstimator(listOf(1.0,0.0,0.0,0.0))
  var maximum=0.0
  for(i in 0..6000) {
   val t=1_000_000_000L+i*10_000_000L
   val acceleration=min(i/6000.0,1.0)*0.3
   c.accelerometer(t,listOf(acceleration+random.nextGaussian()*0.002,random.nextGaussian()*0.002,9.80665+random.nextGaussian()*0.002))
   val o=c.gyroscope(t,listOf(0.0,0.0003+random.nextGaussian()*0.0001,0.0))!!
   maximum=max(maximum,abs(2*atan2(o.wxyz[2],o.wxyz[0])*180/PI))
  }
  println("slow ramp 0-to-0.3m/s2 in 60s bias+noise maxFalsePitchDeg=$maximum")
  assertTrue(maximum.isFinite())
  assertTrue(maximum>0.1) // This unobservable gentle ramp is deliberately not sold as solved.
 }
 @Test fun asynchronousFutureDuplicateAndSaturation() {
  val c=VehicleAttitudeEstimator(listOf(1.0,0.0,0.0,0.0))
  c.accelerometer(1_010_000_000L,listOf(0.0,0.0,9.80665))
  assertEquals("stale_accel",c.gyroscope(1_000_000_000L,listOf(0.0,0.0,0.0))!!.reason)
  c.accelerometer(1_000_000_000L,listOf(9.80665,0.0,0.0)) // old input ignored
  assertNotEquals("dynamic_gyro",c.gyroscope(1_020_000_000L,listOf(0.0,0.0,0.0))!!.reason)
  assertTrue(c.gyroscope(1_030_000_000L,listOf(31.0,0.0,0.0))!!.reason.contains("reinitialize"))
  assertTrue(c.gyroscope(1_040_000_000L,listOf(0.0,0.0,0.0))!!.reason.contains("reinitialize"))
 }
 @Test fun turningWithRealRollUsesCoupledBodyRates() {
  for(duration in listOf(5,20)) {
   val c=VehicleAttitudeEstimator(listOf(1.0,0.0,0.0,0.0))
   var maxError=0.0
   for(i in 0..duration*100) {
    val roll=min(i,300)*0.01*PI/180
    val mid=max(0.0, min(i-0.5,300.0))*0.01*PI/180
    val t=1_000_000_000L+i*10_000_000L
    // Rz(yaw)*Rx(roll), v=19.6133m/s, yawDot=0.2: a=v*yawDot=0.4g.
    c.accelerometer(t,listOf(0.0,9.80665*(0.4*cos(roll)+sin(roll)),9.80665*(cos(roll)-0.4*sin(roll))))
    val o=c.gyroscope(t,listOf(if(i in 1..300)PI/180 else 0.0,0.2*sin(mid),0.2*cos(mid)))!!
    val q=o.wxyz
    val measured=atan2(2*(q[0]*q[1]+q[2]*q[3]),1-2*(q[1]*q[1]+q[2]*q[2]))
    maxError=max(maxError,abs(measured-roll)*180/PI)
   }
   println("turn_roll_${duration}s maxErrorDeg=$maxError")
   assertTrue(maxError<=1)
  }
 }
 @Test fun missingReferenceReportsAgeAndResidualDrift() {
  val c=VehicleAttitudeEstimator(listOf(1.0,0.0,0.0,0.0))
  var o: VehicleAttitudeEstimator.Output?=null
  for(i in 0..6000) {
   val t=1_000_000_000L+i*10_000_000L
   if(i<200) c.accelerometer(t,listOf(0.0,0.0,9.80665))
   o=c.gyroscope(t,listOf(0.001,0.0001*sin(i.toDouble()),0.0))
  }
  val drift=2*atan2(o!!.wxyz[1],o.wxyz[0])*180/PI
  println("60s lost-reference residual rollDeg=$drift trustedAge=${o.trustedAgeSeconds}")
  assertTrue(o.trustedAgeSeconds!!>50); assertEquals("stale_accel",o.reason)
  assertTrue(abs(drift)>1) // Explicit observability limitation, not a hidden angle clamp.
 }
 @Test fun realPitchDuringSustainedAccelerationIsNotFrozen() {
  for(duration in listOf(5,20)) {
   val c=VehicleAttitudeEstimator(listOf(1.0,0.0,0.0,0.0))
   for(i in 0..duration*100) {
    val theta=min(i,300)*0.01*PI/180
    val t=1_000_000_000L+i*10_000_000L
    // R^T*(navigation acceleration + upward specific force).
    c.accelerometer(t,listOf(9.80665*(0.3*cos(theta)-sin(theta)),0.0,9.80665*(0.3*sin(theta)+cos(theta))))
    val o=c.gyroscope(t,listOf(0.0,if(i in 1..300)PI/180 else 0.0,0.0))!!
    assertEquals(theta*180/PI,2*atan2(o.wxyz[2],o.wxyz[0])*180/PI,1.0)
   }
  }
 }
 @Test fun sustainedAccelerationDoesNotTimeoutIntoFalseTilt() {
  for (duration in listOf(5,20)) for (axis in 0..1) {
   val c=VehicleAttitudeEstimator(listOf(1.0,0.0,0.0,0.0))
   for (i in 0..duration*100) {
    val a=mutableListOf(0.0,0.0,9.80665); a[axis]=9.80665*if(axis==0)0.3 else 0.4
    val t=1_000_000_000L+i*10_000_000L
    c.accelerometer(t,a)
    // Lateral acceleration v*w with v=19.6133m/s and yaw rate 0.2rad/s.
    val o=c.gyroscope(t,listOf(0.0,0.0,if(axis==1)0.2 else 0.0))!!
    assertTrue(2*acos(sqrt(o.wxyz[0].pow(2)+o.wxyz[3].pow(2)).coerceIn(0.0,1.0))*180/PI<1)
   }
  }
 }
 @Test fun realSlowTiltAndHoldIsPreserved() {
  val c=VehicleAttitudeEstimator(listOf(1.0,0.0,0.0,0.0))
  var out: VehicleAttitudeEstimator.Output?=null
  for(i in 0..2000) {
   val theta=min(i,500)*0.01*PI/300
   val t=1_000_000_000L+i*10_000_000L
   c.accelerometer(t,listOf(0.0,9.80665*sin(theta),9.80665*cos(theta)))
   out=c.gyroscope(t,listOf(if(i in 1..500)PI/300 else 0.0,0.0,0.0))
  }
  assertEquals(3.0,2*atan2(out!!.wxyz[1],out.wxyz[0])*180/PI,0.1)
 }
 @Test fun duplicateAndLongGapAreExplicit() {
  val c=VehicleAttitudeEstimator(listOf(1.0,0.0,0.0,0.0))
  c.gyroscope(1L,listOf(0.0,0.0,0.0))
  assertNull(c.gyroscope(1L,listOf(0.0,0.0,0.0)))
  assertTrue(c.gyroscope(1_000_000_001L,listOf(0.0,0.0,0.0))!!.reason.contains("reinitialize"))
 }
}
