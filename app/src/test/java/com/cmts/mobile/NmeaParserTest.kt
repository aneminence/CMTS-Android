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

    @Test fun checksumAcceptsValidAndRejectsCorrupt() {
        val ok = sentence("GNGGA,092725.00,4717.113993,N,00833.915591,E,1,08,1.01,499.6,M,48.0,M,,")
        assertTrue(NmeaParser.checksumValid(ok))
        assertTrue(NmeaParser.looksLikeNmea(ok))
        assertFalse(NmeaParser.checksumValid(ok.dropLast(1) + "00"))
        assertFalse(NmeaParser.looksLikeNmea("\$GNGGA,bad"))
    }

    @Test fun validGgaYieldsLatLonAltitudeAndSats() {
        val line = sentence("GNGGA,092725.00,4717.113993,N,00833.915591,E,1,08,1.01,499.6,M,48.0,M,,")
        val gga = NmeaParser.parseGga(NmeaParser.split(line)!!.fields)!!
        assertTrue(gga.hasPosition)
        assertEquals(1, gga.quality)
        assertEquals(8, gga.satsUsed)
        assertEquals(47.28523322, gga.latitude!!, 1e-8)
        assertEquals(8.56525985, gga.longitude!!, 1e-8)
        assertEquals(499.6, gga.altitudeM!!, 1e-6)
    }

    @Test fun emptyOrQualityZeroGgaIsNotAFix() {
        val empty = sentence("GNGGA,092725.00,,,,,0,00,99.99,,,,,,")
        val gga = NmeaParser.parseGga(NmeaParser.split(empty)!!.fields)!!
        assertEquals(0, gga.quality)
        assertNull(gga.latitude)
        assertFalse(gga.hasPosition)
        val update = NmeaFixAssembler().onLine(empty)
        assertTrue(update is NmeaUpdate.NoFix)
    }

    @Test fun qualityZeroWithStaleCoordinatesIsStillNotAFix() {
        val line = sentence("GNGGA,092725.00,4717.113993,N,00833.915591,E,0,00,99.99,499.6,M,48.0,M,,")
        val gga = NmeaParser.parseGga(NmeaParser.split(line)!!.fields)!!
        assertFalse(gga.hasPosition)
        assertTrue(NmeaFixAssembler().onLine(line) is NmeaUpdate.NoFix)
    }

    @Test fun rmcVoidOrEmptyIsNotAFixAndDoesNotInventSpeed() {
        val voidLine = sentence("GNRMC,092725.00,V,,,,,,,080108,,,")
        val rmc = NmeaParser.parseRmc(NmeaParser.split(voidLine)!!.fields)!!
        assertFalse(rmc.statusActive)
        assertFalse(rmc.hasPosition)
        assertNull(rmc.speedMps)
        assertTrue(NmeaFixAssembler().onLine(voidLine) is NmeaUpdate.NoFix)
    }

    @Test fun rmcActiveConvertsKnotsToMetersPerSecondAndUtc() {
        val line = sentence("GNRMC,092725.00,A,4717.113993,N,00833.915591,E,10.0,90.0,080108,,,")
        val rmc = NmeaParser.parseRmc(NmeaParser.split(line)!!.fields)!!
        assertTrue(rmc.hasPosition)
        assertEquals(NmeaParser.knotsToMps(10.0), rmc.speedMps!!, 1e-9)
        assertEquals(90.0, rmc.headingDeg!!, 1e-9)
        assertEquals(1199784445000L, rmc.utcMillis) // 2008-01-08 09:27:25 UTC
    }

    @Test fun assemblerDoesNotReuseOldFixAfterVoidSentence() {
        val assembler = NmeaFixAssembler()
        val fixLine = sentence("GNGGA,092725.00,4717.113993,N,00833.915591,E,1,08,1.01,499.6,M,48.0,M,,")
        val voidLine = sentence("GNGGA,092726.00,,,,,0,00,99.99,,,,,,")
        val first = assembler.onLine(fixLine)
        assertTrue(first is NmeaUpdate.Fix)
        val second = assembler.onLine(voidLine)
        assertTrue(second is NmeaUpdate.NoFix)
    }

    @Test fun rmcDoesNotOverrideAVoidGga() {
        val assembler = NmeaFixAssembler()
        assertTrue(assembler.onLine(sentence("GNGGA,092725.00,,,,,0,00,99.99,,,,,,")) is NmeaUpdate.NoFix)
        val rmc = sentence("GNRMC,092725.00,A,4717.113993,N,00833.915591,E,10.0,90.0,080108,,,")
        assertTrue(assembler.onLine(rmc) is NmeaUpdate.NoFix)
    }

    @Test fun headingFromHprDoesNotCreateAFixByItself() {
        val assembler = NmeaFixAssembler()
        val hpr = sentence("GNHPR,092725.00,123.4,-1.2,0.5,1")
        assertEquals(NmeaUpdate.Ignored, assembler.onLine(hpr))
        val gga = sentence("GNGGA,092725.00,4717.113993,N,00833.915591,E,1,08,1.01,499.6,M,48.0,M,,")
        val fix = (assembler.onLine(gga) as NmeaUpdate.Fix).fix
        assertEquals(123.4, fix.headingDeg!!, 1e-6)
    }

    @Test fun gsvVisibleCountPrefersCombinedGnTalker() {
        val assembler = NmeaFixAssembler()
        assembler.onLine(sentence("GPGSV,1,1,08,01,40,000,40"))
        assembler.onLine(sentence("GLGSV,1,1,06,02,30,000,30"))
        assembler.onLine(sentence("GNGSV,1,1,12,03,20,000,20"))
        val gga = sentence("GNGGA,092725.00,4717.113993,N,00833.915591,E,1,08,1.01,499.6,M,48.0,M,,")
        val fix = (assembler.onLine(gga) as NmeaUpdate.Fix).fix
        assertEquals(12, fix.satsVisible)
        assertEquals(8, fix.satsUsed)
    }

    @Test fun badChecksumLinesAreIgnored() {
        assertEquals(NmeaUpdate.Ignored, NmeaFixAssembler().onLine("\$GNGGA,092725.00,4717.113993,N,00833.915591,E,1,08,1.01,499.6,M,48.0,M,,*00"))
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
        assertTrue(update is NmeaUpdate.NoFix)
        assertFalse(update is NmeaUpdate.Fix)
    }
}
