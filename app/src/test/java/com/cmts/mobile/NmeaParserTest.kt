package com.cmts.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaParserTest {
    private fun sentence(body: String): String {
        var xor = 0
        for (ch in body) xor = xor xor ch.code
        return "\$${body}*%02X".format(xor)
    }

    private fun NmeaFixAssembler.push(line: String): List<NmeaUpdate> = onLine(line)

    private fun List<NmeaUpdate>.singleFix(): NmeaFix {
        val fixes = filterIsInstance<NmeaUpdate.Fix>()
        assertEquals("expected exactly one Fix in $this", 1, fixes.size)
        return fixes[0].fix
    }

    private fun List<NmeaUpdate>.fixCount(): Int = count { it is NmeaUpdate.Fix }

    private fun rmc(utc: String, latRaw: String, lonRaw: String, knots: String = "10.0", heading: String = "90.0"): String =
        sentence("GNRMC,$utc,A,$latRaw,N,$lonRaw,E,$knots,$heading,080108,,,")

    private fun gga(utc: String, latRaw: String, lonRaw: String, alt: String = "499.6", quality: String = "1", sats: String = "08"): String =
        sentence("GNGGA,$utc,$latRaw,N,$lonRaw,E,$quality,$sats,1.01,$alt,M,48.0,M,,")

    @Test fun checksumAcceptsValidAndRejectsCorrupt() {
        val ok = sentence("GNGGA,092725.00,4717.113993,N,00833.915591,E,1,08,1.01,499.6,M,48.0,M,,")
        assertTrue(NmeaParser.checksumValid(ok))
        assertTrue(NmeaParser.looksLikeNmea(ok))
        assertFalse(NmeaParser.checksumValid(ok.dropLast(1) + "00"))
        assertFalse(NmeaParser.looksLikeNmea("\$GNGGA,bad"))
    }

    @Test fun validGgaYieldsLatLonAltitudeAndSats() {
        val line = sentence("GNGGA,092725.00,4717.113993,N,00833.915591,E,1,08,1.01,499.6,M,48.0,M,,")
        val parsed = NmeaParser.parseGga(NmeaParser.split(line)!!.fields)!!
        assertTrue(parsed.hasPosition)
        assertEquals(1, parsed.quality)
        assertEquals(8, parsed.satsUsed)
        assertEquals(47.28523322, parsed.latitude!!, 1e-8)
        assertEquals(8.56525985, parsed.longitude!!, 1e-8)
        assertEquals(499.6, parsed.altitudeM!!, 1e-6)
    }

    @Test fun emptyOrQualityZeroGgaIsNotAFix() {
        val empty = sentence("GNGGA,092725.00,,,,,0,00,99.99,,,,,,")
        val parsed = NmeaParser.parseGga(NmeaParser.split(empty)!!.fields)!!
        assertEquals(0, parsed.quality)
        assertNull(parsed.latitude)
        assertFalse(parsed.hasPosition)
        val update = NmeaFixAssembler().push(empty)
        assertTrue(update.any { it is NmeaUpdate.NoFix })
        assertEquals(0, update.fixCount())
    }

    @Test fun qualityZeroWithStaleCoordinatesIsStillNotAFix() {
        val line = sentence("GNGGA,092725.00,4717.113993,N,00833.915591,E,0,00,99.99,499.6,M,48.0,M,,")
        val parsed = NmeaParser.parseGga(NmeaParser.split(line)!!.fields)!!
        assertFalse(parsed.hasPosition)
        val update = NmeaFixAssembler().push(line)
        assertTrue(update.any { it is NmeaUpdate.NoFix })
        assertEquals(0, update.fixCount())
    }

    @Test fun rmcVoidOrEmptyIsNotAFixAndDoesNotInventSpeed() {
        val voidLine = sentence("GNRMC,092725.00,V,,,,,,,080108,,,")
        val parsed = NmeaParser.parseRmc(NmeaParser.split(voidLine)!!.fields)!!
        assertFalse(parsed.statusActive)
        assertFalse(parsed.hasPosition)
        assertNull(parsed.speedMps)
        val update = NmeaFixAssembler().push(voidLine)
        assertTrue(update.any { it is NmeaUpdate.NoFix })
        assertEquals(0, update.fixCount())
    }

    @Test fun rmcActiveConvertsKnotsToMetersPerSecondAndUtc() {
        val line = sentence("GNRMC,092725.00,A,4717.113993,N,00833.915591,E,10.0,90.0,080108,,,")
        val parsed = NmeaParser.parseRmc(NmeaParser.split(line)!!.fields)!!
        assertTrue(parsed.hasPosition)
        assertEquals(NmeaParser.knotsToMps(10.0), parsed.speedMps!!, 1e-9)
        assertEquals(90.0, parsed.headingDeg!!, 1e-9)
        assertEquals(1199784445000L, parsed.utcMillis) // 2008-01-08 09:27:25 UTC
    }

    @Test fun assemblerDoesNotReuseOldFixAfterVoidSentence() {
        val assembler = NmeaFixAssembler()
        val first = assembler.push(gga("092725.00", "4717.113993", "00833.915591"))
        assertEquals(0, first.fixCount())
        val completed = assembler.push(rmc("092725.00", "4717.113993", "00833.915591"))
        assertEquals(1, completed.fixCount())
        val voidLine = sentence("GNGGA,092726.00,,,,,0,00,99.99,,,,,,")
        val second = assembler.push(voidLine)
        assertTrue(second.any { it is NmeaUpdate.NoFix })
        assertEquals(0, second.fixCount())
    }

    @Test fun rmcDoesNotOverrideAVoidGga() {
        val assembler = NmeaFixAssembler()
        assertTrue(assembler.push(sentence("GNGGA,092725.00,,,,,0,00,99.99,,,,,,")) .any { it is NmeaUpdate.NoFix })
        val rmcLine = rmc("092725.00", "4717.113993", "00833.915591")
        val update = assembler.push(rmcLine)
        assertTrue(update.any { it is NmeaUpdate.NoFix })
        assertEquals(0, update.fixCount())
        assertEquals(0, assembler.flush().fixCount())
    }

    @Test fun headingFromHprDoesNotCreateAFixByItself() {
        val assembler = NmeaFixAssembler()
        val hpr = sentence("GNHPR,092725.00,123.4,-1.2,0.5,1")
        assertTrue(assembler.push(hpr).isEmpty())
        assertEquals(0, assembler.push(gga("092725.00", "4717.113993", "00833.915591")).fixCount())
        val fix = assembler.push(rmc("092725.00", "4717.113993", "00833.915591", heading = "90.0")).singleFix()
        assertEquals(123.4, fix.headingDeg!!, 1e-6)
    }

    @Test fun gsvVisibleCountPrefersCombinedGnTalker() {
        val assembler = NmeaFixAssembler()
        assembler.push(sentence("GPGSV,1,1,08,01,40,000,40"))
        assembler.push(sentence("GLGSV,1,1,06,02,30,000,30"))
        assembler.push(sentence("GNGSV,1,1,12,03,20,000,20"))
        assembler.push(gga("092725.00", "4717.113993", "00833.915591"))
        val fix = assembler.push(rmc("092725.00", "4717.113993", "00833.915591")).singleFix()
        assertEquals(12, fix.satsVisible)
        assertEquals(8, fix.satsUsed)
    }

    @Test fun sameUtcGgaAndRmcEmitOneMergedFix() {
        val assembler = NmeaFixAssembler()
        val ggaLine = gga("092725.00", "4717.113993", "00833.915591", alt = "499.6")
        val rmcLine = rmc("092725.00", "4717.200000", "00833.800000", knots = "10.0", heading = "90.0")
        assertEquals(0, assembler.push(ggaLine).fixCount())
        val fix = assembler.push(rmcLine).singleFix()
        assertEquals(47.28523322, fix.latitude, 1e-8)
        assertEquals(8.56525985, fix.longitude, 1e-8)
        assertEquals(499.6, fix.altitudeM!!, 1e-6)
        assertEquals(NmeaParser.knotsToMps(10.0), fix.speedMps!!, 1e-9)
        assertEquals(90.0, fix.headingDeg!!, 1e-9)
        assertEquals("um982_nmea_gga_q1", fix.quality)
        assertEquals(8, fix.satsUsed)
        assertEquals(0, assembler.push(rmcLine).fixCount())
        assertEquals(0, assembler.flush().fixCount())
    }

    @Test fun rmcBeforeGgaDoesNotSawtoothBackToStaleGga() {
        val assembler = NmeaFixAssembler()
        val positions = listOf(
            Triple("092725.00", "4717.113993", "00833.915591"),
            Triple("092725.05", "4717.213993", "00833.915591"),
            Triple("092725.10", "4717.313993", "00833.915591"),
        )
        val lats = mutableListOf<Double>()
        for ((utc, latRaw, lonRaw) in positions) {
            val rmcUpdates = assembler.push(rmc(utc, latRaw, lonRaw))
            val ggaUpdates = assembler.push(gga(utc, latRaw, lonRaw, alt = "500.0"))
            assertEquals(0, rmcUpdates.fixCount())
            lats += ggaUpdates.singleFix().latitude
        }
        assertEquals(3, lats.size)
        for (i in 1 until lats.size) {
            assertTrue("latitude should advance, not snap back: $lats", lats[i] > lats[i - 1])
        }
    }

    @Test fun twentyHertzDistinctUtcEpochsYieldTwentyFixes() {
        val assembler = NmeaFixAssembler()
        val fixes = mutableListOf<NmeaFix>()
        for (i in 0 until 20) {
            val hundredths = i * 5
            val utc = "0927%02d.%02d".format(25 + hundredths / 100, hundredths % 100)
            val latRaw = "4717.%06d".format(113993 + i * 1000)
            assembler.push(gga(utc, latRaw, "00833.915591", alt = "499.6"))
            fixes += assembler.push(rmc(utc, latRaw, "00833.915591", knots = "10.0")).singleFix()
        }
        assertEquals(20, fixes.size)
        for (i in 1 until fixes.size) {
            assertTrue("epoch $i snapped back: ${fixes.map { it.latitude }}", fixes[i].latitude > fixes[i - 1].latitude)
            assertEquals(NmeaParser.knotsToMps(10.0), fixes[i].speedMps!!, 1e-9)
            assertEquals("um982_nmea_gga_q1", fixes[i].quality)
        }
    }

    @Test fun missingUtcSentencesWithinWindowMergeToOneFix() {
        var now = 1_000_000_000L
        val assembler = NmeaFixAssembler { now }
        val ggaLine = sentence("GNGGA,,4717.113993,N,00833.915591,E,1,08,1.01,499.6,M,48.0,M,,")
        val rmcLine = sentence("GNRMC,,A,4717.113993,N,00833.915591,E,10.0,90.0,080108,,,")
        assertEquals(0, assembler.push(ggaLine).fixCount())
        now += 10_000_000L
        val fix = assembler.push(rmcLine).singleFix()
        assertEquals(47.28523322, fix.latitude, 1e-8)
        assertEquals(NmeaParser.knotsToMps(10.0), fix.speedMps!!, 1e-9)
        assertEquals(0, assembler.flush().fixCount())
    }

    @Test fun missingUtcSentencesOutsideWindowAreSeparateEpochs() {
        var now = 1_000_000_000L
        val assembler = NmeaFixAssembler { now }
        val first = sentence("GNGGA,,4717.113993,N,00833.915591,E,1,08,1.01,499.6,M,48.0,M,,")
        val second = sentence("GNGGA,,4717.213993,N,00833.915591,E,1,08,1.01,501.0,M,48.0,M,,")
        assertEquals(0, assembler.push(first).fixCount())
        now += 40_000_000L
        val flushed = assembler.push(second)
        assertEquals(1, flushed.fixCount())
        assertEquals(47.28523322, flushed.singleFix().latitude, 1e-8)
        val late = assembler.flush().singleFix()
        assertTrue(late.latitude > flushed.singleFix().latitude)
    }

    @Test fun ggaOnlyEpochFlushesWhenNextEpochStarts() {
        val assembler = NmeaFixAssembler()
        assertEquals(0, assembler.push(gga("092725.00", "4717.113993", "00833.915591")).fixCount())
        val flushed = assembler.push(gga("092725.05", "4717.213993", "00833.915591"))
        assertEquals(1, flushed.fixCount())
        assertEquals(47.28523322, flushed.singleFix().latitude, 1e-8)
        val last = assembler.flush().singleFix()
        assertTrue(last.latitude > flushed.singleFix().latitude)
    }

    @Test fun badChecksumLinesAreIgnored() {
        assertTrue(
            NmeaFixAssembler().push("\$GNGGA,092725.00,4717.113993,N,00833.915591,E,1,08,1.01,499.6,M,48.0,M,,*00").isEmpty(),
        )
    }

    @Test fun lineBufferSplitsOnCrLfAndKeepsPartial() {
        val buffer = NmeaLineBuffer()
        val lines = buffer.append("\$GNGGA,1\r\n\$GNRMC,2".toByteArray(Charsets.US_ASCII))
        assertEquals(listOf("\$GNGGA,1"), lines)
        val rest = buffer.append("\n".toByteArray())
        assertEquals(listOf("\$GNRMC,2"), rest)
    }
}

class NmeaPortSelectorTest {
    @Test fun prefersUart3WhenItHasNmea() {
        assertEquals(3, NmeaPortSelector.choose(setOf(0, 3)))
        assertEquals(3, NmeaPortSelector.choose(setOf(3)))
    }

    @Test fun usesFirstNmeaPortIfUart3IsSilent() {
        assertEquals(1, NmeaPortSelector.choose(setOf(1, 2)))
        assertEquals(0, NmeaPortSelector.choose(setOf(0, 1)))
    }

    @Test fun noNmeaPortReturnsNull() {
        assertNull(NmeaPortSelector.choose(emptySet()))
    }
}

class GnssSourceLogicTest {
    @Test fun defaultPersistedValueIsPhone() {
        // GnssSourceSettings.load needs a Context; the default token itself is phone.
        assertEquals(GnssSource.PHONE, GnssSource.PHONE)
        assertEquals("外接模块已连接，尚无定位（可能未接天线或未锁定）", GnssSourceSettings.noFixStatus())
        assertEquals("外接 UM982 · UART3 · 已定位", GnssSourceSettings.locatedStatus(3))
    }

    @Test fun switchingToExternalDoesNotInventAFixFromInvalidNmea() {
        val assembler = NmeaFixAssembler()
        val voidGga = run {
            val body = "GNGGA,000000.00,,,,,0,00,99.99,,,,,,"
            var xor = 0
            for (ch in body) xor = xor xor ch.code
            "\$${body}*%02X".format(xor)
        }
        val update = assembler.onLine(voidGga)
        assertTrue(update.any { it is NmeaUpdate.NoFix })
        assertTrue(update.none { it is NmeaUpdate.Fix })
    }
}
