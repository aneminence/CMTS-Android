package com.cmts.mobile.performance

/**
 * Pure Kotlin performance timing engine shared by realtime capture and offline recompute.
 * No Android / Compose / Location dependencies.
 *
 * Threshold crossings: linear interpolation between adjacent accepted samples.
 * Braking distance: trapezoidal integration of speed on the timed interval.
 * Interpolation only refines crossing estimates — it does not create GNSS precision.
 */
class PerformanceEngine {
    private var config: PerformanceConfig? = null
    private var phase: PerformancePhase = PerformancePhase.Idle
    private var quality: QualityTier = QualityTier.PASS
    private val reasons: MutableList<QualityReason> = mutableListOf()

    private var lastAccepted: SpeedSample? = null
    private var lastAcceptedSpeed: Double? = null
    private var armedAtNanos: Long? = null
    private var runStartedAtNanos: Long? = null

    private var bandStartNanos: Long? = null
    private var bandCount: Int = 0
    private var bandLastNanos: Long? = null

    private var startCrossing: CrossingPoint? = null
    private var endCrossing: CrossingPoint? = null
    private var finishCandidate: CrossingPoint? = null

    private var distanceMeters: Double = 0.0
    private var acceptedInAttempt: Int = 0
    private var rejectedInAttempt: Int = 0
    private var maxGapNs: Long = 0L
    private var firstAcceptedNanos: Long? = null
    private var speedAccuracyUnknown: Boolean = false
    private var lockedSourceId: String? = null
    private var lockedClockDomain: String? = null

    private val segment = ArrayList<Pair<Long, Double>>(64)

    fun snapshot(): EngineSnapshot {
        val runningDur = when {
            phase == PerformancePhase.Running && startCrossing != null && lastAccepted != null ->
                (lastAccepted!!.sampleTimeNanos - startCrossing!!.timeNanos).coerceAtLeast(0L)
            phase == PerformancePhase.FinishCandidate && startCrossing != null && finishCandidate != null ->
                (finishCandidate!!.timeNanos - startCrossing!!.timeNanos).coerceAtLeast(0L)
            else -> null
        }
        val terminal = phase == PerformancePhase.Completed ||
            phase == PerformancePhase.Invalid ||
            phase == PerformancePhase.Cancelled
        return EngineSnapshot(
            phase = phase,
            config = config,
            lastSpeedMps = lastAcceptedSpeed,
            measuredHz = measuredHz(),
            quality = quality,
            reasons = reasons.toList(),
            result = if (terminal) buildResult(phase) else null,
            runningDurationNs = runningDur,
            runningDistanceMeters = if (phase == PerformancePhase.Running || phase == PerformancePhase.FinishCandidate) distanceMeters else null,
        )
    }

    fun onEvent(event: EngineEvent): EngineSnapshot {
        when (event) {
            is EngineEvent.Arm -> arm(event.config)
            EngineEvent.Cancel -> cancel()
            is EngineEvent.Watchdog -> watchdog(event.nowNanos)
            is EngineEvent.Sample -> onSample(event.sample)
        }
        return snapshot()
    }

    /** Offline path: same engine as realtime. */
    fun recompute(config: PerformanceConfig, samples: List<SpeedSample>, finalNowNanos: Long? = null): PerformanceResult {
        arm(config)
        for (s in samples) onSample(s)
        if (finalNowNanos != null) watchdog(finalNowNanos)
        return buildResult(phase)
    }

    private fun arm(cfg: PerformanceConfig) {
        clearAttempt()
        config = cfg
        phase = PerformancePhase.Armed
        quality = QualityTier.PASS
        reasons.clear()
        reasons.add(QualityReason.OK)
    }

    private fun cancel() {
        if (phase == PerformancePhase.Idle || phase == PerformancePhase.Completed) return
        note(QualityReason.CANCELLED)
        quality = QualityTier.FAIL
        phase = PerformancePhase.Cancelled
    }

    private fun clearAttempt() {
        config = null
        phase = PerformancePhase.Idle
        quality = QualityTier.PASS
        reasons.clear()
        lastAccepted = null
        lastAcceptedSpeed = null
        armedAtNanos = null
        runStartedAtNanos = null
        resetBand()
        startCrossing = null
        endCrossing = null
        finishCandidate = null
        distanceMeters = 0.0
        acceptedInAttempt = 0
        rejectedInAttempt = 0
        maxGapNs = 0L
        firstAcceptedNanos = null
        speedAccuracyUnknown = false
        lockedSourceId = null
        lockedClockDomain = null
        segment.clear()
    }

    private fun watchdog(nowNanos: Long) {
        val cfg = config ?: return
        if (phase !in ACTIVE) return
        val last = lastAccepted
        if (last != null && nowNanos - last.receivedTimeNanos > cfg.quality.maxSampleAgeNs) {
            fail(QualityReason.WATCHDOG_STALE)
            return
        }
        val anchor = runStartedAtNanos ?: armedAtNanos
        if (anchor != null && nowNanos - anchor > cfg.timeoutNs) fail(QualityReason.TIMEOUT)
    }

    private fun onSample(sample: SpeedSample) {
        val cfg = config ?: return
        if (phase !in ACTIVE) return

        if (armedAtNanos == null) armedAtNanos = sample.sampleTimeNanos

        val reject = classifyReject(cfg, sample)
        if (reject != null) {
            rejectedInAttempt++
            if (reject == QualityReason.SOURCE_CHANGED || reject == QualityReason.CLOCK_DOMAIN_CHANGED) {
                fail(reject)
                return
            }
            note(reject)
            if (phase == PerformancePhase.Running || phase == PerformancePhase.FinishCandidate) {
                if (reject == QualityReason.DUPLICATE_OR_REORDERED_TIME) softWarn(reject)
                else softWarn(reject)
            } else {
                softWarn(reject)
                resetBand()
            }
            return
        }

        val speed = sample.speedMps!!
        if (sample.speedAccuracyMps == null) {
            speedAccuracyUnknown = true
            softWarn(QualityReason.SPEED_ACCURACY_UNKNOWN)
        } else {
            cfg.quality.failSpeedAccuracyMps?.let { limit ->
                if (sample.speedAccuracyMps > limit) {
                    fail(QualityReason.SPEED_ACCURACY_POOR)
                    return
                }
            }
            cfg.quality.warnSpeedAccuracyMps?.let { limit ->
                if (sample.speedAccuracyMps > limit) softWarn(QualityReason.SPEED_ACCURACY_POOR)
            }
        }

        if (lockedSourceId == null) {
            lockedSourceId = sample.sourceId
            lockedClockDomain = sample.clockDomain
        }

        val prev = lastAccepted
        val prevSpeed = lastAcceptedSpeed
        if (prev != null) {
            val gap = sample.sampleTimeNanos - prev.sampleTimeNanos
            if (gap > maxGapNs) maxGapNs = gap
            if (gap > cfg.quality.maxSampleGapNs) {
                if (phase == PerformancePhase.Running || phase == PerformancePhase.FinishCandidate) {
                    fail(QualityReason.SAMPLE_GAP)
                    return
                }
                softWarn(QualityReason.SAMPLE_GAP)
                resetBand()
                acceptAnchor(sample, speed)
                return
            }
        }

        when (phase) {
            PerformancePhase.Armed, PerformancePhase.Ready, PerformancePhase.StartCandidate ->
                handleWaiting(cfg, sample, speed, prev, prevSpeed)
            PerformancePhase.Running ->
                handleRunning(cfg, sample, speed, prev, prevSpeed)
            PerformancePhase.FinishCandidate ->
                handleFinishCandidate(cfg, sample, speed)
            else -> Unit
        }

        acceptAnchor(sample, speed)
        maybeTimeout(cfg, sample.sampleTimeNanos)
    }

    private fun acceptAnchor(sample: SpeedSample, speed: Double) {
        acceptedInAttempt++
        if (firstAcceptedNanos == null) firstAcceptedNanos = sample.sampleTimeNanos
        lastAccepted = sample
        lastAcceptedSpeed = speed
        val hz = measuredHz()
        if (hz != null && hz < 1.0) softWarn(QualityReason.LOW_SAMPLE_RATE)
    }

    private fun handleWaiting(
        cfg: PerformanceConfig,
        sample: SpeedSample,
        speed: Double,
        prev: SpeedSample?,
        prevSpeed: Double?,
    ) {
        when (cfg.mode) {
            PerformanceMode.ACCEL_0_TO_X -> {
                trackStationary(cfg, sample.sampleTimeNanos, speed <= cfg.zero.stopMps())
                if (bandSatisfied(cfg)) phase = PerformancePhase.Ready
                if (phase == PerformancePhase.Ready && prev != null && prevSpeed != null) {
                    risingCross(prev, prevSpeed, sample, speed, cfg.zero.launchMps())?.let { start ->
                        beginRun(start, sample, speed)
                        risingCross(prev, prevSpeed, sample, speed, cfg.endMps())?.let { end ->
                            if (end.timeNanos >= start.timeNanos) completeAccel(cfg, end)
                        }
                    }
                }
            }
            PerformanceMode.ACCEL_X_TO_Y -> {
                val startV = cfg.startMps()
                if (speed < startV) {
                    trackStationary(cfg, sample.sampleTimeNanos, true)
                    if (bandSatisfied(cfg)) phase = PerformancePhase.Ready
                } else {
                    if (phase == PerformancePhase.Armed) note(QualityReason.ARMED_ALREADY_PAST_START)
                    resetBand()
                }
                if (phase == PerformancePhase.Ready && prev != null && prevSpeed != null) {
                    risingCross(prev, prevSpeed, sample, speed, startV)?.let { start ->
                        beginRun(start, sample, speed)
                        risingCross(prev, prevSpeed, sample, speed, cfg.endMps())?.let { end ->
                            if (end.timeNanos >= start.timeNanos) completeAccel(cfg, end)
                        }
                    }
                }
            }
            PerformanceMode.BRAKE_X_TO_0, PerformanceMode.BRAKE_X_TO_Y -> {
                val startV = cfg.startMps()
                if (speed > startV) {
                    trackStationary(cfg, sample.sampleTimeNanos, true)
                    if (bandSatisfied(cfg)) phase = PerformancePhase.Ready
                } else {
                    if (phase == PerformancePhase.Armed) note(QualityReason.ARMED_ALREADY_PAST_START)
                    resetBand()
                }
                if (phase == PerformancePhase.Ready && prev != null && prevSpeed != null) {
                    fallingCross(prev, prevSpeed, sample, speed, startV)?.let { start ->
                        beginRun(start, sample, speed)
                        if (cfg.mode == PerformanceMode.BRAKE_X_TO_Y) {
                            fallingCross(prev, prevSpeed, sample, speed, cfg.endMps())?.let { end ->
                                if (end.timeNanos >= start.timeNanos) completeBrake(cfg, end)
                            }
                        } else {
                            fallingCross(prev, prevSpeed, sample, speed, cfg.zero.stopMps())?.let { end ->
                                if (end.timeNanos >= start.timeNanos) enterFinishCandidate(cfg, end, sample, speed)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleRunning(
        cfg: PerformanceConfig,
        sample: SpeedSample,
        speed: Double,
        prev: SpeedSample?,
        prevSpeed: Double?,
    ) {
        if (prev == null || prevSpeed == null) return
        integrate(prev.sampleTimeNanos, prevSpeed, sample.sampleTimeNanos, speed)
        when (cfg.mode) {
            PerformanceMode.ACCEL_0_TO_X, PerformanceMode.ACCEL_X_TO_Y ->
                risingCross(prev, prevSpeed, sample, speed, cfg.endMps())?.let { completeAccel(cfg, it) }
            PerformanceMode.BRAKE_X_TO_Y ->
                fallingCross(prev, prevSpeed, sample, speed, cfg.endMps())?.let { completeBrake(cfg, it) }
            PerformanceMode.BRAKE_X_TO_0 ->
                fallingCross(prev, prevSpeed, sample, speed, cfg.zero.stopMps())?.let {
                    enterFinishCandidate(cfg, it, sample, speed)
                }
        }
    }

    private fun handleFinishCandidate(cfg: PerformanceConfig, sample: SpeedSample, speed: Double) {
        trackStationary(cfg, sample.sampleTimeNanos, speed <= cfg.zero.stopMps())
        if (bandSatisfied(cfg)) {
            val end = finishCandidate ?: return
            endCrossing = end
            distanceMeters = distanceTo(end.timeNanos, end.speedMps)
            phase = PerformancePhase.Completed
            finalizeQuality(cfg)
        } else if (speed > cfg.zero.launchMps()) {
            finishCandidate = null
            phase = PerformancePhase.Running
            resetBand()
        }
    }

    private fun beginRun(cross: CrossingPoint, sample: SpeedSample, speed: Double) {
        startCrossing = cross
        endCrossing = null
        finishCandidate = null
        distanceMeters = 0.0
        segment.clear()
        segment.add(cross.timeNanos to cross.speedMps)
        if (sample.sampleTimeNanos > cross.timeNanos) {
            integrate(cross.timeNanos, cross.speedMps, sample.sampleTimeNanos, speed)
        }
        phase = PerformancePhase.Running
        runStartedAtNanos = cross.timeNanos
        resetBand()
    }

    private fun completeAccel(cfg: PerformanceConfig, end: CrossingPoint) {
        val start = startCrossing ?: return
        if (end.timeNanos < start.timeNanos) return
        distanceMeters = distanceTo(end.timeNanos, end.speedMps)
        endCrossing = end
        phase = PerformancePhase.Completed
        finalizeQuality(cfg)
    }

    private fun completeBrake(cfg: PerformanceConfig, end: CrossingPoint) {
        val start = startCrossing ?: return
        if (end.timeNanos < start.timeNanos) return
        distanceMeters = distanceTo(end.timeNanos, end.speedMps)
        endCrossing = end
        phase = PerformancePhase.Completed
        finalizeQuality(cfg)
    }

    private fun enterFinishCandidate(
        cfg: PerformanceConfig,
        end: CrossingPoint,
        sample: SpeedSample,
        speed: Double,
    ) {
        finishCandidate = end
        distanceMeters = distanceTo(end.timeNanos, end.speedMps)
        phase = PerformancePhase.FinishCandidate
        resetBand()
        trackStationary(cfg, sample.sampleTimeNanos, speed <= cfg.zero.stopMps())
        if (bandSatisfied(cfg)) {
            endCrossing = end
            phase = PerformancePhase.Completed
            finalizeQuality(cfg)
        }
    }

    private fun integrate(t0: Long, v0: Double, t1: Long, v1: Double) {
        if (t1 <= t0) return
        distanceMeters += (v0 + v1) * 0.5 * ((t1 - t0).toDouble() / 1e9)
        segment.add(t1 to v1)
        if (segment.size > 10_000) segment.subList(0, segment.size - 8_000).clear()
    }

    private fun distanceTo(endTime: Long, endSpeed: Double): Double {
        if (segment.isEmpty()) return 0.0
        var dist = 0.0
        var i = 0
        while (i + 1 < segment.size) {
            val (ta, va) = segment[i]
            val (tb, vb) = segment[i + 1]
            if (tb <= endTime) {
                if (tb > ta) dist += (va + vb) * 0.5 * ((tb - ta).toDouble() / 1e9)
                i++
            } else {
                if (endTime > ta) {
                    dist += (va + endSpeed) * 0.5 * ((endTime - ta).toDouble() / 1e9)
                }
                return dist
            }
        }
        val (ta, va) = segment.last()
        if (endTime > ta) dist += (va + endSpeed) * 0.5 * ((endTime - ta).toDouble() / 1e9)
        return dist
    }

    private fun risingCross(
        prev: SpeedSample,
        prevSpeed: Double,
        sample: SpeedSample,
        speed: Double,
        threshold: Double,
    ): CrossingPoint? {
        if (!(prevSpeed < threshold && speed >= threshold)) return null
        if (sample.sampleTimeNanos <= prev.sampleTimeNanos) return null
        if (speed == prevSpeed) return null
        val alpha = (threshold - prevSpeed) / (speed - prevSpeed)
        if (alpha !in 0.0..1.0) return null
        val dt = sample.sampleTimeNanos - prev.sampleTimeNanos
        val tCross = prev.sampleTimeNanos + (alpha * dt).toLong()
        return CrossingPoint(tCross, threshold, prev.sequence, sample.sequence, alpha)
    }

    private fun fallingCross(
        prev: SpeedSample,
        prevSpeed: Double,
        sample: SpeedSample,
        speed: Double,
        threshold: Double,
    ): CrossingPoint? {
        if (!(prevSpeed > threshold && speed <= threshold)) return null
        if (sample.sampleTimeNanos <= prev.sampleTimeNanos) return null
        if (speed == prevSpeed) return null
        val alpha = (threshold - prevSpeed) / (speed - prevSpeed)
        if (alpha !in 0.0..1.0) return null
        val dt = sample.sampleTimeNanos - prev.sampleTimeNanos
        val tCross = prev.sampleTimeNanos + (alpha * dt).toLong()
        return CrossingPoint(tCross, threshold, prev.sequence, sample.sequence, alpha)
    }

    private fun classifyReject(cfg: PerformanceConfig, sample: SpeedSample): QualityReason? {
        if (lockedSourceId != null && sample.sourceId != lockedSourceId) return QualityReason.SOURCE_CHANGED
        if (lockedClockDomain != null && sample.clockDomain != lockedClockDomain) return QualityReason.CLOCK_DOMAIN_CHANGED
        if (lockedSourceId == null && sample.sourceId != cfg.expectedSourceId) return QualityReason.SOURCE_CHANGED
        lastAccepted?.let { prev ->
            if (sample.sampleTimeNanos <= prev.sampleTimeNanos) return QualityReason.DUPLICATE_OR_REORDERED_TIME
        }
        if (cfg.quality.rejectMock && sample.mock) return QualityReason.MOCK_LOCATION
        if (!sample.valid) return QualityReason.INVALID_SPEED
        val speed = sample.speedMps ?: return QualityReason.MISSING_SPEED
        if (!speed.isFinite()) return QualityReason.INVALID_SPEED
        if (cfg.quality.rejectNegativeSpeed && speed < 0.0) return QualityReason.NEGATIVE_SPEED
        return null
    }

    private fun trackStationary(cfg: PerformanceConfig, t: Long, inBand: Boolean) {
        if (!inBand) {
            resetBand()
            return
        }
        if (bandStartNanos == null) bandStartNanos = t
        bandCount++
        bandLastNanos = t
    }

    private fun bandSatisfied(cfg: PerformanceConfig): Boolean {
        val start = bandStartNanos ?: return false
        val last = bandLastNanos ?: return false
        return bandCount >= cfg.zero.minStationarySamples &&
            (last - start) >= cfg.zero.stationaryHoldMs * 1_000_000L
    }

    private fun resetBand() {
        bandStartNanos = null
        bandCount = 0
        bandLastNanos = null
    }

    private fun maybeTimeout(cfg: PerformanceConfig, now: Long) {
        if (phase in setOf(PerformancePhase.Armed, PerformancePhase.Ready, PerformancePhase.StartCandidate)) {
            armedAtNanos?.let { if (now - it > cfg.timeoutNs) fail(QualityReason.TIMEOUT) }
        }
        if (phase == PerformancePhase.Running || phase == PerformancePhase.FinishCandidate) {
            startCrossing?.let { if (now - it.timeNanos > cfg.timeoutNs) fail(QualityReason.TIMEOUT) }
        }
    }

    private fun fail(reason: QualityReason) {
        note(reason)
        quality = QualityTier.FAIL
        phase = PerformancePhase.Invalid
    }

    private fun softWarn(reason: QualityReason) {
        note(reason)
        if (quality == QualityTier.PASS) quality = QualityTier.WARN
    }

    private fun note(reason: QualityReason) {
        if (reason == QualityReason.OK) {
            if (reasons.isEmpty()) reasons.add(reason)
            return
        }
        reasons.remove(QualityReason.OK)
        if (reason !in reasons) reasons.add(reason)
    }

    private fun finalizeQuality(cfg: PerformanceConfig) {
        val hz = measuredHz()
        if (hz != null && hz < 1.0) softWarn(QualityReason.LOW_SAMPLE_RATE)
        if (speedAccuracyUnknown) softWarn(QualityReason.SPEED_ACCURACY_UNKNOWN)
        if (startCrossing == null || endCrossing == null) fail(QualityReason.INSUFFICIENT_SAMPLES)
        if (maxGapNs > cfg.quality.maxSampleGapNs / 2) {
            // informational only when already completed within gap rules
        }
    }

    private fun measuredHz(): Double? {
        val first = firstAcceptedNanos ?: return null
        val last = lastAccepted?.sampleTimeNanos ?: return null
        if (last <= first || acceptedInAttempt < 2) return null
        return (acceptedInAttempt - 1).toDouble() * 1e9 / (last - first).toDouble()
    }

    private fun buildResult(phaseOut: PerformancePhase): PerformanceResult {
        val cfg = requireNotNull(config) { "buildResult without config" }
        val start = startCrossing
        val end = endCrossing
        val duration = if (start != null && end != null && end.timeNanos >= start.timeNanos) {
            end.timeNanos - start.timeNanos
        } else null
        val distance = if (cfg.isBraking() && phaseOut == PerformancePhase.Completed && duration != null) {
            distanceMeters
        } else null
        val zeroDef =
            "stop=${cfg.zero.stopSpeedKmh} km/h, launch=${cfg.zero.launchSpeedKmh} km/h, " +
                "hold=${cfg.zero.stationaryHoldMs} ms, minSamples=${cfg.zero.minStationarySamples}; " +
                "0→X starts at launch threshold; X→0 ends at stop threshold after stationary confirm " +
                "(confirm wait excluded from score). Operational definition — not exact physical zero."
        return PerformanceResult(
            config = cfg,
            phase = phaseOut,
            quality = quality,
            reasons = reasons.toList(),
            start = start,
            end = end,
            durationNs = duration,
            distanceMeters = distance,
            stats = AttemptStats(
                acceptedSamples = acceptedInAttempt,
                rejectedSamples = rejectedInAttempt,
                maxGapNs = maxGapNs,
                measuredMeanHz = measuredHz(),
                speedAccuracyUnknown = speedAccuracyUnknown,
                reasons = reasons.toList(),
            ),
            algorithmVersion = cfg.algorithmVersion,
            zeroDefinition = zeroDef,
        )
    }

    companion object {
        private val ACTIVE = setOf(
            PerformancePhase.Armed,
            PerformancePhase.Ready,
            PerformancePhase.StartCandidate,
            PerformancePhase.Running,
            PerformancePhase.FinishCandidate,
        )
    }
}
