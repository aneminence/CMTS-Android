package com.cmts.mobile

import org.junit.Assert.*
import org.junit.Test

class MapMathTest {
 @Test fun mercatorOriginAndLatitudeLimits() {
  assertEquals(WorldPoint(0.5, 0.5), project(0.0, 0.0))
  assertTrue(project(90.0, 180.0).y.isFinite())
  assertEquals(project(85.05112878, 0.0), project(90.0, 0.0))
 }
 @Test fun wrapAcrossDatelineUsesShortPath() {
  assertEquals(0.99, wrapX(-0.01), 1e-9)
  assertEquals(0.02, wrappedDelta(0.01 - 0.99), 1e-9)
 }
 @Test fun durationUsesMonotonicTimeAndClampsNegative() {
  assertEquals("01:01:01", elapsedLabel(100L, 3661_000_000_100L))
  assertEquals("00:00:00", elapsedLabel(100L, 0L))
 }
}
