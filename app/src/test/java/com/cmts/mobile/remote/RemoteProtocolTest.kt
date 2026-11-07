package com.cmts.mobile.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProtocolTest {
    @Test
    fun envelopeHasRequiredFields() {
        val msg = RemoteProtocol.envelope(
            type = "device.hello",
            deviceId = "dev-1",
            payload = JSONObject().put("ok", true),
            id = "h1",
            ts = 123L,
        )
        assertEquals(1, msg.getInt("v"))
        assertEquals("device.hello", msg.getString("type"))
        assertEquals("h1", msg.getString("id"))
        assertEquals(123L, msg.getLong("ts"))
        assertEquals("dev-1", msg.getString("deviceId"))
        assertTrue(msg.getJSONObject("payload").getBoolean("ok"))
    }

    @Test
    fun nullablesUseJsonNullNotZero() {
        val o = JSONObject()
        RemoteProtocol.putNullable(o, "speedKph", null)
        RemoteProtocol.putNullable(o, "ax", 0.0)
        assertTrue(o.isNull("speedKph"))
        assertEquals(0.0, o.getDouble("ax"), 0.0)
    }

    @Test
    fun samplePayloadForbidsSilentZeroWhenInvalid() {
        val payload = JSONObject()
        RemoteProtocol.putNullable(payload, "lat", null)
        payload.put("valid", JSONObject().put("gnss", false))
        assertTrue(payload.isNull("lat"))
        assertFalse(payload.getJSONObject("valid").getBoolean("gnss"))
    }

    @Test
    fun parseRoundTrip() {
        val raw = """{"v":1,"type":"record.start","id":"r1","payload":{"mode":"manual"}}"""
        val msg = RemoteProtocol.parse(raw)
        assertEquals("record.start", RemoteProtocol.typeOf(msg))
        assertEquals("r1", RemoteProtocol.idOf(msg))
        assertEquals("manual", RemoteProtocol.payloadOf(msg).getString("mode"))
    }
}
