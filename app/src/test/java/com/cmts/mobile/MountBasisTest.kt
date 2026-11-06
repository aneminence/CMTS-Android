package com.cmts.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class MountBasisTest {
 @Test fun uprightWindshieldMountIsLevelAtZero() {
  val angles=listOf(90.0,0.0,37.0)
  val basis=MountBasis.stationary(angles)
  basis.attitude(angles,angles)!!.forEach { assertEquals(0.0,it,1e-8) }
  assertEquals(1.0,basis.project(listOf(0.0,0.0,-1.0))!![0],1e-8)
 }
 @Test fun learnedForwardCanBeAcrossPhone() {
  val basis=MountBasis.from(V3(0.0,0.0,1.0),V3(0.0,1.0,0.0))!!
  val angles=basis.attitude(listOf(12.0,0.0,0.0),listOf(0.0,0.0,0.0))!!
  assertEquals(-12.0,angles[1],1e-8)
  assertEquals(0.0,angles[0],1e-8)
 }
}
