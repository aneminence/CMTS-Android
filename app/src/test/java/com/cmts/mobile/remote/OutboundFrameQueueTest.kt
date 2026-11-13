package com.cmts.mobile.remote

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class OutboundFrameQueueTest {
    @Test fun blocksUntilMessageOrCloseWithoutPolling() {
        val worker = Executors.newSingleThreadExecutor()
        val queue = OutboundFrameQueue()
        try {
            val waiting = worker.submit<ByteArray?> { queue.take() }
            assertThrows(TimeoutException::class.java) { waiting.get(50, TimeUnit.MILLISECONDS) }
            queue.sample(byteArrayOf(7))
            assertArrayEquals(byteArrayOf(7), waiting.get(2, TimeUnit.SECONDS))
            val closing = worker.submit<ByteArray?> { queue.take() }
            queue.close()
            assertNull(closing.get(2, TimeUnit.SECONDS))
        } finally { queue.close(); worker.shutdownNow() }
    }

    @Test fun keepsControlPriorityAndDropsOldestWithinEachBoundedQueue() {
        val queue = OutboundFrameQueue(controlLimit = 2, sampleLimit = 2)
        (1..3).forEach { queue.sample(byteArrayOf(it.toByte())) }
        (4..6).forEach { queue.control(byteArrayOf(it.toByte())) }
        queue.control(byteArrayOf(99), replaceOldest = false)
        assertEquals(listOf(5, 6, 2, 3), List(4) { queue.take()!!.single().toInt() })
        queue.close()
    }
}
