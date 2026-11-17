package com.cmts.mobile.performance

/**
 * Pure (Android-free) bookkeeping for the live 成绩 table and continuous / stay-armed runs.
 *
 * - The live table accumulates completed scores for the current capture/recording session.
 * - Stop-and-go (re-arm after a round) must not clear already posted scores.
 * - The table is cleared only when a new recording session starts ([clearResults]).
 * - When every armed segment is terminal, [onRoundFinished] says whether to re-arm (stay-armed on)
 *   or release capture (stay-armed off, e.g. after 取消).
 *
 * Not thread-safe; [PerformanceController] calls it under its lock.
 */
internal class RunCycle<T>(stayArmed: Boolean = true) {
    enum class RoundAction { REARM, RELEASE }

    var stayArmed: Boolean = stayArmed
        private set

    private val scores = mutableListOf<T>()

    /** Snapshot of the live table rows for the current recording session. */
    val rows: List<T> get() = scores.toList()

    /** An explicitly started capture session clears visible scores, not the armed test. */
    fun clearResults() { scores.clear() }

    /** A new set of segments was armed; scores of this recording stay visible. */
    fun onArmed() {}

    /**
     * Report a segment phase after an engine event. Always returns false: starting the next
     * stop-and-go run does not clear the session table.
     */
    fun observe(@Suppress("UNUSED_PARAMETER") phase: PerformancePhase): Boolean = false

    fun onCompleted(row: T) {
        scores += row
    }

    fun onRoundFinished(): RoundAction = if (stayArmed) RoundAction.REARM else RoundAction.RELEASE

    /** 取消: stop continuous mode until the user arms again. */
    fun onCancel() {
        stayArmed = false
    }

    /** 待命 tapped (or restored setting): continuous mode on. */
    fun setStayArmed(value: Boolean) {
        stayArmed = value
    }

    companion object {
        /** A segment in one of these phases means the driver actually began a run. */
        val RUN_STARTED_PHASES = setOf(
            PerformancePhase.Running,
            PerformancePhase.FinishCandidate,
            PerformancePhase.Completed,
        )
    }
}
