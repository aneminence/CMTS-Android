package com.cmts.mobile

import kotlin.math.*

data class TrackPoint(val latitude: Double, val longitude: Double, val nanos: Long)
internal data class WorldPoint(val x: Double, val y: Double)
internal fun project(latitude: Double, longitude: Double): WorldPoint {
 val lat = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
 return WorldPoint((longitude + 180) / 360, (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2)
}
internal fun wrapX(x: Double): Double = ((x % 1) + 1) % 1
internal fun wrappedDelta(x: Double): Double = x - floor(x + 0.5)
internal fun elapsedLabel(start: Long, now: Long): String {
 val seconds = ((now - start).coerceAtLeast(0) / 1_000_000_000)
 return "%02d:%02d:%02d".format(java.util.Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
}
