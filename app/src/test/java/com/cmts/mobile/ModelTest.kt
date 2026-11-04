package com.cmts.mobile
import org.junit.Assert.*
import org.junit.Test
class ModelTest {
 @Test fun actualRate() { val r = Rate(); assertNull(r.update(0)); assertEquals(2.0, r.update(500000000)!!, 0.0001); assertNull(r.update(500000000)) }
 @Test fun missingValuesStayEmpty() { assertEquals("1,phone.gps,10,20,,reported,", Sample("phone.gps",10,null,listOf(null)).csv(1,20)) }
 @Test(expected = IllegalArgumentException::class) fun rejectNan() { Sample("imu",0,null,listOf(Double.NaN)) }
}
