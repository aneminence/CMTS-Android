package com.cmts.mobile

data class Sample(val source: String, val nanos: Long, val utcMillis: Long?, val values: List<Double?>, val quality: String = "reported") {
    init { require(nanos >= 0); require(values.all { it == null || it.isFinite() }) }
    fun csv(sequence: Long, callbackNanos: Long) = (listOf(sequence, source, nanos, callbackNanos, utcMillis ?: "", quality) + values.map { it ?: "" }).joinToString(",")
}
interface SensorDataSource { fun start(emit: (Sample) -> Unit); fun stop() }
// USB UM982, RS485 and remote adapters must preserve source timestamps and quality.
class Rate {
    private var previous: Long? = null
    fun update(nanos: Long): Double? { val old = previous; previous = nanos; return if (old != null && nanos > old) 1e9 / (nanos - old) else null }
}
