package com.edith.app.meross

import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C37: the MQTT connection to Meross checks the server's host name against its certificate. */
class MerossBusOptionsTest {
    private val creds = MerossCreds(
        token = "token", key = "key", userId = "u1", email = "owner@example.com",
        domain = "https://iotx-us.meross.com", mqttDomain = "mqtt-us.meross.com:443", installId = "install",
    )

    @Test fun `the server's host name is verified`() {
        assertTrue(connectOptions(creds, 5).isHttpsHostnameVerificationEnabled)
    }

    @Test fun `the user, the derived password and the protocol are set`() {
        val o = connectOptions(creds, 7)
        assertEquals("u1", o.userName)
        assertEquals(MerossProtocol.mqttPassword("u1", "key"), String(o.password))
        assertEquals(MqttConnectOptions.MQTT_VERSION_3_1_1, o.mqttVersion)
        assertEquals(7, o.connectionTimeout)
        assertTrue(o.isCleanSession)
    }
}
