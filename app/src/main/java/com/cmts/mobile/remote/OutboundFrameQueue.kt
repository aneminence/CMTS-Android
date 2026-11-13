package com.cmts.mobile.remote

/** Bounded queues with control priority; sleeps until a producer enqueues or the socket closes. */
internal class OutboundFrameQueue(private val controlLimit: Int = 64, private val sampleLimit: Int = 8) {
    private val monitor = Object()
    private val control = ArrayDeque<ByteArray>()
    private val samples = ArrayDeque<ByteArray>()
    private var closed = false

    fun control(bytes: ByteArray, replaceOldest: Boolean = true) = synchronized(monitor) {
        if (closed) return@synchronized
        if (control.size == controlLimit) {
            if (!replaceOldest) return@synchronized
            control.removeFirst()
        }
        control.addLast(bytes)
        monitor.notifyAll()
    }

    fun sample(bytes: ByteArray) = synchronized(monitor) {
        if (closed) return@synchronized
        if (samples.size == sampleLimit) samples.removeFirst()
        samples.addLast(bytes)
        monitor.notifyAll()
    }

    fun take(): ByteArray? = synchronized(monitor) {
        while (!closed && control.isEmpty() && samples.isEmpty()) monitor.wait()
        if (closed) null else if (control.isNotEmpty()) control.removeFirst() else samples.removeFirst()
    }

    fun close() = synchronized(monitor) {
        closed = true
        control.clear()
        samples.clear()
        monitor.notifyAll()
    }
}
