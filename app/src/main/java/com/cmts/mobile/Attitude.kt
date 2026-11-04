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
