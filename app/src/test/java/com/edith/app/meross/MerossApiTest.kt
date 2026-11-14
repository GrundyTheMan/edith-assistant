package com.edith.app.meross

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C26 §1: sign-in (region redirect, 2FA, refusals), the device list and sign-out, against a pretend Meross. */
class MerossApiTest {
    private val cloud = FakeMerossCloud()
    @After fun tearDown() = cloud.shutdown()

    @Test fun `a sign-in sends a correctly signed request with the password as md5, and returns the token and key`() = runTest {
        cloud.answers["/v1/Auth/signIn"] = { cloud.loginOk() }
        val r = cloud.api().login("owner@example.com", "hunter2", null, "INSTALL-1")
        val creds = (r as MerossLogin.Ok).creds
        assertEquals(listOf("tok-1", "key-1", "4242"), listOf(creds.token, creds.key, creds.userId))
        assertEquals("a signed request, as Meross checks it", emptyList<String>(), cloud.badSigns.toList())
        val sent = cloud.sent["/v1/Auth/signIn"]!!
        assertEquals(MerossProtocol.md5("hunter2"), sent.text("password"))
        assertEquals("owner@example.com", sent.text("email"))
        assertTrue("the plain password never goes out", "hunter2" !in sent.toString())
        assertEquals("Basic", cloud.authorization("/v1/Auth/signIn"))
    }

    @Test fun `a redirect to another region is followed once`() = runTest {
        val other = FakeMerossCloud()
        try {
            other.answers["/v1/Auth/signIn"] = { other.loginOk(domain = other.base) }
            cloud.answers["/v1/Auth/signIn"] = { cloud.status(1030, """{"domain":"${other.base}","mqttDomain":"mqtt-eu.example.test"}""") }
            val creds = (cloud.api().login("a@b.c", "pw", null, "I") as MerossLogin.Ok).creds
            assertEquals(other.base, creds.domain)
            assertEquals(1, other.requests.size)
        } finally { other.shutdown() }
    }

    @Test fun `a second redirect is not followed`() = runTest {
        cloud.answers["/v1/Auth/signIn"] = { cloud.status(1030, """{"domain":"${cloud.base}"}""") }
        assertTrue(cloud.api().login("a@b.c", "pw", null, "I") is MerossLogin.Failed)
        assertEquals(2, cloud.requests.size)
    }

    @Test fun `2FA - a code is asked for, then sent with the next sign-in`() = runTest {
        cloud.answers["/v1/Auth/signIn"] = { cloud.status(1033) }
        assertEquals(MerossLogin.NeedsCode, cloud.api().login("a@b.c", "pw", null, "I"))
        cloud.answers["/v1/Auth/signIn"] = { cloud.status(1032) }
        assertEquals(MerossLogin.WrongCode, cloud.api().login("a@b.c", "pw", "000000", "I"))
        assertEquals("000000", cloud.sent["/v1/Auth/signIn"]!!.text("mfaCode"))
        cloud.answers["/v1/Auth/signIn"] = { cloud.loginOk() }
        assertTrue(cloud.api().login("a@b.c", "pw", "123456", "I") is MerossLogin.Ok)
    }

    @Test fun `refusals are told apart`() = runTest {
        cloud.answers["/v1/Auth/signIn"] = { cloud.status(1004) }
        assertEquals(MerossLogin.BadLogin, cloud.api().login("a@b.c", "pw", null, "I"))
        cloud.answers["/v1/Auth/signIn"] = { cloud.status(1002) }
        assertEquals(MerossLogin.BadLogin, cloud.api().login("a@b.c", "pw", null, "I"))
        cloud.answers["/v1/Auth/signIn"] = { cloud.status(1301) }
        assertEquals(MerossLogin.TooManyTokens, cloud.api().login("a@b.c", "pw", null, "I"))
        cloud.answers["/v1/Auth/signIn"] = { cloud.status(9999) }
        assertTrue((cloud.api().login("a@b.c", "pw", null, "I") as MerossLogin.Failed).why.contains("9999"))
    }

    @Test fun `a cloud that can't be reached or answers nonsense is a plain failure`() = runTest {
        cloud.answers["/v1/Auth/signIn"] = { "this isn't json" }
        assertTrue(cloud.api().login("a@b.c", "pw", null, "I") is MerossLogin.Failed)
        cloud.shutdown()
        assertTrue(cloud.api().login("a@b.c", "pw", null, "I") is MerossLogin.Failed)
    }

    @Test fun `the device list uses the token, keeps garage openers apart, and a refused token is Rejected`() = runTest {
        val creds = MerossCreds("tok-1", "key-1", "4242", "a@b.c", cloud.base, "m", "I")
        cloud.answers["/v1/Device/devList"] = { cloud.devList(cloud.device("u1", "Garage", "msg100"), cloud.device("u2", "Lamp", "mss110"), cloud.device("u3", "Back", "MSG200", online = 2)) }
        val list = (cloud.api().devices(creds) as MerossReply.Ok).value
        assertEquals("Basic tok-1", cloud.authorization("/v1/Device/devList"))
        assertEquals(listOf("u1", "u3"), list.filter { it.isGarageOpener }.map { it.uuid })
        assertEquals(listOf(true, true, false), list.map { it.online }.let { listOf(it[0], it[1], it[2]) })
        for (code in listOf(1019, 1022, 1200)) {
            cloud.answers["/v1/Device/devList"] = { cloud.status(code) }
            assertEquals("code $code", MerossReply.Rejected, cloud.api().devices(creds))
        }
        cloud.answers["/v1/Device/devList"] = { cloud.status(5000) }
        assertTrue(cloud.api().devices(creds) is MerossReply.Failed)
    }

    @Test fun `sign-out returns the token`() = runTest {
        cloud.answers["/v1/Profile/logout"] = { cloud.status(0) }
        cloud.api().logout(MerossCreds("tok-1", "key-1", "4242", "a@b.c", cloud.base, "m", "I"))
        assertEquals("Basic tok-1", cloud.authorization("/v1/Profile/logout"))
    }
}
