package com.cmts.mobile.remote

import com.cmts.mobile.Sample
import org.json.JSONObject

/**
 * Builds stream.sample payload: typed core fields + Live source objects Win flattens.
 * Missing sources are omitted (no invent). Present sources use JSON null for missing components.
 */
internal object RemoteSamplePayload {
    private val ACCEL_XYZ = listOf("x", "y", "z")
    private val GYRO_XYZ = listOf("x", "y", "z")
    private val VEHICLE_ATTITUDE = listOf("roll", "pitch", "yaw")
    private val PHONE_ATTITUDE = listOf("pitch", "roll", "azimuth", "headingAcc")
    private val ROTATION_VECTOR = listOf("x", "y", "z", "w", "headingAcc")
    private val GPS = listOf("lat", "lon", "alt", "speed", "bearing", "horizAcc", "vertAcc", "speedAcc")
    private val SATELLITES = listOf("visible", "used")

    fun build(
        seq: Long,
        measureTimeMs: Long?,
        receiveTimeMs: Long,
        vehicleLabel: String,
        samples: Map<String, Sample>,
        gps: Sample?,
        accel: Sample?,
        gyro: Sample?,
    ): JSONObject {
        val gnssValid = gps != null && gps.values.getOrNull(0) != null && gps.values.getOrNull(1) != null
        val speedValid = gps?.values?.getOrNull(3) != null
        val imuValid = accel != null && accel.values.size >= 3
        val headingValid = gps?.values?.getOrNull(4) != null

        val payload = JSONObject()
        payload.put("seq", seq)
        RemoteProtocol.putNullableLong(payload, "measureTimeMs", measureTimeMs)
        payload.put("receiveTimeMs", receiveTimeMs)
        payload.put("vehicleLabel", vehicleLabel)

        val speedKph = gps?.values?.getOrNull(3)?.times(3.6)
        RemoteProtocol.putNullable(payload, "speedKph", if (speedValid) speedKph else null)
        RemoteProtocol.putNullable(payload, "ax", if (imuValid) accel?.values?.getOrNull(0) else null)
        RemoteProtocol.putNullable(payload, "ay", if (imuValid) accel?.values?.getOrNull(1) else null)
        RemoteProtocol.putNullable(payload, "az", if (imuValid) accel?.values?.getOrNull(2) else null)
        val yawDps = gyro?.values?.getOrNull(2)?.let { Math.toDegrees(it) }
        RemoteProtocol.putNullable(payload, "yawRateDps", if (gyro != null) yawDps else null)
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

        putVector(payload, "phone.accel", samples["phone.accel"], ACCEL_XYZ)
        putVector(payload, "vehicle.accel", samples["vehicle.accel"], ACCEL_XYZ)
        putVector(payload, "phone.gyro", samples["phone.gyro"], GYRO_XYZ)
        putVector(payload, "vehicle.gyro", samples["vehicle.gyro"], GYRO_XYZ)
        putVector(payload, "vehicle.attitude", samples["vehicle.attitude"], VEHICLE_ATTITUDE)
        putVector(payload, "phone.attitude", samples["phone.attitude"], PHONE_ATTITUDE)
        putVector(payload, "phone.rotation_vector", samples["phone.rotation_vector"], ROTATION_VECTOR)
        putVector(payload, "phone.gps", samples["phone.gps"], GPS)
        putVector(payload, "phone.satellites", samples["phone.satellites"], SATELLITES)
        putScalar(payload, "phone.pressure", samples["phone.pressure"])
        return payload
    }

    /** Units: accel m/s2 (incl. gravity); gyro rad/s; attitude deg; pressure mbar; gps speed m/s. */
    fun putVector(payload: JSONObject, key: String, sample: Sample?, names: List<String>) {
        if (sample == null) return
        val child = JSONObject()
        names.forEachIndexed { i, name ->
            RemoteProtocol.putNullable(child, name, sample.values.getOrNull(i))
        }
        payload.put(key, child)
    }

    fun putScalar(payload: JSONObject, key: String, sample: Sample?) {
        if (sample == null) return
        RemoteProtocol.putNullable(payload, key, sample.values.getOrNull(0))
    }
}
