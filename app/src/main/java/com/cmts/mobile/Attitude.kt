package com.cmts.mobile

import kotlin.math.*

internal fun vehicleAxes(device: List<Double>): List<Double> = listOf(device[1], -device[0], device[2])

// Device top forward, left leftward, screen outward up. Right-hand Euler angles.
// Matrix maps natural device coordinates into East/North/Up world coordinates.
internal fun vehicleAttitude(matrix: FloatArray): List<Double> = listOf(
 Math.toDegrees(atan2(-matrix[6].toDouble(), matrix[8].toDouble())),
 Math.toDegrees(asin((-matrix[7].toDouble()).coerceIn(-1.0, 1.0))),
 Math.toDegrees(atan2(-matrix[1].toDouble(), matrix[4].toDouble()))
)

// Android getOrientation convention, fixed to natural device axes (not screen rotation).
// Output order: pitch about X, roll about Y, azimuth about -Z, in degrees.
internal fun attitudeDegrees(azimuth: Double, pitch: Double, roll: Double): List<Double> =
 listOf(Math.toDegrees(pitch), Math.toDegrees(roll), ((Math.toDegrees(azimuth) % 360) + 360) % 360)


/** Display-only mount zero: subtract recorded bias. CSV/phone.* stay raw. */
internal fun applyMountZeroLinear(values: List<Double?>, bias: List<Double>?): List<Double?> {
 if (bias == null) return values
 return values.mapIndexed { i, v ->
  val b = bias.getOrNull(i) ?: return@mapIndexed v
  v?.minus(b)
 }
}

/** Wrap degrees into (-180, 180]. */
internal fun wrapSignedDegrees(deg: Double): Double {
 var x = ((deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
 return if (x <= -180.0) 180.0 else x
}

/**
 * Attitude mount zero: subtract Euler bias.
 * Yaw (index 2) uses signed wrap so relative heading sits near 0 after zeroing.
 * Euler subtraction is an install convenience, not a fused orientation recompute.
 */
internal fun applyMountZeroAttitude(values: List<Double?>, bias: List<Double>?): List<Double?> {
 if (bias == null) return values
 return values.mapIndexed { i, v ->
  val b = bias.getOrNull(i) ?: return@mapIndexed v
  if (v == null) null
  else {
   val d = v - b
   if (i == 2) wrapSignedDegrees(d) else d
  }
 }
}

/** Snapshot gyro+attitude as install zero. Accel bias unused — Dashboard uses gravity removal. */
internal fun captureMountZero(
 accel: Sample?,
 gyro: Sample?,
 attitude: Sample?,
 atNanos: Long
): MountZero? {
 fun triple(s: Sample?): List<Double>? {
  val v = s?.values ?: return null
  if (v.size < 3) return null
  val out = List(3) { i -> v[i] ?: return null }
  return if (out.all { it.isFinite() }) out else null
 }
 val g = triple(gyro) ?: return null
 val t = triple(attitude) ?: return null
 // Keep accel snapshot only as diagnostics; display does not subtract it.
 val a = triple(accel) ?: emptyList()
 return MountZero(enabled = true, accel = a, gyro = g, attitude = t, zeroedAtElapsedNanos = atNanos)
}

internal const val STANDARD_GRAVITY = 9.80665

/** Match Android SensorManager.getRotationMatrixFromVector for length-9 R. */
internal fun rotationMatrixFromVector(rotationVector: List<Double>): FloatArray? {
 if (rotationVector.size < 3) return null
 val q1 = rotationVector[0]
 val q2 = rotationVector[1]
 val q3 = rotationVector[2]
 val q0 = if (rotationVector.size >= 4) rotationVector[3] else {
  val t = 1.0 - q1 * q1 - q2 * q2 - q3 * q3
  if (t > 0) sqrt(t) else 0.0
 }
 if (!listOf(q0, q1, q2, q3).all { it.isFinite() }) return null
 val sq_q1 = 2 * q1 * q1
 val sq_q2 = 2 * q2 * q2
 val sq_q3 = 2 * q3 * q3
 val q1_q2 = 2 * q1 * q2
 val q3_q0 = 2 * q3 * q0
 val q1_q3 = 2 * q1 * q3
 val q2_q0 = 2 * q2 * q0
 val q2_q3 = 2 * q2 * q3
 val q1_q0 = 2 * q1 * q0
 return floatArrayOf(
  (1 - sq_q2 - sq_q3).toFloat(), (q1_q2 - q3_q0).toFloat(), (q1_q3 + q2_q0).toFloat(),
  (q1_q2 + q3_q0).toFloat(), (1 - sq_q1 - sq_q3).toFloat(), (q2_q3 - q1_q0).toFloat(),
  (q1_q3 - q2_q0).toFloat(), (q2_q3 + q1_q0).toFloat(), (1 - sq_q1 - sq_q2).toFloat()
 )
}

/** Gravity as seen by accelerometer in device frame: R^T * [0,0,g] = g * R row2. */
internal fun gravityDeviceFromMatrix(matrix: FloatArray, g: Double = STANDARD_GRAVITY): List<Double>? {
 if (matrix.size < 9) return null
 return listOf(matrix[6] * g, matrix[7] * g, matrix[8] * g)
}

/**
 * Gravity in vehicle frame from roll/pitch (yaw irrelevant).
 * Matches vehicleAxes(gravityDeviceFromMatrix) for our vehicleAttitude Euler convention.
 */
internal fun gravityVehicleFromAttitude(rollDeg: Double, pitchDeg: Double, g: Double = STANDARD_GRAVITY): List<Double> {
 val roll = Math.toRadians(rollDeg)
 val pitch = Math.toRadians(pitchDeg)
 val m6 = -sin(roll) * cos(pitch)
 val m7 = -sin(pitch)
 val m8 = cos(roll) * cos(pitch)
 return vehicleAxes(listOf(m6 * g, m7 * g, m8 * g))
}

/** vehicle.accel minus estimated gravity → linear accel in vehicle axes. */
internal fun vehicleLinearAccel(
 vehicleAccel: List<Double?>?,
 rotationVector: List<Double?>?,
 vehicleAttitude: List<Double?>?,
 g: Double = STANDARD_GRAVITY
): List<Double?>? {
 if (vehicleAccel == null || vehicleAccel.size < 3) return null
 if (!vehicleAccel.take(3).all { it != null && it.isFinite() }) return null
 val gravityVehicle: List<Double> = run {
  val rv = rotationVector?.map { it ?: return@run null }
  if (rv != null && rv.size >= 3 && rv.take(3).all { it.isFinite() }) {
   val matrix = rotationMatrixFromVector(rv) ?: return@run null
   val gDev = gravityDeviceFromMatrix(matrix, g) ?: return@run null
   return@run vehicleAxes(gDev)
  }
  null
 } ?: run {
  val att = vehicleAttitude ?: return null
  if (att.size < 2) return null
  val roll = att[0] ?: return null
  val pitch = att[1] ?: return null
  if (!roll.isFinite() || !pitch.isFinite()) return null
  gravityVehicleFromAttitude(roll, pitch, g)
 }
 return listOf(
  vehicleAccel[0]!! - gravityVehicle[0],
  vehicleAccel[1]!! - gravityVehicle[1],
  vehicleAccel[2]!! - gravityVehicle[2]
 )
}

