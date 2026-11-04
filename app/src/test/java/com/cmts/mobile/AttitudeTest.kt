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
}
