package com.cmts.mobile.performance

/** Algorithm version stamped into every result. Bump when formulas or zero-speed rules change. */
const val PERFORMANCE_ENGINE_VERSION = "perf-engine-1.0.0"

enum class PerformanceMode {
    /** 0 → X km/h acceleration time (launch hysteresis uses stop/launch thresholds). */
    ACCEL_0_TO_X,
    /** X → Y km/h interval acceleration, Y > X. */
    ACCEL_X_TO_Y,
    /** X → 0 braking time and distance (finish confirmed by stationary hold). */
    BRAKE_X_TO_0,
    /** X → Y km/h interval braking, X > Y. */
    BRAKE_X_TO_Y,
}

enum class PerformancePhase {
    Idle,
    Armed,
    Ready,
    StartCandidate,
    Running,
    FinishCandidate,
    Completed,
    Cancelled,
    Invalid,
}

enum class QualityTier { PASS, WARN, FAIL }

enum class QualityReason {
    OK,
    LOW_SAMPLE_RATE,
    SPEED_ACCURACY_UNKNOWN,
    SPEED_ACCURACY_POOR,
    SAMPLE_GAP,
    SAMPLE_STALE,
    MISSING_SPEED,
    INVALID_SPEED,
    NEGATIVE_SPEED,
    MOCK_LOCATION,
    DUPLICATE_OR_REORDERED_TIME,
    SOURCE_CHANGED,
    CLOCK_DOMAIN_CHANGED,
    TIMEOUT,
    ARMED_ALREADY_PAST_START,
    CANCELLED,
    INSUFFICIENT_SAMPLES,
    CROSSING_GAP,
    WATCHDOG_STALE,
}

data class ZeroSpeedConfig(
    /** Speeds at or below this (km/h) count toward stationary. Tuning suggestion, not a validated spec. */
    val stopSpeedKmh: Double = 0.5,
    /** Rising through this (km/h) starts 0→X timing. Must be > stopSpeedKmh. */
    val launchSpeedKmh: Double = 1.0,
    /** Continuous hold at/below stop before Ready / finish confirm. */
    val stationaryHoldMs: Long = 1_000L,
    val minStationarySamples: Int = 3,
) {
    init {
        require(stopSpeedKmh >= 0.0 && stopSpeedKmh.isFinite())
        require(launchSpeedKmh > stopSpeedKmh && launchSpeedKmh.isFinite())
        require(stationaryHoldMs >= 0L)
        require(minStationarySamples >= 1)
    }
    fun stopMps(): Double = stopSpeedKmh / 3.6
    fun launchMps(): Double = launchSpeedKmh / 3.6
}

data class QualityConfig(
    /**
     * Max allowed gap between consecutive *accepted* sample times while Running.
     * Phone GNSS is typically ~1 Hz; 500ms was failing every run before any threshold score.
     */
    val maxSampleGapNs: Long = 2_500_000_000L,
    /**
     * Max age of last sample vs watchdog now while Armed/Running.
     * Must exceed typical phone GNSS interval with headroom (was 1s → WATCHDOG_STALE self-stop).
     */
    val maxSampleAgeNs: Long = 3_500_000_000L,
    /** Soft warn threshold; null skips accuracy checks. */
    val warnSpeedAccuracyMps: Double? = 1.5,
    val failSpeedAccuracyMps: Double? = null,
    val rejectMock: Boolean = true,
    val rejectNegativeSpeed: Boolean = true,
) {
    init {
        require(maxSampleGapNs > 0L)
        require(maxSampleAgeNs > 0L)
    }
}

data class PerformanceConfig(
    val mode: PerformanceMode,
    /** User start threshold in km/h (0 for ACCEL_0_TO_X / end for BRAKE_*_TO_0 conceptual zero). */
    val startSpeedKmh: Double,
    /** User end threshold in km/h. */
    val endSpeedKmh: Double,
    val zero: ZeroSpeedConfig = ZeroSpeedConfig(),
    val quality: QualityConfig = QualityConfig(),
    /** Wall timeout from arm or from start of Running, whichever applies in engine. */
    val timeoutNs: Long = 120_000_000_000L,
    val expectedSourceId: String = "phone.gps",
    val algorithmVersion: String = PERFORMANCE_ENGINE_VERSION,
) {
    init {
        require(startSpeedKmh.isFinite() && endSpeedKmh.isFinite())
        require(startSpeedKmh >= 0.0 && endSpeedKmh >= 0.0)
        require(timeoutNs > 0L)
        when (mode) {
            PerformanceMode.ACCEL_0_TO_X -> {
                require(startSpeedKmh == 0.0) { "ACCEL_0_TO_X start must be 0 km/h" }
                require(endSpeedKmh > zero.launchSpeedKmh)
            }
            PerformanceMode.ACCEL_X_TO_Y -> require(endSpeedKmh > startSpeedKmh)
            PerformanceMode.BRAKE_X_TO_0 -> {
                require(endSpeedKmh == 0.0) { "BRAKE_X_TO_0 end must be 0 km/h" }
                require(startSpeedKmh > zero.stopSpeedKmh)
            }
            PerformanceMode.BRAKE_X_TO_Y -> require(startSpeedKmh > endSpeedKmh)
        }
    }

    fun startMps(): Double = startSpeedKmh / 3.6
    fun endMps(): Double = endSpeedKmh / 3.6
    fun isAcceleration(): Boolean =
        mode == PerformanceMode.ACCEL_0_TO_X || mode == PerformanceMode.ACCEL_X_TO_Y
    fun isBraking(): Boolean =
        mode == PerformanceMode.BRAKE_X_TO_0 || mode == PerformanceMode.BRAKE_X_TO_Y
}

/**
 * One raw speed observation. Realtime capture and offline recompute must feed the same shape.
 * Do not populate from UI refresh rates or Replay snapshots.
 */
data class SpeedSample(
    val sourceId: String,
    val channelKind: String,
    val sequence: Long,
    val clockDomain: String,
    val sampleTimeNanos: Long,
    val receivedTimeNanos: Long,
    val utcMillis: Long? = null,
    val speedMps: Double? = null,
    val speedAccuracyMps: Double? = null,
    val valid: Boolean = true,
    val mock: Boolean = false,
    val rawReference: String? = null,
) {
    init {
        require(sequence >= 0L)
        require(sampleTimeNanos >= 0L)
        require(receivedTimeNanos >= 0L)
        require(speedMps == null || speedMps.isFinite() || speedMps!!.isNaN())
        require(speedAccuracyMps == null || speedAccuracyMps.isFinite())
    }
}

data class CrossingPoint(
    val timeNanos: Long,
    val speedMps: Double,
    val beforeSequence: Long,
    val afterSequence: Long,
    val alpha: Double,
)

data class AttemptStats(
    val acceptedSamples: Int = 0,
    val rejectedSamples: Int = 0,
    val maxGapNs: Long = 0L,
    val measuredMeanHz: Double? = null,
    val speedAccuracyUnknown: Boolean = false,
    val reasons: List<QualityReason> = emptyList(),
)

data class PerformanceResult(
    val config: PerformanceConfig,
    val phase: PerformancePhase,
    val quality: QualityTier,
    val reasons: List<QualityReason>,
    val start: CrossingPoint?,
    val end: CrossingPoint?,
    val durationNs: Long?,
    val distanceMeters: Double?,
    val stats: AttemptStats,
    val algorithmVersion: String = PERFORMANCE_ENGINE_VERSION,
    /** Operational zero-speed definition echoed for UI / persistence. */
    val zeroDefinition: String,
) {
    fun durationSeconds(): Double? = durationNs?.let { it / 1e9 }
}

data class EngineSnapshot(
    val phase: PerformancePhase,
    val config: PerformanceConfig?,
    val lastSpeedMps: Double?,
    val measuredHz: Double?,
    val quality: QualityTier,
    val reasons: List<QualityReason>,
    val result: PerformanceResult?,
    val runningDurationNs: Long?,
    val runningDistanceMeters: Double?,
)

/** Control events are explicit; samples never imply arm/cancel. */
sealed class EngineEvent {
    data class Arm(val config: PerformanceConfig) : EngineEvent()
    data object Cancel : EngineEvent()
    data class Watchdog(val nowNanos: Long) : EngineEvent()
    data class Sample(val sample: SpeedSample) : EngineEvent()
}
