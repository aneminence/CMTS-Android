package com.cmts.mobile

/** Primitive values and exact block extrema: no full-window temporary lists while drawing. */
internal class ReplayCurveSeries(private val values: DoubleArray) {
    private val blockSize = 256
    private val minima = DoubleArray((values.size + blockSize - 1) / blockSize) { Double.POSITIVE_INFINITY }
    private val maxima = DoubleArray(minima.size) { Double.NEGATIVE_INFINITY }
    init {
        values.forEachIndexed { index, value ->
            if (value.isFinite()) {
                val block = index / blockSize
                minima[block] = minOf(minima[block], value)
                maxima[block] = maxOf(maxima[block], value)
            }
        }
    }
    operator fun get(index: Int): Double? = values[index].takeIf { it.isFinite() }

    fun bounds(first: Int, last: Int): Pair<Double, Double> {
        require(first >= 0 && last < values.size && first <= last)
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        var i = first
        while (i <= last) {
            if (i % blockSize == 0 && i + blockSize - 1 <= last) {
                low = minOf(low, minima[i / blockSize])
                high = maxOf(high, maxima[i / blockSize])
                i += blockSize
            } else {
                val value = values[i++]
                if (value.isFinite()) { low = minOf(low, value); high = maxOf(high, value) }
            }
        }
        return if (low.isFinite()) low to high else 0.0 to 0.0
    }
}
