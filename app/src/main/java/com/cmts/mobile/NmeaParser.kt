package com.cmts.mobile

/**
 * Pure NMEA-0183 helpers. Invalid or empty sentences never become a fix.
 * Coordinates are only produced from checksum-valid GGA (quality > 0) or RMC (status A).
 */
object NmeaParser {
    data class Fields(val talker: String, val type: String, val fields: List<String>, val raw: String)

    data class Gga(
        val utcHms: String,
        val latitude: Double?,
        val longitude: Double?,
        val quality: Int,
        val satsUsed: Int?,
        val hdop: Double?,
        val altitudeM: Double?,
        val hasPosition: Boolean,
    )

    data class Rmc(
        val utcHms: String,
        val statusActive: Boolean,
        val latitude: Double?,
        val longitude: Double?,
        val speedMps: Double?,
        val headingDeg: Double?,
        val utcMillis: Long?,
        val hasPosition: Boolean,
    )

    data class Hpr(val headingDeg: Double?, val pitchDeg: Double?, val rollDeg: Double?)

    fun checksumValid(sentence: String): Boolean {
        val start = sentence.indexOf('$')
        val star = sentence.lastIndexOf('*')
        if (start < 0 || star <= start + 1 || star + 3 > sentence.length) return false
        val body = sentence.substring(start + 1, star)
        val given = sentence.substring(star + 1, star + 3)
        val expected = given.toIntOrNull(16) ?: return false
        var xor = 0
        for (ch in body) xor = xor xor ch.code
        return xor == expected
    }

    fun looksLikeNmea(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith('$') && trimmed.length >= 8 && checksumValid(trimmed)
    }

    fun split(line: String): Fields? {
        val trimmed = line.trim()
        if (!checksumValid(trimmed)) return null
        val body = trimmed.substring(1, trimmed.lastIndexOf('*'))
        val parts = body.split(',')
        val head = parts.firstOrNull() ?: return null
        if (head.length < 5) return null
        return Fields(head.substring(0, 2), head.substring(2), parts.drop(1), trimmed)
    }

    /** Packed ddmm.mmmm / dddmm.mmmm plus hemisphere to signed degrees; empty or out of range is null. */
    fun parseHemisphereDegrees(raw: String, hemi: String): Double? {
        if (raw.isBlank() || hemi.isBlank()) return null
        val h = hemi.uppercase()
        val sign = when (h) {
            "N", "E" -> 1.0
            "S", "W" -> -1.0
            else -> return null
        }
        val value = raw.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value < 0.0) return null
        val intPart = raw.substringBefore('.')
        if (intPart.length < 3) return null
        val degrees = kotlin.math.floor(value / 100.0)
        val minutes = value - degrees * 100.0
        if (minutes >= 60.0) return null
        val result = sign * (degrees + minutes / 60.0)
        val abs = kotlin.math.abs(result)
        return when (h) {
            "N", "S" -> result.takeIf { abs <= 90.0 }
            else -> result.takeIf { abs <= 180.0 }
        }
    }

    fun knotsToMps(knots: Double): Double = knots * 1852.0 / 3600.0

    fun parseGga(fields: List<String>): Gga? {
        if (fields.size < 6) return null
        val quality = fields[5].toIntOrNull() ?: return null
        val lat = parseHemisphereDegrees(fields.getOrElse(1) { "" }, fields.getOrElse(2) { "" })
        val lon = parseHemisphereDegrees(fields.getOrElse(3) { "" }, fields.getOrElse(4) { "" })
        val sats = fields.getOrNull(6)?.takeIf { it.isNotBlank() }?.toIntOrNull()
        val hdop = fields.getOrNull(7)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val alt = fields.getOrNull(8)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        return Gga(fields[0], lat, lon, quality, sats, hdop, alt, quality > 0 && lat != null && lon != null)
    }

    fun parseRmc(fields: List<String>): Rmc? {
        if (fields.size < 2) return null
        val active = fields[1].equals("A", ignoreCase = true)
        val lat = parseHemisphereDegrees(fields.getOrElse(2) { "" }, fields.getOrElse(3) { "" })
        val lon = parseHemisphereDegrees(fields.getOrElse(4) { "" }, fields.getOrElse(5) { "" })
        val speed = fields.getOrNull(6)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.let { knotsToMps(it) }
        val heading = fields.getOrNull(7)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val utc = parseUtc(fields[0], fields.getOrElse(8) { "" })
        return Rmc(fields[0], active, lat, lon, speed, heading, utc, active && lat != null && lon != null)
    }

    fun parseHpr(fields: List<String>): Hpr? {
        if (fields.isEmpty()) return null
        fun num(index: Int): Double? = fields.getOrNull(index)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val timeLike = fields[0].matches(Regex("""\d{6}(\.\d+)?"""))
        val base = if (timeLike) 1 else 0
        return Hpr(num(base), num(base + 1), num(base + 2))
    }

    fun parseGsvVisible(fields: List<String>): Int? =
        fields.getOrNull(2)?.toIntOrNull()?.takeIf { it >= 0 }

    fun parseUtc(hms: String, dmy: String): Long? {
        if (hms.length < 6 || dmy.length < 6) return null
        val hour = hms.substring(0, 2).toIntOrNull() ?: return null
        val minute = hms.substring(2, 4).toIntOrNull() ?: return null
        val second = hms.substring(4).toDoubleOrNull() ?: return null
        val day = dmy.substring(0, 2).toIntOrNull() ?: return null
        val month = dmy.substring(2, 4).toIntOrNull() ?: return null
        val year2 = dmy.substring(4, 6).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59 || second < 0.0 || second >= 60.0) return null
        if (day !in 1..31 || month !in 1..12) return null
        val year = if (year2 >= 80) 1900 + year2 else 2000 + year2
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second.toInt())
        calendar.set(java.util.Calendar.MILLISECOND, ((second - second.toInt()) * 1000.0).toInt())
        return calendar.timeInMillis
    }
}

data class NmeaFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val speedMps: Double?,
    val headingDeg: Double?,
    val utcMillis: Long?,
    val satsUsed: Int?,
    val satsVisible: Int?,
    val quality: String,
)

sealed class NmeaUpdate {
    data class Fix(val fix: NmeaFix) : NmeaUpdate()
    data class NoFix(val reason: String) : NmeaUpdate()
    data object Ignored : NmeaUpdate()
}

/** Combines GGA / RMC / HPR / GSV into at most one fix per NMEA epoch. A void or empty sentence never reuses an older fix. */
class NmeaFixAssembler(private val elapsedNanos: () -> Long = { System.nanoTime() }) {
    companion object {
        const val MERGE_WINDOW_NANOS = 25_000_000L
    }

    private class Epoch(
        val key: String,
        val firstNanos: Long,
        var gga: NmeaParser.Gga? = null,
        var rmc: NmeaParser.Rmc? = null,
        var hpr: NmeaParser.Hpr? = null,
        var ggaVoid: Boolean = false,
        var emitted: Boolean = false,
    )

    private var epoch: Epoch? = null
    private var lastHpr: NmeaParser.Hpr? = null
    private val visibleByTalker = mutableMapOf<String, Int>()

    fun onLine(line: String): List<NmeaUpdate> {
        val parsed = NmeaParser.split(line) ?: return emptyList()
        val now = elapsedNanos()
        return when (parsed.type) {
            "GGA" -> {
                val gga = NmeaParser.parseGga(parsed.fields) ?: return emptyList()
                val out = mutableListOf<NmeaUpdate>()
                val current = enterEpoch(epochKey(gga.utcHms, now), now, out)
                current.gga = gga
                if (!gga.hasPosition) {
                    current.ggaVoid = true
                    out += NmeaUpdate.NoFix(if (gga.quality <= 0) "GGA 未锁定" else "GGA 坐标为空")
                } else {
                    emitIfComplete(current)?.let { out += it }
                }
                out
            }
            "RMC" -> {
                val rmc = NmeaParser.parseRmc(parsed.fields) ?: return emptyList()
                val out = mutableListOf<NmeaUpdate>()
                val current = enterEpoch(epochKey(rmc.utcHms, now), now, out)
                current.rmc = rmc
                if (!rmc.hasPosition) {
                    out += NmeaUpdate.NoFix(if (!rmc.statusActive) "RMC 状态无效" else "RMC 坐标为空")
                } else if (current.ggaVoid) {
                    out += NmeaUpdate.NoFix(
                        if (current.gga?.quality ?: 0 <= 0) "GGA 未锁定" else "GGA 坐标为空",
                    )
                } else {
                    emitIfComplete(current)?.let { out += it }
                }
                out
            }
            "HPR" -> {
                val hpr = NmeaParser.parseHpr(parsed.fields) ?: return emptyList()
                lastHpr = hpr
                val timeField = parsed.fields.firstOrNull().orEmpty()
                val timed = timeField.matches(Regex("""\d{6}(\.\d+)?"""))
                val current = epoch
                if (current != null && (!timed || current.key == "utc:$timeField")) {
                    current.hpr = hpr
                }
                emptyList()
            }
            "GSV" -> {
                NmeaParser.parseGsvVisible(parsed.fields)?.let { visibleByTalker[parsed.talker] = it }
                emptyList()
            }
            else -> emptyList()
        }
    }

    /** Emits a pending GGA-only or RMC-only epoch. Safe to call at stream end. */
    fun flush(): List<NmeaUpdate> {
        val current = epoch ?: return emptyList()
        if (current.emitted) return emptyList()
        val fix = buildFix(current) ?: return emptyList()
        current.emitted = true
        return listOf(NmeaUpdate.Fix(fix))
    }

    private fun epochKey(utcHms: String, now: Long): String {
        if (utcHms.isNotBlank()) return "utc:$utcHms"
        val current = epoch
        if (current != null && now - current.firstNanos <= MERGE_WINDOW_NANOS) return current.key
        return "t:$now"
    }

    private fun enterEpoch(key: String, now: Long, out: MutableList<NmeaUpdate>): Epoch {
        val current = epoch
        if (current != null && current.key == key) return current
        if (current != null && !current.emitted) {
            buildFix(current)?.let {
                current.emitted = true
                out += NmeaUpdate.Fix(it)
            }
        }
        return Epoch(key, now).also { epoch = it }
    }

    private fun emitIfComplete(epoch: Epoch): NmeaUpdate? {
        if (epoch.emitted) return null
        if (epoch.gga == null || epoch.rmc == null) return null
        val fix = buildFix(epoch) ?: return null
        epoch.emitted = true
        return NmeaUpdate.Fix(fix)
    }

    private fun visibleCount(): Int? {
        if (visibleByTalker.isEmpty()) return null
        visibleByTalker["GN"]?.let { return it }
        return visibleByTalker.values.sum()
    }

    private fun buildFix(epoch: Epoch): NmeaFix? {
        if (epoch.ggaVoid) return null
        val gga = epoch.gga
        val rmc = epoch.rmc
        val lat = when {
            gga?.hasPosition == true -> gga.latitude
            rmc?.hasPosition == true -> rmc.latitude
            else -> null
        }
        val lon = when {
            gga?.hasPosition == true -> gga.longitude
            rmc?.hasPosition == true -> rmc.longitude
            else -> null
        }
        if (lat == null || lon == null) return null
        val heading = epoch.hpr?.headingDeg ?: lastHpr?.headingDeg ?: rmc?.headingDeg
        val quality = buildString {
            append("um982_nmea")
            if (gga != null) append("_gga_q").append(gga.quality)
        }
        return NmeaFix(
            latitude = lat,
            longitude = lon,
            altitudeM = gga?.altitudeM,
            speedMps = rmc?.speedMps,
            headingDeg = heading,
            utcMillis = rmc?.utcMillis,
            satsUsed = gga?.satsUsed,
            satsVisible = visibleCount(),
            quality = quality,
        )
    }
}

object NmeaPortSelector {
    const val PREFERRED_PORT = 3

    fun choose(portsWithNmea: Set<Int>, preferred: Int = PREFERRED_PORT): Int? {
        if (preferred in portsWithNmea) return preferred
        return portsWithNmea.filter { it >= 0 }.minOrNull()
    }
}

/** Accumulates raw UART bytes into complete NMEA lines. */
class NmeaLineBuffer(private val limit: Int = 8192) {
    private val buffer = StringBuilder()

    fun append(data: ByteArray): List<String> {
        buffer.append(data.toString(Charsets.US_ASCII))
        if (buffer.length > limit) buffer.delete(0, buffer.length - limit)
        val text = buffer.toString()
        val lines = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') {
                val line = text.substring(start, i).trim()
                if (line.isNotEmpty()) lines += line
                while (i < text.length && (text[i] == '\n' || text[i] == '\r')) i++
                start = i
            } else {
                i++
            }
        }
        buffer.setLength(0)
        if (start < text.length) buffer.append(text.substring(start))
        return lines
    }
}
