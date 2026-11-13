package com.cmts.mobile

/**
 * Product recording state machine (protocol v0.1 / handoff 2026-11-07).
 * CaptureService / UI / WebSocket remote all project onto this.
 */
enum class RecordingState {
    IDLE,
    INITIALIZING,
    READY,
    RECORDING,
    STOPPING,
    SAVING,
    ERROR,
}

object RecordingTransitions {
    private val allowed: Map<RecordingState, Set<RecordingState>> = mapOf(
        RecordingState.IDLE to setOf(RecordingState.INITIALIZING, RecordingState.ERROR),
        RecordingState.INITIALIZING to setOf(RecordingState.READY, RecordingState.ERROR, RecordingState.IDLE),
        RecordingState.READY to setOf(
            RecordingState.RECORDING,
            RecordingState.INITIALIZING,
            RecordingState.IDLE,
            RecordingState.ERROR,
        ),
        RecordingState.RECORDING to setOf(RecordingState.STOPPING, RecordingState.ERROR),
        RecordingState.STOPPING to setOf(RecordingState.SAVING, RecordingState.ERROR, RecordingState.READY),
        RecordingState.SAVING to setOf(RecordingState.READY, RecordingState.IDLE, RecordingState.ERROR),
        RecordingState.ERROR to setOf(RecordingState.IDLE, RecordingState.INITIALIZING, RecordingState.READY),
    )

    fun canTransition(from: RecordingState, to: RecordingState): Boolean =
        from == to || allowed[from]?.contains(to) == true
}

/**
 * Thread-safe façade over [RecordingState]. CaptureService is the sole writer of
 * structural transitions; UI/WS call [RecordingCommands] which posts intents.
 */
object RecordingStateMachine {
    @Volatile
    private var state: RecordingState = RecordingState.IDLE

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var activeFileId: String? = null
        private set

    @Volatile
    var startedAtMs: Long? = null
        private set

    fun snapshot(): RecordingState = state

    @Synchronized
    fun tryTransition(to: RecordingState, reason: String? = null): Boolean {
        val from = state
        if (!RecordingTransitions.canTransition(from, to)) return false
        state = to
        when (to) {
            RecordingState.ERROR -> lastError = reason
            RecordingState.IDLE, RecordingState.READY -> {
                if (to == RecordingState.IDLE) {
                    activeFileId = null
                    startedAtMs = null
                }
                if (from == RecordingState.ERROR && to != RecordingState.ERROR) lastError = null
            }
            RecordingState.RECORDING -> {
                // fileId / startedAt set by CaptureService via markSession
            }
            else -> Unit
        }
        return true
    }

    @Synchronized
    fun force(to: RecordingState, reason: String? = null) {
        state = to
        if (to == RecordingState.ERROR) lastError = reason
        if (to == RecordingState.IDLE) {
            activeFileId = null
            startedAtMs = null
            lastError = null
        }
    }

    @Synchronized
    fun markSession(fileId: String?, startedAtMs: Long?) {
        activeFileId = fileId
        this.startedAtMs = startedAtMs
    }

    /** Called after the recording file opens successfully, following the permitted ready step. */
    @Synchronized
    fun recordingOpened(fileId: String, startedAtMs: Long): Boolean {
        if (state == RecordingState.RECORDING) return activeFileId == fileId
        if (state == RecordingState.INITIALIZING) tryTransition(RecordingState.READY)
        if (!RecordingTransitions.canTransition(state, RecordingState.RECORDING)) return false
        markSession(fileId, startedAtMs)
        return tryTransition(RecordingState.RECORDING)
    }

    fun statusPayload(): Map<String, Any?> = mapOf(
        "state" to state.name,
        "fileId" to activeFileId,
        "startedAtMs" to startedAtMs,
        "reason" to lastError,
        "ok" to (state != RecordingState.ERROR),
        "recordHold" to (state == RecordingState.RECORDING || state == RecordingState.STOPPING || state == RecordingState.SAVING),
        "running" to Live.running,
        "serviceAlive" to Live.serviceAlive,
    )
}

/** Shared start/stop used by MainActivity UI and remote WebSocket. Idempotent. */
object RecordingCommands {
    fun start(context: android.content.Context): RecordingState {
        val cur = RecordingStateMachine.snapshot()
        if (cur == RecordingState.RECORDING || cur == RecordingState.STOPPING || cur == RecordingState.SAVING) {
            return cur
        }
        RecordingStateMachine.tryTransition(RecordingState.INITIALIZING)
        context.startForegroundService(CaptureService.acquireIntent(context, CaptureService.CLIENT_RECORD))
        return RecordingStateMachine.snapshot()
    }

    fun stop(context: android.content.Context): RecordingState {
        val cur = RecordingStateMachine.snapshot()
        if (cur != RecordingState.RECORDING && cur != RecordingState.INITIALIZING) {
            // Idempotent: already stopped / idle / ready / error — just report.
            return cur
        }
        RecordingStateMachine.tryTransition(RecordingState.STOPPING)
        context.startService(CaptureService.releaseIntent(context, CaptureService.CLIENT_RECORD))
        return RecordingStateMachine.snapshot()
    }
}
