package com.cmts.mobile.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalIpv4Test {
    @Test
    fun prefersWlanOverRmnet() {
        val picked = LocalIpv4.pickPreferred(
            listOf(
                LocalIpv4.Candidate("rmnet0", "10.0.0.5", siteLocal = true),
                LocalIpv4.Candidate("wlan0", "10.11.6.214", siteLocal = true),
            ),
        )
        assertEquals("10.11.6.214", picked)
    }

    @Test
    fun skipsRmnetWhenOtherSiteLocalExists() {
        val picked = LocalIpv4.pickPreferred(
            listOf(
                LocalIpv4.Candidate("rmnet_data0", "10.64.1.2", siteLocal = true),
                LocalIpv4.Candidate("eth0", "192.168.31.10", siteLocal = true),
            ),
        )
        assertEquals("192.168.31.10", picked)
    }

    @Test
    fun emptyReturnsNull() {
        assertNull(LocalIpv4.pickPreferred(emptyList()))
    }

    @Test
    fun buildPairingUriExactForm() {
        val uri = LocalIpv4.buildPairingUri("10.11.6.214", "72e3a38515d0")
        assertEquals("cmts://10.11.6.214:8787/cmts/v1?token=72e3a38515d0", uri)
    }
}
