package com.edith.app.blink

import com.edith.app.home.EventKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C29: reading Blink's answers, from made-up captures shaped like what blinkpy and Jarvis read. */
class BlinkParseTest {
    private fun parse(text: String) = Json.parseToJsonElement(text)
    private val cam = FakeBlink.Companion

    @Test fun `the homescreen lists cameras, minis and doorbells by name`() {
        val list = BlinkParse.homescreen(parse(FakeBlink.homescreenJson(
            cameras = listOf(cam.camera("1", "Garage")), owls = listOf(cam.camera("2", "Bedroom", type = "owl")), doorbells = listOf(cam.camera("3", "Front door")),
        )))
        assertEquals(listOf("Bedroom", "Front door", "Garage"), list.map { it.name })
        assertEquals(listOf(BlinkKind.Mini, BlinkKind.Doorbell, BlinkKind.Camera), list.map { it.kind })
        assertEquals("owl", list.first().product)
    }

    @Test fun `a camera is offline when its sync module is or its own status says so, and the battery is only ok or low`() {
        val body = FakeBlink.homescreenJson(cameras = listOf(cam.camera("1", "A", battery = "low"), cam.camera("2", "B", status = "offline")))
        val list = BlinkParse.homescreen(parse(body))
        assertEquals(true, list[0].online); assertEquals(true, list[0].lowBattery)
        assertEquals(false, list[1].online); assertEquals(false, list[1].lowBattery)
        assertTrue(BlinkParse.homescreen(parse(FakeBlink.homescreenJson(cameras = listOf(cam.camera("1", "A")), syncOffline = true))).all { it.online == false })
    }

    @Test fun `odd homescreens give no cameras and never throw`() {
        assertTrue(BlinkParse.homescreen(parse("[]")).isEmpty())
        assertTrue(BlinkParse.homescreen(parse("""{"cameras":"x","owls":[1,{"id":5}],"doorbells":[{"network_id":2}]}""")).isEmpty())
    }

    @Test fun `a snapshot address comes three ways`() {
        fun camera(thumb: String?) = BlinkCamera("7", "5", "X", BlinkKind.Camera, "catalina", thumb, null, null)
        assertEquals("/api/v3/media/accounts/9/networks/5/catalina/7/thumbnail/thumbnail.jpg?ts=1700000000&ext=", BlinkParse.thumbnailPath("9", camera("1700000000")))
        assertEquals("/media/a?x=1&ext=", BlinkParse.thumbnailPath("9", camera("/media/a?x=1&ext=")))
        assertEquals("/media/thumb/7.jpg", BlinkParse.thumbnailPath("9", camera("/media/thumb/7")))
        assertNull(BlinkParse.thumbnailPath("9", camera(null)))
    }

    @Test fun `each kind of camera has its own place to ask for a snapshot`() {
        fun c(k: BlinkKind) = BlinkCamera("7", "5", "X", k, null, null, null, null)
        assertEquals("/network/5/camera/7/thumbnail", BlinkParse.snapshotPath("9", c(BlinkKind.Camera)))
        assertEquals("/api/v1/accounts/9/networks/5/owls/7/thumbnail", BlinkParse.snapshotPath("9", c(BlinkKind.Mini)))
        assertEquals("/api/v1/accounts/9/networks/5/doorbells/7/thumbnail", BlinkParse.snapshotPath("9", c(BlinkKind.Doorbell)))
    }

    @Test fun `media is newest first, and deleted clips and snapshots are skipped`() {
        val body = """{"media":[${cam.media("1", "1", "Backyard", "2026-11-14T10:00:00+00:00")},${cam.media("2", "1", "Backyard", "2026-11-15T09:30:00+0000", source = "doorbell")},""" +
            """${cam.media("3", "1", "Backyard", "2026-11-15T10:00:00+00:00", deleted = true)},${cam.media("4", "1", "Backyard", "2026-11-15T11:00:00+00:00", source = "snapshot")},""" +
            """{"id":5,"media":"/x"},{"id":6,"created_at":"not a time","media":"/y"}]}"""
        val list = BlinkParse.media(parse(body))
        assertEquals(listOf("2", "1"), list.map { it.id })
        assertEquals(listOf(EventKind.Ring, EventKind.Motion), list.map { it.kind })
        assertEquals("/api/v2/accounts/4242/media/clip/2.mp4", list.first().mediaPath)
        assertTrue(BlinkParse.media(parse("""{"media":"x"}""")).isEmpty())
    }

    @Test fun `times read both of Blink's spellings, and the since time is UTC without milliseconds`() {
        assertEquals(BlinkParse.time("2026-11-15T09:30:00+00:00"), BlinkParse.time("2026-11-15T09:30:00+0000"))
        assertEquals(BlinkParse.time("2026-11-15T09:30:00+00:00"), BlinkParse.time("2026-11-15T04:30:00-05:00"))
        assertNull(BlinkParse.time("yesterday")); assertNull(BlinkParse.time(null))
        assertEquals("2026-11-15T09:30:00+0000", BlinkParse.since(BlinkParse.time("2026-11-15T09:30:00.789+00:00")!!))
    }

    @Test fun `the code is read from the address the sign-in ends on`() {
        assertEquals("abc", BlinkParse.codeFrom("immedia-blink://applinks.blink.com/signin/callback?code=abc&state=x"))
        assertEquals("abc", BlinkParse.codeFrom("/end?state=x&code=abc"))
        assertNull(BlinkParse.codeFrom("immedia-blink://applinks.blink.com/signin/callback?state=x"))
        assertNull(BlinkParse.codeFrom("immedia-blink://x?code=")); assertNull(BlinkParse.codeFrom(null))
    }

    @Test fun `the CSRF token is read from the sign-in page's script, and a changed page gives none`() {
        assertEquals("tok", BlinkParse.csrfToken("""<html><script id="oauth-args" type="application/json">{"csrf-token":"tok"}</script></html>"""))
        assertEquals("tok", BlinkParse.csrfToken("""<SCRIPT type="application/json" id='oauth-args'>
            {"a":1,"csrf-token":"tok"}</SCRIPT>"""))
        assertNull(BlinkParse.csrfToken("<html>nothing</html>"))
        assertNull(BlinkParse.csrfToken("""<script id="oauth-args" type="application/json">not json</script>"""))
        assertFalse(BlinkParse.csrfToken("""<script id="oauth-args" type="application/json">{"csrf-token":""}</script>""") != null)
    }
}
