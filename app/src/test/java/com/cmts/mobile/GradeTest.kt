package com.cmts.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class GradeTest {
 @Test fun noseDownIsNegativeGrade() {
  assertEquals("-100.0%", gradeLabel("45.0"))
  assertEquals("+100.0%", gradeLabel("-45.0"))
  assertEquals("0.0%", gradeLabel("0.0"))
  assertEquals("—", gradeLabel("90.0"))
  assertEquals("—", gradeLabel("—"))
 }
}
