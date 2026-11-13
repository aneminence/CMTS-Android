package com.cmts.mobile

import org.junit.Assert.*
import org.junit.Test

class ConnectionEpochTest {
    @Test fun reconnectCannotReactivateOldReaderOrBeCancelledByItsFinally() {
        val connection = ConnectionEpoch()
        val old = connection.tryStart()!!
        assertNull(connection.tryStart())
        connection.stop()
        val next = connection.tryStart()!!
        assertFalse(connection.isActive(old))
        connection.finish(old)
        assertTrue(connection.isActive(next))
        connection.finish(next)
        assertFalse(connection.isActive(next))
        assertNotNull(connection.tryStart())
    }
}
