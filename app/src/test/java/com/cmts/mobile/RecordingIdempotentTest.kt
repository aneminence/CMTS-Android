package com.cmts.mobile

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Documents idempotent record.start/stop at the state-machine layer:
 * already RECORDING → stay; already non-RECORDING stop → stay.
 */
class RecordingIdempotentTest {
    @Before
    fun reset() {
        RecordingStateMachine.force(RecordingState.IDLE)
    }

    @Test
    fun repeatedRecordingTransitionIsNoOpAtMachine() {
        RecordingStateMachine.force(RecordingState.RECORDING)
        assertEquals(true, RecordingTransitions.canTransition(RecordingState.RECORDING, RecordingState.RECORDING))
        assertEquals(RecordingState.RECORDING, RecordingStateMachine.snapshot())
        RecordingStateMachine.tryTransition(RecordingState.RECORDING)
        assertEquals(RecordingState.RECORDING, RecordingStateMachine.snapshot())
    }

    @Test
    fun stopFromReadyIsRejectedLeavingReady() {
        RecordingStateMachine.force(RecordingState.READY)
        // STOPPING only from RECORDING
        assertEquals(false, RecordingStateMachine.tryTransition(RecordingState.STOPPING))
        assertEquals(RecordingState.READY, RecordingStateMachine.snapshot())
    }
}
