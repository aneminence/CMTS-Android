package com.cmts.mobile

import org.junit.Assert.*
import org.junit.Test

class AttitudeTest {
 @Test fun vehicleMountIsRightHandedAndNorthAlignedFlatIsZero() {
  assertEquals(listOf(2.0, -1.0, 3.0), vehicleAxes(listOf(1.0, 2.0, 3.0)))
  val flat = vehicleAttitude(floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f))
  flat.forEach { assertEquals(0.0, it, 1e-9) }
 }
 @Test fun vehicleYawPositiveLeftAndRollPositiveLeftSideUp() {
  val west = vehicleAttitude(floatArrayOf(0f,-1f,0f, 1f,0f,0f, 0f,0f,1f))
  assertEquals(90.0, west[2], 1e-9)
  val tilted = vehicleAttitude(floatArrayOf(0.8660254f,0f,0.5f, 0f,1f,0f, -0.5f,0f,0.8660254f))
  assertEquals(30.0, tilted[0], 1e-5)
 }
 @Test fun androidAnglesMapToNamedAxesAndDegrees() {
  val result = attitudeDegrees(-Math.PI / 2, Math.PI / 6, -Math.PI / 4)
  assertEquals(30.0, result[0], 1e-9)
  assertEquals(-45.0, result[1], 1e-9)
  assertEquals(270.0, result[2], 1e-9)
 }
 @Test fun azimuthWrapAndFlatReference() {
  assertEquals(listOf(0.0, 0.0, 0.0), attitudeDegrees(0.0, 0.0, 0.0))
  assertEquals(0.0, attitudeDegrees(2 * Math.PI, 0.0, 0.0)[2], 1e-9)
 }

 @Test fun mountZeroGyroAndAttitudeNearZeroAfterCapture() {
  val accel = Sample("vehicle.accel", 1L, null, listOf(0.05, -0.1, 9.75))
  val gyro = Sample("vehicle.gyro", 1L, null, listOf(0.01, -0.02, 0.03))
  val attitude = Sample("vehicle.attitude", 1L, null, listOf(2.0, -5.0, 123.0))
  val z = captureMountZero(accel, gyro, attitude, 42L)!!
  assertTrue(z.enabled)
  assertEquals(42L, z.zeroedAtElapsedNanos)
  assertEquals(listOf(0.0, 0.0, 0.0), applyMountZeroLinear(gyro.values, z.gyro))
  val rel = applyMountZeroAttitude(attitude.values, z.attitude)
  assertEquals(0.0, rel[0]!!, 1e-12)
  assertEquals(0.0, rel[1]!!, 1e-12)
  assertEquals(0.0, rel[2]!!, 1e-12)
 }
 @Test fun captureMountZeroWorksWithoutAccel() {
  val z = captureMountZero(
   null,
   Sample("vehicle.gyro", 1L, null, listOf(0.0, 0.0, 0.0)),
   Sample("vehicle.attitude", 1L, null, listOf(1.0, 2.0, 3.0)),
   7L
  )!!
  assertTrue(z.enabled)
  assertTrue(z.accel.isEmpty())
 }
 @Test fun flatIdentityRotationRemovesGravityFromVehicleAccel() {
  // Identity RV quaternion (0,0,0,1); gravity device = [0,0,g]; vehicle same.
  val rv = listOf<Double?>(0.0, 0.0, 0.0, 1.0)
  val vehicleAccel = listOf<Double?>(0.0, 0.0, STANDARD_GRAVITY)
  val linear = vehicleLinearAccel(vehicleAccel, rv, null)!!
  assertEquals(0.0, linear[0]!!, 1e-9)
  assertEquals(0.0, linear[1]!!, 1e-9)
  assertEquals(0.0, linear[2]!!, 1e-9)
 }
 @Test fun tiltedGravityStillCancelsWhenAccelMatchesEstimate() {
  // 30 deg roll about vehicle X via attitude fallback
  val roll = 30.0
  val pitch = 0.0
  val gVeh = gravityVehicleFromAttitude(roll, pitch)
  val vehicleAccel = gVeh.map { it as Double? }
  val attitude = listOf<Double?>(roll, pitch, 0.0)
  val linear = vehicleLinearAccel(vehicleAccel, null, attitude)!!
  assertEquals(0.0, linear[0]!!, 1e-9)
  assertEquals(0.0, linear[1]!!, 1e-9)
  assertEquals(0.0, linear[2]!!, 1e-9)
 }
 @Test fun linearAccelReportsMotionAfterGravityRemoval() {
  val rv = listOf<Double?>(0.0, 0.0, 0.0, 1.0)
  val vehicleAccel = listOf<Double?>(1.5, -0.5, STANDARD_GRAVITY)
  val linear = vehicleLinearAccel(vehicleAccel, rv, null)!!
  assertEquals(1.5, linear[0]!!, 1e-9)
  assertEquals(-0.5, linear[1]!!, 1e-9)
  assertEquals(0.0, linear[2]!!, 1e-9)
 }
 @Test fun gravityDeviceFromIdentityMatrix() {
  val g = gravityDeviceFromMatrix(floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f))!!
  assertEquals(0.0, g[0], 1e-9)
  assertEquals(0.0, g[1], 1e-9)
  assertEquals(STANDARD_GRAVITY, g[2], 1e-9)
 }
 @Test fun yawBiasWrapsToSignedRelative() {
  assertEquals(10.0, wrapSignedDegrees(370.0), 1e-12)
  assertEquals(-10.0, wrapSignedDegrees(-370.0), 1e-12)
  assertEquals(-20.0, applyMountZeroAttitude(listOf(0.0, 0.0, 350.0), listOf(0.0, 0.0, 10.0))[2]!!, 1e-12)
 }
 @Test fun applyEnabledMountZeroMatchesBiasAtCapture() {
  // Flat install: MountBasis ~ identity on vehicle axes; linear accel projects cleanly.
  val accel = Sample("vehicle.accel", 1L, null, listOf(0.5, -0.2, STANDARD_GRAVITY))
  val gyro = Sample("vehicle.gyro", 1L, null, listOf(0.01, -0.02, 0.03))
  val attitude = Sample("vehicle.attitude", 1L, null, listOf(0.0, 0.0, 12.0))
  val rv = listOf<Double?>(0.0, 0.0, 0.0, 1.0)
  val z = captureMountZero(accel, gyro, attitude, 1L)!!
  val axes = applyEnabledMountZero(z, accel.values, gyro.values, attitude.values, rv)
  assertEquals(0.0, axes.gyro!![0]!!, 1e-12)
  assertEquals(0.0, axes.gyro!![1]!!, 1e-12)
  assertEquals(0.0, axes.gyro!![2]!!, 1e-12)
  assertEquals(0.0, axes.attitude!![0]!!, 1e-6)
  assertEquals(0.0, axes.attitude!![1]!!, 1e-6)
  assertEquals(0.0, axes.attitude!![2]!!, 1e-6)
  assertEquals(0.5, axes.accel!![0]!!, 1e-5)
  assertEquals(-0.2, axes.accel!![1]!!, 1e-5)
  assertEquals(0.0, axes.accel!![2]!!, 1e-5)
 }
 @Test fun captureMountZeroRejectsIncomplete() {
  assertNull(captureMountZero(null, null, Sample("vehicle.attitude", 1L, null, listOf(0.0,0.0,0.0)), 1L))
  assertNull(captureMountZero(null, Sample("vehicle.gyro", 1L, null, listOf(0.0,0.0,0.0)), null, 1L))
  assertNull(captureMountZero(Sample("vehicle.accel", 1L, null, listOf(1.0, 2.0)), Sample("vehicle.gyro", 1L, null, listOf(0.0,0.0)), Sample("vehicle.attitude", 1L, null, listOf(0.0,0.0,0.0)), 1L))
 }
}
