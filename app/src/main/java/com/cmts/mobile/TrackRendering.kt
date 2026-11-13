package com.cmts.mobile

/** Immutable prefix view; replay advances an index without copying the recorded track. */
internal open class ListPrefix<T>(val source: List<T>, override val size: Int) : AbstractList<T>() {
    init { require(size in 0..source.size) }
    override fun get(index: Int): T {
        if (index !in 0 until size) throw IndexOutOfBoundsException(index.toString())
        return source[index]
    }
}

internal class TrackPrefix(source: List<TrackPoint>, size: Int) : ListPrefix<TrackPoint>(source, size)

internal fun trackUpperBound(track: List<TrackPoint>, nanos: Long): Int {
    var low = 0
    var high = track.size
    while (low < high) {
        val mid = low + (high - low) / 2
        if (track[mid].nanos <= nanos) low = mid + 1 else high = mid
    }
    return low
}

/** Reuses projections while seeking replay and while a bounded live track slides forward. */
internal class TrackCoordinateCache<T>(private val convert: (TrackPoint) -> T) {
    private var source: List<TrackPoint>? = null
    private var coordinates = ArrayList<T>()
    private val cached = HashMap<TrackPoint, T>()

    fun get(track: List<TrackPoint>): List<T> {
        val fullTrack = (track as? TrackPrefix)?.source ?: track
        if (fullTrack !== source) {
            if (fullTrack.isEmpty() || !cached.containsKey(fullTrack.first())) cached.clear()
            coordinates = ArrayList()
            source = fullTrack
        }
        for (i in coordinates.size until track.size) {
            val point = fullTrack[i]
            coordinates.add(cached.getOrPut(point) { convert(point) })
        }
        // Amortize pruning instead of allocating a large Set for every GNSS fix.
        if (cached.size > fullTrack.size + 1024) cached.keys.retainAll(fullTrack.toSet())
        // A size-fixed view remains valid when another replay tick appends coordinates.
        return ListPrefix(coordinates, track.size)
    }
}
