package com.cmts.mobile

/**
 * VBOX-style trip bookkeeping: integrate GNSS speed over time, never polyline length.
 *
 * Distance and moving time only include intervals whose mean speed is at or above
 * [MOVING_SPEED_THRESHOLD_MPS] (0.5 km/h, same operational stop threshold as the
 * performance engine). Recording duration is wall-clock and includes stops.
 *
 * Intervals longer than [MAX_INTERVAL_NS] are skipped so a recording pause or
 * GNSS dropout cannot invent distance.
 */
object TripMetrics {
    const val MOVING_SPEED_THRESHOLD_KMH = 0.5
    const val MOVING_SPEED_THRESHOLD_MPS = MOVING_SPEED_THRESHOLD_KMH / 3.6
    const val MAX_INTERVAL_NS = 5_000_000_000L
    const val VERSION = 1

    data class Snapshot(
        val recordingDurationNs: Long,
        val movingDurationNs: Long,
        val distanceMeters: Double,
    )

    data class SpeedFix(val timeNs: Long, val speedMps: Double?)

    /** A stopped session with no moving distance is discarded, not saved as a record. */
    fun isEmptyTrip(distanceMeters: Double): Boolean = !distanceMeters.isFinite() || distanceMeters <= 0.0

    fun integrate(fixes: List<SpeedFix>, recordingDurationNs: Long): Snapshot {

        var moving = 0L
        var distance = 0.0
        var previous: SpeedFix? = null
        for (fix in fixes) {
            val last = previous
            previous = fix
            if (last == null) continue
            val dt = fix.timeNs - last.timeNs
            if (dt <= 0L || dt > MAX_INTERVAL_NS) continue
            val v0 = last.speedMps
            val v1 = fix.speedMps
            if (v0 == null || v1 == null || !v0.isFinite() || !v1.isFinite()) continue
            val mean = 0.5 * (v0 + v1)
            if (mean < MOVING_SPEED_THRESHOLD_MPS) continue
            moving += dt
            distance += mean * (dt / 1e9)
        }
        return Snapshot(recordingDurationNs.coerceAtLeast(0L), moving, distance.coerceAtLeast(0.0))
    }

    class Accumulator {
        private var previous: SpeedFix? = null
        private var movingDurationNs = 0L
        private var distanceMeters = 0.0

        fun reset() {
            previous = null
            movingDurationNs = 0L
            distanceMeters = 0.0
        }

        fun onFix(timeNs: Long, speedMps: Double?) {
            val added = integrate(listOfNotNull(previous, SpeedFix(timeNs, speedMps)), 0L)
            movingDurationNs += added.movingDurationNs
            distanceMeters += added.distanceMeters
            previous = SpeedFix(timeNs, speedMps)
        }

        fun snapshot(recordingDurationNs: Long) = Snapshot(recordingDurationNs.coerceAtLeast(0L), movingDurationNs, distanceMeters)
    }
}
