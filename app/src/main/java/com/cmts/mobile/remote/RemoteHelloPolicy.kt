package com.cmts.mobile.remote

/** Open LAN mode still requires a compatible application handshake before subscription. */
object RemoteHelloPolicy {
    fun accepts(protoMin: Int, protoMax: Int): Boolean = protoMin <= 1 && protoMax >= 1
}
