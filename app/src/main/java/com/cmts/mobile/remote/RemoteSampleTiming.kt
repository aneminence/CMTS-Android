package com.cmts.mobile.remote

/**
 * Stream timing helpers for protocol v0.1 core samples.
 * IMU drives emit cadence; subscribe.maxHz caps it; GNSS fields ride along.
 */
internal object RemoteSampleTiming {
    const val ABS_MAX_HZ = 100
    const val DEFAULT_MAX_HZ = 100

    /** Prefer IMU (accel/gyro) nanos so measureTimeMs advances at sensor rate. */
    fun measureNanos(accelNanos: Long?, gyroNanos: Long?, gpsNanos: Long?): Long? {
        val imu = listOfNotNull(accelNanos, gyroNanos).maxOrNull()
        return imu ?: gpsNanos
    }

    fun clampHz(hz: Int): Int = hz.coerceIn(1, ABS_MAX_HZ)

    /** Emit when interval allows and measurement time advanced (or first sample). */
    fun shouldEmit(
        nowElapsedNanos: Long,
        lastEmitElapsedNanos: Long,
        hz: Int,
        measureNanos: Long?,
        lastMeasureNanos: Long?,
    ): Boolean {
        val capped = clampHz(hz)
        val minIntervalNs = 1_000_000_000L / capped
        if (nowElapsedNanos - lastEmitElapsedNanos < minIntervalNs) return false
        if (measureNanos == null) return false
        if (lastMeasureNanos != null && measureNanos <= lastMeasureNanos) return false
        return true
    }
}
