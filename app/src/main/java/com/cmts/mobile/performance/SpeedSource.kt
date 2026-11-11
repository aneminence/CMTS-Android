package com.cmts.mobile.performance

/**
 * Adapters feed [SpeedSample] into [PerformanceEngine] on a single ordered worker thread.
 * Phone GNSS, future UM982, and CMTS host implement this; none may invent samples from UI.
 */
interface SpeedSource {
    val sourceId: String
    val channelKind: String
    fun start(emit: (SpeedSample) -> Unit)
    fun stop()
}

/** Marker for the phone LocationManager GNSS path (wired in stage B). */
class PhoneGnssSpeedSourcePlaceholder : SpeedSource {
    override val sourceId: String = "phone.gps"
    override val channelKind: String = "gnss"
    override fun start(emit: (SpeedSample) -> Unit) {
        error("Phone GNSS speed source is wired in CaptureService (stage B), not here")
    }
    override fun stop() = Unit
}

/**
 * UM982 NMEA adapter is wired through CaptureService (external GNSS source).
 * Sample time is phone receive elapsedRealtime; UTC comes from RMC when present.
 * Invalid/void NMEA must not be turned into speed samples.
 */
interface Um982SpeedSource : SpeedSource

/** Future CMTS host adapter — channelKind must say gnss vs wheel etc. */
interface CmtsHostSpeedSource : SpeedSource
