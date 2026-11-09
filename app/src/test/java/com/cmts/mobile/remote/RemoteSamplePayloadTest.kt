package com.cmts.mobile.remote

import com.cmts.mobile.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSamplePayloadTest {
    @Test
    fun fullSnapshotKeepsCoreAndAddsNestedSources() {
        val samples = mapOf(
            "phone.accel" to Sample("phone.accel", 1L, null, listOf(0.1, 0.2, 9.8)),
            "vehicle.accel" to Sample("vehicle.accel", 1L, null, listOf(0.2, -0.1, 9.8)),
            "phone.gyro" to Sample("phone.gyro", 2L, null, listOf(0.01, 0.02, 0.03)),
            "vehicle.gyro" to Sample("vehicle.gyro", 2L, null, listOf(0.02, -0.01, 0.03)),
            "vehicle.attitude" to Sample("vehicle.attitude", 3L, null, listOf(1.0, 2.0, 3.0)),
            "phone.attitude" to Sample("phone.attitude", 3L, null, listOf(10.0, 20.0, 30.0, 1.5)),
            "phone.rotation_vector" to Sample("phone.rotation_vector", 3L, null, listOf(0.0, 0.0, 0.0, 1.0, 0.1)),
            "phone.gps" to Sample(
                "phone.gps",
                4L,
                1000L,
                listOf(39.1, 117.2, 10.0, 5.0, 90.0, 2.0, 3.0, 0.5),
            ),
            "phone.satellites" to Sample("phone.satellites", 5L, null, listOf(18.0, 12.0)),
            "phone.pressure" to Sample("phone.pressure", 6L, null, listOf(1013.25)),
        )
        val payload = RemoteSamplePayload.build(
            seq = 7L,
            measureTimeMs = 1234L,
            receiveTimeMs = 5678L,
            vehicleLabel = "Note20",
            samples = samples,
            gps = samples["phone.gps"],
            accel = samples["vehicle.accel"],
            gyro = samples["vehicle.gyro"],
        )

        assertEquals(7L, payload.getLong("seq"))
        assertEquals(0.2, payload.getDouble("ax"), 0.0)
        assertEquals(18.0, payload.getJSONObject("phone.satellites").getDouble("visible"), 0.0)
        assertEquals(12.0, payload.getJSONObject("phone.satellites").getDouble("used"), 0.0)
        assertEquals(0.03, payload.getJSONObject("phone.gyro").getDouble("z"), 0.0)
        assertEquals(1013.25, payload.getDouble("phone.pressure"), 0.0)
        assertEquals(5.0, payload.getJSONObject("phone.gps").getDouble("speed"), 0.0)
        // nested gyro stays rad/s; core yawRateDps is deg/s from gyro Z
        assertEquals(Math.toDegrees(0.03), payload.getDouble("yawRateDps"), 1e-9)
        assertEquals(1.0, payload.getJSONObject("vehicle.attitude").getDouble("roll"), 0.0)
        assertEquals(10.0, payload.getJSONObject("phone.attitude").getDouble("pitch"), 0.0)
        assertEquals(1.0, payload.getJSONObject("phone.rotation_vector").getDouble("w"), 0.0)
        assertTrue(payload.getJSONObject("valid").getBoolean("imu"))
        assertTrue(payload.getJSONObject("valid").getBoolean("gnss"))
    }

    @Test
    fun missingSourcesOmittedNoSilentZero() {
        val payload = RemoteSamplePayload.build(
            seq = 1L,
            measureTimeMs = null,
            receiveTimeMs = 1L,
            vehicleLabel = "x",
            samples = emptyMap(),
            gps = null,
            accel = null,
            gyro = null,
        )
        assertTrue(payload.isNull("ax"))
        assertTrue(payload.isNull("lat"))
        assertFalse(payload.has("phone.accel"))
        assertFalse(payload.has("phone.pressure"))
        assertFalse(payload.has("vehicle.gyro"))
        assertFalse(payload.getJSONObject("valid").getBoolean("imu"))
    }

    @Test
    fun presentSourceKeepsNullComponents() {
        val samples = mapOf(
            "phone.gps" to Sample("phone.gps", 1L, null, listOf(39.0, 117.0, null, null, null, 2.0, null, null)),
        )
        val payload = RemoteSamplePayload.build(
            seq = 1L,
            measureTimeMs = 1L,
            receiveTimeMs = 1L,
            vehicleLabel = "x",
            samples = samples,
            gps = samples["phone.gps"],
            accel = null,
            gyro = null,
        )
        val gps = payload.getJSONObject("phone.gps")
        assertEquals(39.0, gps.getDouble("lat"), 0.0)
        assertTrue(gps.isNull("alt"))
        assertTrue(gps.isNull("speed"))
        assertEquals(2.0, gps.getDouble("horizAcc"), 0.0)
        assertTrue(payload.isNull("speedKph"))
    }
}
