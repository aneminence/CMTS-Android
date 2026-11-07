package com.cmts.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingStateMachineTest {
    @Before
    fun reset() {
        RecordingStateMachine.force(RecordingState.IDLE)
    }

    @Test
    fun idleToInitializingToReadyToRecording() {
        assertTrue(RecordingStateMachine.tryTransition(RecordingState.INITIALIZING))
        assertTrue(RecordingStateMachine.tryTransition(RecordingState.READY))
        assertTrue(RecordingStateMachine.tryTransition(RecordingState.RECORDING))
        assertEquals(RecordingState.RECORDING, RecordingStateMachine.snapshot())
    }

    @Test
    fun recordingStopPath() {
        RecordingStateMachine.force(RecordingState.RECORDING)
        assertTrue(RecordingStateMachine.tryTransition(RecordingState.STOPPING))
        assertTrue(RecordingStateMachine.tryTransition(RecordingState.SAVING))
        assertTrue(RecordingStateMachine.tryTransition(RecordingState.READY))
        assertEquals(RecordingState.READY, RecordingStateMachine.snapshot())
    }

    @Test
    fun rejectsIllegalJump() {
        RecordingStateMachine.force(RecordingState.IDLE)
        assertFalse(RecordingStateMachine.tryTransition(RecordingState.RECORDING))
        assertEquals(RecordingState.IDLE, RecordingStateMachine.snapshot())
    }

    @Test
    fun errorThenRecover() {
        RecordingStateMachine.force(RecordingState.ERROR, "boom")
        assertEquals("boom", RecordingStateMachine.lastError)
        assertTrue(RecordingStateMachine.tryTransition(RecordingState.IDLE))
        assertEquals(null, RecordingStateMachine.lastError)
    }

    @Test
    fun sameStateIsAllowed() {
        RecordingStateMachine.force(RecordingState.READY)
        assertTrue(RecordingStateMachine.tryTransition(RecordingState.READY))
    }
}
