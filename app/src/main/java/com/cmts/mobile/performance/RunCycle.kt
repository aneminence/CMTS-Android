package com.cmts.mobile.performance

/**
 * Pure (Android-free) bookkeeping for the live 成绩 table and continuous / stay-armed runs.
 *
 * - The live table holds only the current (or just-finished) run's completed scores.
 * - Arming (manual or automatic re-arm) does NOT clear the table; it only marks that the next
 *   run should start a fresh table. The table is cleared when a segment of the new run actually
 *   starts ([RUN_STARTED_PHASES]), so the previous run stays readable while waiting.
 * - When every armed segment is terminal, [onRoundFinished] says whether to re-arm (stay-armed on)
 *   or release capture (stay-armed off, e.g. after 取消).
 *
 * Not thread-safe; [PerformanceController] calls it under its lock.
 */
internal class RunCycle<T>(stayArmed: Boolean = true) {
    enum class RoundAction { REARM, RELEASE }

    var stayArmed: Boolean = stayArmed
        private set

    private var pendingClear = false
    private val scores = mutableListOf<T>()

    /** Snapshot of the live table rows (current or just-finished run only). */
    val rows: List<T> get() = scores.toList()

    val clearPending: Boolean get() = pendingClear

    /** A new set of segments was armed; the table is cleared once that run starts. */
    fun onArmed() {
        pendingClear = true
    }

    /**
     * Report a segment phase after an engine event. Returns true if this observation started the
     * new run and the table was just cleared.
     */
    fun observe(phase: PerformancePhase): Boolean {
        if (!pendingClear || phase !in RUN_STARTED_PHASES) return false
        pendingClear = false
        scores.clear()
        return true
    }

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
