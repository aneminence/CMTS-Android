package com.cmts.mobile.remote

import com.cmts.mobile.MountZero
import com.cmts.mobile.Sample
import com.cmts.mobile.applyEnabledMountZero
import org.json.JSONObject

/**
 * Builds stream.sample payload: typed core fields + vehicle.* Live sources Win flattens.
 * Nested phone.* omitted (Available is vehicle-only). Missing sources omitted (no invent).
 * Present sources use JSON null for missing components.
 *
 * When [mountZero].enabled, vehicle.* and matching core axis fields use the same
 * Dashboard mount-zero transform (zeroed vehicle coordinates). When disabled, raw.
 */
internal object RemoteSamplePayload {
    private val ACCEL_XYZ = listOf("x", "y", "z")
    private val GYRO_XYZ = listOf("x", "y", "z")
    private val VEHICLE_ATTITUDE = listOf("roll", "pitch", "yaw")

    fun build(
        seq: Long,
        measureTimeMs: Long?,
        receiveTimeMs: Long,
        vehicleLabel: String,
        samples: Map<String, Sample>,
        gps: Sample?,
        accel: Sample?,
        gyro: Sample?,
        mountZero: MountZero = MountZero(),
    ): JSONObject {
        val vehicleAccelSample = samples["vehicle.accel"]
        val vehicleGyroSample = samples["vehicle.gyro"]
        val vehicleAttitudeSample = samples["vehicle.attitude"]
        val rotation = samples["phone.rotation_vector"]

        val zeroed = if (mountZero.enabled) {
            applyEnabledMountZero(
                zero = mountZero,
                vehicleAccel = vehicleAccelSample?.values,
                vehicleGyro = vehicleGyroSample?.values,
                vehicleAttitude = vehicleAttitudeSample?.values,
                rotationVector = rotation?.values,
            )
        } else {
            null
        }

        // Nested vehicle values: transformed when zeroed, else raw sample values.
        val nestedAccel = if (zeroed != null && vehicleAccelSample != null) zeroed.accel else vehicleAccelSample?.values
        val nestedGyro = if (zeroed != null && vehicleGyroSample != null) zeroed.gyro else vehicleGyroSample?.values
        val nestedAttitude = if (zeroed != null && vehicleAttitudeSample != null) zeroed.attitude else vehicleAttitudeSample?.values

        // Core ax/ay/az / yawRate follow preferred vehicle path when present; else phone fallback stays raw.
        val coreAccelValues = when {
            vehicleAccelSample != null && zeroed != null -> zeroed.accel
            vehicleAccelSample != null -> vehicleAccelSample.values
            else -> accel?.values // may be phone.accel fallback from caller
        }
        val coreGyroValues = when {
            vehicleGyroSample != null && zeroed != null -> zeroed.gyro
            vehicleGyroSample != null -> vehicleGyroSample.values
            else -> gyro?.values
        }

        val gnssValid = gps != null && gps.values.getOrNull(0) != null && gps.values.getOrNull(1) != null
        val speedValid = gps?.values?.getOrNull(3) != null
        val imuSample = vehicleAccelSample ?: accel
        val imuValid = imuSample != null && imuSample.values.size >= 3
        val headingValid = gps?.values?.getOrNull(4) != null

        val payload = JSONObject()
        payload.put("seq", seq)
        RemoteProtocol.putNullableLong(payload, "measureTimeMs", measureTimeMs)
        payload.put("receiveTimeMs", receiveTimeMs)
        payload.put("vehicleLabel", vehicleLabel)

        val speedKph = gps?.values?.getOrNull(3)?.times(3.6)
        RemoteProtocol.putNullable(payload, "speedKph", if (speedValid) speedKph else null)
        RemoteProtocol.putNullable(payload, "ax", if (imuValid) coreAccelValues?.getOrNull(0) else null)
        RemoteProtocol.putNullable(payload, "ay", if (imuValid) coreAccelValues?.getOrNull(1) else null)
        RemoteProtocol.putNullable(payload, "az", if (imuValid) coreAccelValues?.getOrNull(2) else null)
        val yawDps = coreGyroValues?.getOrNull(2)?.let { Math.toDegrees(it) }
        RemoteProtocol.putNullable(payload, "yawRateDps", if (coreGyroValues != null) yawDps else null)
        RemoteProtocol.putNullable(payload, "lat", if (gnssValid) gps?.values?.getOrNull(0) else null)
        RemoteProtocol.putNullable(payload, "lon", if (gnssValid) gps?.values?.getOrNull(1) else null)
        RemoteProtocol.putNullable(payload, "altM", if (gnssValid) gps?.values?.getOrNull(2) else null)
        RemoteProtocol.putNullable(payload, "headingDeg", if (headingValid) gps?.values?.getOrNull(4) else null)
        payload.put(
            "valid",
            JSONObject()
                .put("gnss", gnssValid)
                .put("speed", speedValid)
                .put("imu", imuValid)
                .put("heading", headingValid),
        )

        // Nested Live sources: vehicle.* only (phone.* omitted for Available).
        putVectorValues(payload, "vehicle.accel", vehicleAccelSample != null, nestedAccel, ACCEL_XYZ)
        putVectorValues(payload, "vehicle.gyro", vehicleGyroSample != null, nestedGyro, GYRO_XYZ)
        putVectorValues(payload, "vehicle.attitude", vehicleAttitudeSample != null, nestedAttitude, VEHICLE_ATTITUDE)
        return payload
    }

    /** Units when raw: accel m/s2 (incl. gravity); gyro rad/s; attitude deg.
     *  When mountZero enabled: accel is gravity-removed linear in install vehicle axes. */
    fun putVector(payload: JSONObject, key: String, sample: Sample?, names: List<String>) {
        if (sample == null) return
        putVectorValues(payload, key, true, sample.values, names)
    }

    fun putVectorValues(
        payload: JSONObject,
        key: String,
        present: Boolean,
        values: List<Double?>?,
        names: List<String>,
    ) {
        if (!present) return
        val child = JSONObject()
        names.forEachIndexed { i, name ->
            RemoteProtocol.putNullable(child, name, values?.getOrNull(i))
        }
        payload.put(key, child)
    }

    fun putScalar(payload: JSONObject, key: String, sample: Sample?) {
        if (sample == null) return
        RemoteProtocol.putNullable(payload, key, sample.values.getOrNull(0))
    }
}
