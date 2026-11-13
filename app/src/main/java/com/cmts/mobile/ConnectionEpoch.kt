package com.cmts.mobile

import java.util.concurrent.atomic.AtomicReference

/** Cancellation belongs to one connection; restarting cannot reactivate an obsolete reader. */
internal class ConnectionEpoch {
    private val current = AtomicReference<Any?>(null)
    fun tryStart(): Any? = Any().let { if (current.compareAndSet(null, it)) it else null }
    fun isActive(token: Any): Boolean = current.get() === token
    fun stop() { current.set(null) }
    fun finish(token: Any) { current.compareAndSet(token, null) }
}
