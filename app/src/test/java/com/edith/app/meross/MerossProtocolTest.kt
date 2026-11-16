package com.edith.app.meross

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C26: signing, topics and messages, against values worked out independently (`md5sum`) from meross-iot's formulas. */
class MerossProtocolTest {
    @Test fun `the sign-in sign is md5 of secret, timestamp in ms, nonce and the encoded params`() {
        assertEquals("25f64922be265cadcbb1eff4a691f017", MerossProtocol.requestSign(1_700_000_000_000, "ABCDEFGHIJKLMNOP", "e30="))
        assertEquals("e30=", MerossProtocol.encodeParams(JsonObject(emptyMap())))
        val body = MerossProtocol.envelope(JsonObject(emptyMap()), 1_700_000_000_000, "ABCDEFGHIJKLMNOP")
        assertEquals(setOf("params", "sign", "timestamp", "nonce"), body.keys)
        assertEquals("25f64922be265cadcbb1eff4a691f017", body["sign"].toString().trim('"'))
    }

    @Test fun `the mqtt password and app id`() {
        assertEquals("a50c03902d817c55974a7905e832aab0", MerossProtocol.mqttPassword("4242", "secretkey"))
        assertEquals("d4ba7e481160937f59429787eef0f67b", MerossProtocol.appId("11111111-2222-3333-4444-555555555555"))
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", MerossProtocol.md5("password"))
    }

    @Test fun `the nonce is 16 capital letters and digits`() {
        repeat(20) { assertTrue(Regex("[A-Z0-9]{16}").matches(MerossProtocol.nonce())) }
    }

    @Test fun `topics`() {
        assertEquals("/appliance/abc/subscribe", MerossProtocol.requestTopic("abc"))
        assertEquals("/app/4242-appid/subscribe", MerossProtocol.responseTopic("4242", "appid"))
    }

    @Test fun `a message carries a header signed with the key, and only a correctly signed one verifies`() {
        val payload = buildJsonObject { put("state", buildJsonObject { put("channel", 0); put("open", 1) }) }
        val (text, id) = MerossProtocol.message("SET", MerossProtocol.GARAGE_STATE, payload, "uuid1", "secretkey", "/app/4242-appid/subscribe", 1_700_000_000)
        val whole = Json.parseToJsonElement(text).jsonObject
        val header = whole["header"]!!.jsonObject
        assertEquals(id, header.text("messageId"))
        assertEquals(listOf("SET", "Appliance.GarageDoor.State", "uuid1", "/app/4242-appid/subscribe", "Android"),
            listOf("method", "namespace", "uuid", "from", "triggerSrc").map { header.text(it) })
        assertEquals(1, header.int("payloadVersion"))
        assertEquals(payload, whole["payload"])
        assertTrue(MerossProtocol.verify(header, "secretkey"))
        assertFalse("another account's key", MerossProtocol.verify(header, "otherkey"))
        val forged = JsonObject(header + ("sign" to kotlinx.serialization.json.JsonPrimitive("0".repeat(32))))
        assertFalse(MerossProtocol.verify(forged, "secretkey"))
        assertTrue("a known header", MerossProtocol.verify(buildJsonObject {
            put("messageId", "f00dbabe"); put("timestamp", 1_700_000_000); put("sign", "e9a7a4d215f630e492bb9b5dc7154d4d")
        }, "secretkey"))
    }

    @Test fun `hosts and ports`() {
        assertEquals("mqtt-us.meross.com" to 443, MerossProtocol.hostAndPort("mqtt-us.meross.com"))
        assertEquals("mqtt-eu.meross.com" to 8883, MerossProtocol.hostAndPort("mqtt-eu.meross.com:8883"))
        assertEquals("https://iotx-us.meross.com", MerossProtocol.apiBase("iotx-us.meross.com"))
        assertEquals("http://localhost:1", MerossProtocol.apiBase("http://localhost:1/"))
    }

    @Test fun `only https addresses under meross dot com are trusted`() {
        assertTrue(MerossProtocol.isTrustedDomain("iotx-eu.meross.com"))
        assertTrue(MerossProtocol.isTrustedDomain("https://iotx-us.meross.com/"))
        assertTrue(MerossProtocol.isTrustedDomain("mqtt-us.meross.com:443"))
        assertTrue(MerossProtocol.isTrustedDomain("IOTX-AP.MEROSS.COM"))
        assertFalse("plain http never", MerossProtocol.isTrustedDomain("http://iotx-us.meross.com"))
        assertFalse(MerossProtocol.isTrustedDomain("evil.example.com"))
        assertFalse("a lookalike", MerossProtocol.isTrustedDomain("iotx-us.meross.com.evil.example"))
        assertFalse("a lookalike", MerossProtocol.isTrustedDomain("notmeross.com"))
        assertFalse("user info", MerossProtocol.isTrustedDomain("https://iotx-us.meross.com@evil.example"))
        assertFalse("a path", MerossProtocol.isTrustedDomain("iotx-us.meross.com/redirect"))
        assertFalse(MerossProtocol.isTrustedDomain(""))
    }
}
