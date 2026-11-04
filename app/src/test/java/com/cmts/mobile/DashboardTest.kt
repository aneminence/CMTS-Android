package com.cmts.mobile
import org.junit.Assert.*
import org.junit.Test
class DashboardTest {
 @Test fun unitsAndMissing() {
  assertEquals("36.0", dashboardValue(10.0, 3.6))
  assertEquals("180.0", dashboardValue(Math.PI, 180.0 / Math.PI))
  assertEquals("—", dashboardValue(null))
  assertEquals("—", dashboardValue(Double.NaN))
 }
 @Test fun staleOrStoppedIsNotLive() {
  val s = Sample("phone.gps", 1_000_000_000L, null, emptyList())
  assertTrue(isFresh(s,2_000_000_000L,true))
  assertFalse(isFresh(s,4_000_000_001L,true))
  assertFalse(isFresh(s,2_000_000_000L,false))
  assertFalse(isFresh(s,0L,true))
 }
}
