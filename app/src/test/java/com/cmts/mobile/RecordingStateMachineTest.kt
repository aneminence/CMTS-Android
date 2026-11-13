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
    fun successfullyOpenedFileLeavesInitializingAndCanStopNormally() {
        RecordingStateMachine.tryTransition(RecordingState.INITIALIZING)
        assertTrue(RecordingStateMachine.recordingOpened("session-test", 1234L))
        assertEquals(RecordingState.RECORDING, RecordingStateMachine.snapshot())
        assertEquals("session-test", RecordingStateMachine.activeFileId)
        assertEquals(1234L, RecordingStateMachine.startedAtMs)
        assertTrue(RecordingStateMachine.tryTransition(RecordingState.STOPPING))
        assertTrue(RecordingStateMachine.tryTransition(RecordingState.SAVING))
        assertTrue(RecordingStateMachine.tryTransition(RecordingState.READY))
    }

    @Test
    fun openCallbackCannotOverwriteAnErrorWithARecordingSession() {
        RecordingStateMachine.force(RecordingState.ERROR, "write failed")
        assertFalse(RecordingStateMachine.recordingOpened("session-test", 1234L))
        assertEquals(RecordingState.ERROR, RecordingStateMachine.snapshot())
        assertEquals(null, RecordingStateMachine.activeFileId)
        assertEquals("write failed", RecordingStateMachine.lastError)
    }

    @Test
    fun duplicateOpenKeepsTheOriginalSessionAndStartTime() {
        RecordingStateMachine.tryTransition(RecordingState.INITIALIZING)
        assertTrue(RecordingStateMachine.recordingOpened("session-test", 1234L))
        assertTrue(RecordingStateMachine.recordingOpened("session-test", 9999L))
        assertFalse(RecordingStateMachine.recordingOpened("unexpected-session", 9999L))
        assertEquals(RecordingState.RECORDING, RecordingStateMachine.snapshot())
        assertEquals("session-test", RecordingStateMachine.activeFileId)
        assertEquals(1234L, RecordingStateMachine.startedAtMs)
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
