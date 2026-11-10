package com.cmts.mobile.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteHelloPolicyTest {
    @Test fun compatibleHandshakeNeedsNoCredential() { assertTrue(RemoteHelloPolicy.accepts(1, 1)) }
    @Test fun incompatibleOrInvertedRangeRejected() {
        assertFalse(RemoteHelloPolicy.accepts(2, 2))
        assertFalse(RemoteHelloPolicy.accepts(2, 1))
        assertFalse(RemoteHelloPolicy.accepts(0, 0))
    }
}
