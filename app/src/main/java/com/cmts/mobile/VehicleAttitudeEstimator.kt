package com.cmts.mobile

import kotlin.math.*

/** Experimental pure core. SI, vehicle FLU; quaternion wxyz maps vehicle to local level.
 * Navigation yaw is arbitrary. No magnetometer or absolute heading claim.
 * Initialization must be supplied from a verified parked observation, never display zero.
 */
internal class VehicleAttitudeEstimator(initialWxyz: List<Double>) {
    companion object { const val VERSION = "experimental-mahony-0.1" }
    private var q = initialWxyz.toDoubleArray().also {
        require(it.size == 4 && it.all(Double::isFinite))
        val n = sqrt(it.sumOf { v -> v*v }); require(n > 0)
        for (i in it.indices) it[i] /= n
    }
    private var accel: DoubleArray? = null
    private var accelTime = Long.MIN_VALUE
    private var gyroTime: Long? = null
    private var trustedTime: Long? = null
    private var rejectUntil = 0L
    private var previousAccel: DoubleArray? = null
    private var jerk = 0.0
    var weight = 0.0; private set
    var reason = "initializing"; private set
    var motionRisk = false
    private var invalidContinuity = false
    data class Output(val time: Long, val wxyz: List<Double>, val weight: Double,
                      val reason: String, val accelAgeSeconds: Double, val trustedAgeSeconds: Double?)

    fun accelerometer(time: Long, values: List<Double>) {
        if (time <= accelTime || values.size != 3 || values.any { !it.isFinite() }) return
        val a = values.toDoubleArray()
        if (accelTime != Long.MIN_VALUE) {
            val dt = (time - accelTime) * 1e-9
            jerk = sqrt(a.indices.sumOf { (a[it] - previousAccel!![it]).pow(2) }) / dt
        }
        previousAccel = a.copyOf(); accel = a; accelTime = time
    }

    /** Caller supplies calibrated residual bias; motion never learns bias here. */
    fun gyroscope(time: Long, values: List<Double>, bias: List<Double> = listOf(0.0,0.0,0.0)): Output? {
        if (values.size != 3 || bias.size != 3 || (values + bias).any { !it.isFinite() }) return null
        val last = gyroTime
        if (last != null && time <= last) return null
        gyroTime = time
        val dt = if (last == null) 0.0 else (time-last)*1e-9
        val age = if (accelTime == Long.MIN_VALUE) Double.POSITIVE_INFINITY else (time-accelTime)*1e-9
        if (dt > 0.2 || values.any { abs(it) > 30 } || invalidContinuity) {
            invalidContinuity = true
            weight = 0.0; reason = "gap_or_saturation_reinitialize"
            return output(time, age)
        }
        val a = accel
        val vertical = doubleArrayOf(2*(q[1]*q[3]-q[0]*q[2]), 2*(q[2]*q[3]+q[0]*q[1]), 1-2*(q[1]*q[1]+q[2]*q[2]))
        val error = DoubleArray(3)
        var target = 0.0
        reason = "stale_accel"
        if (a != null && age in 0.0..0.1) {
            val norm = sqrt(a.sumOf { it*it })
            if (norm > 1e-6) {
                val unit = a.map { it/norm }
                val angle = acos(unit.indices.sumOf { unit[it]*vertical[it] }.coerceIn(-1.0,1.0))
                val risk = maxOf(abs(norm-9.80665)/0.3, angle/Math.toRadians(3.0), jerk/5.0, if (motionRisk) 1.0 else 0.0)
                if (risk >= 1) rejectUntil = time + 1_000_000_000L
                target = if (time < rejectUntil) 0.0 else (1-risk).coerceIn(0.0,1.0)
                reason = if (target == 0.0) "dynamic_gyro" else "fusion"
                for (i in 0..2) error[i] = unit[(i+1)%3]*vertical[(i+2)%3]-unit[(i+2)%3]*vertical[(i+1)%3]
            }
        }
        weight = if (target < weight) target else weight + (target-weight)*(1-exp(-dt/1.0))
        if (weight > 0.5) trustedTime = time
        val omega = DoubleArray(3) { values[it]-bias[it]+weight*error[it] }
        val speed = sqrt(omega.sumOf { it*it })
        val half = speed*dt/2
        val scale = if (speed < 1e-12) dt/2 else sin(half)/speed
        val r = doubleArrayOf(cos(half), omega[0]*scale, omega[1]*scale, omega[2]*scale)
        q = doubleArrayOf(q[0]*r[0]-q[1]*r[1]-q[2]*r[2]-q[3]*r[3],
            q[0]*r[1]+q[1]*r[0]+q[2]*r[3]-q[3]*r[2],
            q[0]*r[2]-q[1]*r[3]+q[2]*r[0]+q[3]*r[1],
            q[0]*r[3]+q[1]*r[2]-q[2]*r[1]+q[3]*r[0])
        val n = sqrt(q.sumOf { it*it }); for (i in q.indices) q[i] /= n
        return output(time, age)
    }
    private fun output(time: Long, age: Double) = Output(time,q.toList(),weight,reason,age,trustedTime?.let { (time-it)*1e-9 })
}
