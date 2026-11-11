package com.cmts.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReplayMountZeroTest {
    private val reference = listOf(20.0, -10.0, 30.0)
    private val zero = MountZero(
        basis = MountBasis.stationary(reference),
        enabled = true,
        accel = listOf(0.1, 0.2, 9.7),
        gyro = listOf(0.01, -0.02, 0.03),
        attitude = reference,
        zeroedAtElapsedNanos = 123_000_000L,
    )

    /** Same keys CaptureService.stopRecordingSession writes. */
    private fun metadataFor(z: MountZero): JSONObject {
        val meta = JSONObject().put("status", "closed").put("mountZeroEnabled", z.enabled)
        if (z.enabled) {
            z.basis?.let { b ->
                fun vector(v: V3) = JSONArray(listOf(v.x, v.y, v.z))
                meta.put("finalMountBasis", JSONArray(listOf(vector(b.forward), vector(b.left), vector(b.up))))
            }
            meta.put("mountZeroAccel", JSONArray(z.accel))
            meta.put("mountZeroGyro", JSONArray(z.gyro))
            meta.put("mountZeroAttitude", JSONArray(z.attitude))
            meta.put("mountZeroAtElapsedNanos", z.zeroedAtElapsedNanos)
        }
        return meta
    }

    private val tNow = 5_000_000_000L
    private val rawAccel = gravityVehicleFromAttitude(25.0, -8.0).mapIndexed { i, g -> g + listOf(1.5, -0.4, 0.2)[i] }
    private val rawGyro = listOf(0.2, -0.1, 0.05)
    private val rawAttitude = listOf(25.0, -8.0, 40.0)
    private fun frame(z: MountZero) = ReplayFrame(
        tNow,
        mapOf(
            "vehicle.accel" to Sample("vehicle.accel", tNow - 10_000_000L, null, rawAccel.map { it as Double? }),
            "vehicle.gyro" to Sample("vehicle.gyro", tNow - 10_000_000L, null, rawGyro.map { it as Double? }),
            "vehicle.attitude" to Sample("vehicle.attitude", tNow - 10_000_000L, null, rawAttitude.map { it as Double? }),
            "phone.gps" to Sample("phone.gps", tNow - 50_000_000L, 1L, listOf(39.0, 117.0, 50.0, 10.0, 90.0)),
        ),
        z,
    )

    private fun channel(label: String) = replayChannels.single { it.label == label }

    @Test
    fun metadataWithZeroReconstructsMountZero() {
        val rebuilt = mountZeroFromMetadata(JSONObject(metadataFor(zero).toString()))
        assertTrue(rebuilt.enabled)
        assertEquals(zero.gyro, rebuilt.gyro)
        assertEquals(zero.attitude, rebuilt.attitude)
        assertEquals(zero.accel, rebuilt.accel)
        assertEquals(zero.zeroedAtElapsedNanos, rebuilt.zeroedAtElapsedNanos)
        assertEquals(zero.basis, rebuilt.basis)
    }

    @Test
    fun sessionWithoutZeroStaysRaw() {
        assertFalse(mountZeroFromMetadata(null).enabled)
        assertFalse(mountZeroFromMetadata(JSONObject().put("status", "closed")).enabled)
        assertFalse(mountZeroFromMetadata(JSONObject().put("mountZeroEnabled", false)).enabled)
        val f = frame(MountZero())
        assertEquals(rawAttitude[0], replayChannelValue(channel("横滚(侧倾) X °"), f)!!, 1e-9)
        assertEquals(rawAttitude[1], replayChannelValue(channel("俯仰 Y °"), f)!!, 1e-9)
        assertEquals(rawGyro[2] * 180.0 / Math.PI, replayChannelValue(channel("角速度 Z °/s"), f)!!, 1e-9)
        // Accel is gravity-removed like the Dashboard (no zero → no rotation).
        val linear = vehicleLinearAccel(rawAccel, null, rawAttitude)!!
        assertEquals(linear[0]!!, replayChannelValue(channel("线加速度 X m/s²"), f)!!, 1e-9)
    }

    @Test
    fun savedZeroMatchesLiveApplyEnabledMountZero() {
        val rebuilt = mountZeroFromMetadata(JSONObject(metadataFor(zero).toString()))
        val expected = applyEnabledMountZero(zero, rawAccel, rawGyro, rawAttitude, null)
        val f = frame(rebuilt)
        val axes = displayVehicleAxes(rebuilt, f.samples)
        for (i in 0..2) {
            assertEquals(expected.accel!![i]!!, axes.accel!![i]!!, 1e-9)
            assertEquals(expected.gyro!![i]!!, axes.gyro!![i]!!, 1e-9)
            assertEquals(expected.attitude!![i]!!, axes.attitude!![i]!!, 1e-9)
        }
        val labelsAtt = listOf("横滚(侧倾) X °", "俯仰 Y °", "偏航 Z °")
        val labelsAcc = listOf("线加速度 X m/s²", "线加速度 Y m/s²", "线加速度 Z m/s²")
        val labelsGyro = listOf("角速度 X °/s", "角速度 Y °/s", "角速度 Z °/s")
        for (i in 0..2) {
            assertEquals(expected.attitude!![i]!!, replayChannelValue(channel(labelsAtt[i]), f)!!, 1e-9)
            assertEquals(expected.accel!![i]!!, replayChannelValue(channel(labelsAcc[i]), f)!!, 1e-9)
            assertEquals(expected.gyro!![i]!! * 180.0 / Math.PI, replayChannelValue(channel(labelsGyro[i]), f)!!, 1e-9)
        }
        // Rotated values really differ from raw for this tilted install.
        assertNotEquals(rawAttitude[0], replayChannelValue(channel("横滚(侧倾) X °"), f)!!, 1e-3)
        // GNSS channels are never rotated.
        assertEquals(36.0, replayChannelValue(channel("速度 km/h"), f)!!, 1e-9)
        assertEquals(90.0, replayChannelValue(channel("航向 °"), f)!!, 1e-9)
        assertEquals(50.0, replayChannelValue(channel("高度 m"), f)!!, 1e-9)
    }

    @Test
    fun staleSamplesAreBlankLikeDashboard() {
        val f = frame(MountZero()).copy(nanos = tNow + 4_000_000_000L)
        assertNull(replayChannelValue(channel("俯仰 Y °"), f))
        assertNull(replayChannelValue(channel("速度 km/h"), f))
    }

    @Test
    fun replayChannelsOnlyUseLoadedSources() {
        assertTrue(replayChannels.all { it.source in REPLAY_SOURCES })
        assertEquals(listOf("vehicle.attitude"), replayChannels.filter { it.label.startsWith("俯仰") }.map { it.source })
    }

    @Test
    fun replayReadLoadsSavedZeroAndRotationVector() {
        val d = Files.createTempDirectory("replay-zero").toFile()
        try {
            File(d, "closed.marker").writeText("")
            File(d, "metadata.json").writeText(metadataFor(zero).toString(2))
            File(d, "samples.csv").writeText(
                "header\n" +
                    "1,vehicle.attitude,100,100,,ok,25,-8,40\n" +
                    "2,phone.rotation_vector,200,200,,accuracy_3,0,0,0,1,0.1\n" +
                    "3,vehicle.gyro,300000000,300000000,,ok,0.2,-0.1,0.05\n",
            )
            val r = Replay.read(d)
            assertTrue(r.mountZero.enabled)
            assertTrue(r.frames.all { it.mountZero.enabled })
            assertNotNull(r.frames.last().samples["phone.rotation_vector"])
        } finally { d.deleteRecursively() }

        val plain = Files.createTempDirectory("replay-plain").toFile()
        try {
            File(plain, "closed.marker").writeText("")
            File(plain, "metadata.json").writeText(JSONObject().put("mountZeroEnabled", false).toString())
            File(plain, "samples.csv").writeText("header\n1,vehicle.attitude,100,100,,ok,25,-8,40\n")
            assertFalse(Replay.read(plain).frames.single().mountZero.enabled)
        } finally { plain.deleteRecursively() }
    }
}
