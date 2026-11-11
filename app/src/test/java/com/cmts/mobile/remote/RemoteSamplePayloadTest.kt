package com.cmts.mobile.remote

import com.cmts.mobile.MountZero
import com.cmts.mobile.Sample
import com.cmts.mobile.STANDARD_GRAVITY
import com.cmts.mobile.applyEnabledMountZero
import com.cmts.mobile.captureMountZero
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RemoteSamplePayloadTest {
    @Test fun derivedEstimateDoesNotReplaceLegacyAngles() {
        val samples=mapOf(
            "vehicle.attitude" to Sample("vehicle.attitude",1,null,listOf(10.0,20.0,30.0)),
            "estimate.attitude" to Sample("estimate.attitude",2,null,listOf(1.0,2.0,3.0),"experimental"))
        val p=RemoteSamplePayload.build(1,null,1,"test",samples,null,null,null)
        assertEquals(10.0,p.getJSONObject("vehicle.attitude").getDouble("roll"),0.0)
        assertEquals(1.0,p.getJSONObject("estimate.attitude").getDouble("roll"),0.0)
        assertEquals(2L,p.getJSONObject("estimate.attitude").getLong("measurementNanos"))
    }
    @Test
    fun fullSnapshotKeepsCoreAndVehicleNestedOnly() {
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
        assertEquals(18.0 * 0 + 5.0 * 3.6, payload.getDouble("speedKph"), 0.0)
        assertFalse(payload.has("phone.accel"))
        assertFalse(payload.has("phone.gyro"))
        assertFalse(payload.has("phone.attitude"))
        assertFalse(payload.has("phone.rotation_vector"))
        assertFalse(payload.has("phone.gps"))
        assertFalse(payload.has("phone.satellites"))
        assertFalse(payload.has("phone.pressure"))
        assertEquals(0.03, payload.getJSONObject("vehicle.gyro").getDouble("z"), 0.0)
        // nested gyro stays rad/s; core yawRateDps is deg/s from gyro Z
        assertEquals(Math.toDegrees(0.03), payload.getDouble("yawRateDps"), 1e-9)
        assertEquals(1.0, payload.getJSONObject("vehicle.attitude").getDouble("roll"), 0.0)
        assertEquals(0.2, payload.getJSONObject("vehicle.accel").getDouble("x"), 0.0)
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
        assertFalse(payload.has("vehicle.accel"))
        assertFalse(payload.has("vehicle.gyro"))
        assertFalse(payload.getJSONObject("valid").getBoolean("imu"))
    }

    @Test
    fun presentVehicleKeepsNullComponents() {
        val samples = mapOf(
            "vehicle.accel" to Sample("vehicle.accel", 1L, null, listOf(0.5, null, 9.8)),
            "phone.gps" to Sample("phone.gps", 1L, null, listOf(39.0, 117.0, null, null, null, 2.0, null, null)),
        )
        val payload = RemoteSamplePayload.build(
            seq = 1L,
            measureTimeMs = 1L,
            receiveTimeMs = 1L,
            vehicleLabel = "x",
            samples = samples,
            gps = samples["phone.gps"],
            accel = samples["vehicle.accel"],
            gyro = null,
        )
        val accel = payload.getJSONObject("vehicle.accel")
        assertEquals(0.5, accel.getDouble("x"), 0.0)
        assertTrue(accel.isNull("y"))
        assertEquals(9.8, accel.getDouble("z"), 0.0)
        assertFalse(payload.has("phone.gps"))
        assertTrue(payload.isNull("speedKph"))
        assertEquals(39.0, payload.getDouble("lat"), 0.0)
    }

    @Test
    fun mountZeroOffKeepsRawVehicleFields() {
        val samples = baseImuSamples()
        val raw = RemoteSamplePayload.build(
            seq = 1L,
            measureTimeMs = 1L,
            receiveTimeMs = 1L,
            vehicleLabel = "x",
            samples = samples,
            gps = null,
            accel = samples["vehicle.accel"],
            gyro = samples["vehicle.gyro"],
            mountZero = MountZero(),
        )
        assertEquals(0.5, raw.getDouble("ax"), 0.0)
        assertEquals(0.5, raw.getJSONObject("vehicle.accel").getDouble("x"), 0.0)
        assertEquals(STANDARD_GRAVITY, raw.getJSONObject("vehicle.accel").getDouble("z"), 1e-9)
        assertEquals(0.03, raw.getJSONObject("vehicle.gyro").getDouble("z"), 0.0)
        assertEquals(12.0, raw.getJSONObject("vehicle.attitude").getDouble("yaw"), 0.0)
    }

    @Test
    fun mountZeroOnTransformsVehicleAndCoreAxes() {
        val samples = baseImuSamples()
        val zero = captureMountZero(
            samples["vehicle.accel"],
            samples["vehicle.gyro"],
            samples["vehicle.attitude"],
            99L,
        )!!
        assertTrue(zero.enabled)

        val off = RemoteSamplePayload.build(
            seq = 1L,
            measureTimeMs = 1L,
            receiveTimeMs = 1L,
            vehicleLabel = "x",
            samples = samples,
            gps = null,
            accel = samples["vehicle.accel"],
            gyro = samples["vehicle.gyro"],
            mountZero = MountZero(),
        )
        val on = RemoteSamplePayload.build(
            seq = 2L,
            measureTimeMs = 1L,
            receiveTimeMs = 1L,
            vehicleLabel = "x",
            samples = samples,
            gps = null,
            accel = samples["vehicle.accel"],
            gyro = samples["vehicle.gyro"],
            mountZero = zero,
        )

        // At capture pose: gyro bias-subtracted ~0; attitude relative ~0.
        assertEquals(0.0, on.getJSONObject("vehicle.gyro").getDouble("x"), 1e-9)
        assertEquals(0.0, on.getJSONObject("vehicle.gyro").getDouble("y"), 1e-9)
        assertEquals(0.0, on.getJSONObject("vehicle.gyro").getDouble("z"), 1e-9)
        assertEquals(0.0, on.getDouble("yawRateDps"), 1e-9)
        assertEquals(0.0, on.getJSONObject("vehicle.attitude").getDouble("roll"), 1e-6)
        assertEquals(0.0, on.getJSONObject("vehicle.attitude").getDouble("pitch"), 1e-6)
        assertEquals(0.0, on.getJSONObject("vehicle.attitude").getDouble("yaw"), 1e-6)

        // Accel becomes gravity-removed (+ projected); differs from raw-with-gravity.
        assertNotEquals(off.getJSONObject("vehicle.accel").getDouble("z"), on.getJSONObject("vehicle.accel").getDouble("z"), 0.5)
        assertTrue(abs(on.getDouble("az")) < 0.5)

        // Matches shared helper used by Dashboard.
        val expected = applyEnabledMountZero(
            zero,
            samples["vehicle.accel"]!!.values,
            samples["vehicle.gyro"]!!.values,
            samples["vehicle.attitude"]!!.values,
            samples["phone.rotation_vector"]!!.values,
        )
        assertEquals(expected.accel!![0]!!, on.getDouble("ax"), 1e-9)
        assertEquals(expected.accel!![1]!!, on.getDouble("ay"), 1e-9)
        assertEquals(expected.accel!![2]!!, on.getDouble("az"), 1e-9)
        assertEquals(expected.gyro!![0]!!, on.getJSONObject("vehicle.gyro").getDouble("x"), 1e-9)
        assertEquals(expected.attitude!![2]!!, on.getJSONObject("vehicle.attitude").getDouble("yaw"), 1e-9)
    }

    private fun baseImuSamples(): Map<String, Sample> = mapOf(
        "vehicle.accel" to Sample("vehicle.accel", 1L, null, listOf(0.5, -0.2, STANDARD_GRAVITY)),
        "vehicle.gyro" to Sample("vehicle.gyro", 1L, null, listOf(0.01, -0.02, 0.03)),
        "vehicle.attitude" to Sample("vehicle.attitude", 1L, null, listOf(0.0, 0.0, 12.0)),
        // Identity RV → gravity device [0,0,g] → vehicle same; linear ≈ [0.5,-0.2,0]
        "phone.rotation_vector" to Sample("phone.rotation_vector", 1L, null, listOf(0.0, 0.0, 0.0, 1.0)),
    )
}
