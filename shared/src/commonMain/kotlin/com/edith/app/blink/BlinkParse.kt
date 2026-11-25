package com.edith.app.blink

import com.edith.app.home.EventKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import io.ktor.http.Url
import kotlinx.datetime.LocalDate
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.char
import kotlinx.datetime.parse
import kotlin.time.Instant

/** Blink's three kinds of camera; each has its own addresses for a snapshot and a live view. */
enum class BlinkKind { Camera, Mini, Doorbell }

/** One camera on the account, as Blink's homescreen lists it (Jarvis's `parseHomescreen`). */
data class BlinkCamera(
    val id: String,
    val networkId: String,
    val name: String,
    val kind: BlinkKind,
    /** Blink's product name ("catalina" is the Outdoor, "owl" the Mini): part of some picture addresses. */
    val product: String?,
    /** Where the latest snapshot is, as Blink gives it. */
    val thumbnail: String?,
    /** Blink says only "ok" or "low". */
    val lowBattery: Boolean?,
    val online: Boolean?,
    /** The camera's serial number: a live view needs it (C32). */
    val serial: String? = null,
)

/** One recorded clip from Blink's media list. [mediaPath] and [thumbnailPath] are paths on the account's server. */
data class BlinkMedia(val id: String, val cameraId: String, val cameraName: String, val at: Long, val kind: EventKind, val mediaPath: String, val thumbnailPath: String?)

/** Reads Blink's answers. Pure and forgiving: odd input gives empty results, never an exception. */
object BlinkParse {
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it.obj() }

    /**
     * The cameras in the homescreen answer: `cameras` (Outdoor, Indoor…), `owls` (Mini) and `doorbells`. A camera is
     * offline when its sync module is, or when its own status says so. Newest firmware answers differ by account, so
     * every field is optional except the ids.
     */
    fun homescreen(body: JsonElement): List<BlinkCamera> {
        val h = body.obj() ?: return emptyList()
        val offlineNetworks = h.list("sync_modules").filter { it.text("status") == "offline" }.mapNotNull { it.text("network_id") }.toSet()
        val out = mutableListOf<BlinkCamera>()
        for ((key, kind) in listOf("cameras" to BlinkKind.Camera, "owls" to BlinkKind.Mini, "doorbells" to BlinkKind.Doorbell)) {
            for (c in h.list(key)) {
                val id = c.text("id") ?: continue
                val network = c.text("network_id") ?: continue
                val status = c.text("status")
                val battery = c.text("battery")
                out += BlinkCamera(
                    id = id, networkId = network, name = c.text("name") ?: "Blink camera $id", kind = kind, product = c.text("type"),
                    thumbnail = c.text("thumbnail"),
                    lowBattery = battery?.let { it == "low" },
                    online = if (network in offlineNetworks || status == "offline") false else if (status != null) true else null,
                    serial = c.text("serial"),
                )
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * The address of a camera's latest snapshot. Blink gives it three ways: a full path (".jpg" goes on the end), a path
     * that already ends in `&ext=`, or only the time it was taken, from which the path is built.
     */
    fun thumbnailPath(accountId: String, camera: BlinkCamera): String? {
        val t = camera.thumbnail ?: return null
        return when {
            t.all { it.isDigit() } ->
                "/api/v3/media/accounts/$accountId/networks/${camera.networkId}/${camera.product ?: "catalina"}/${camera.id}/thumbnail/thumbnail.jpg?ts=$t&ext="
            t.endsWith("&ext=") -> t
            else -> "$t.jpg"
        }
    }

    /** Where to ask a camera for a new snapshot. */
    fun snapshotPath(accountId: String, camera: BlinkCamera): String = when (camera.kind) {
        BlinkKind.Mini -> "/api/v1/accounts/$accountId/networks/${camera.networkId}/owls/${camera.id}/thumbnail"
        BlinkKind.Doorbell -> "/api/v1/accounts/$accountId/networks/${camera.networkId}/doorbells/${camera.id}/thumbnail"
        BlinkKind.Camera -> "/network/${camera.networkId}/camera/${camera.id}/thumbnail"
    }

    /** Where to ask a camera for a live view (Jarvis's `liveviewPath`). */
    fun liveviewPath(accountId: String, camera: BlinkCamera): String {
        val base = "/accounts/$accountId/networks/${camera.networkId}"
        return when (camera.kind) {
            BlinkKind.Mini -> "/api/v2$base/owls/${camera.id}/liveview"
            BlinkKind.Doorbell -> "/api/v2$base/doorbells/${camera.id}/liveview"
            BlinkKind.Camera -> "/api/v6$base/cameras/${camera.id}/liveview"
        }
    }

    /** What Blink answered to a live view request: the stream's address and the command that keeps it open. */
    data class LiveAnswer(val server: String?, val commandId: String?, val networkId: String?, val pollSeconds: Int)

    fun liveAnswer(body: JsonElement): LiveAnswer {
        val o = body.obj()
        return LiveAnswer(
            server = o?.text("server"), commandId = o?.text("command_id"), networkId = o?.text("network_id"),
            pollSeconds = (o?.text("polling_interval")?.toIntOrNull() ?: 5).coerceAtLeast(2),
        )
    }

    /** Whether the live view's command is still running (Jarvis's `commandRunning`): Blink ends a view by ending its command. */
    fun commandRunning(body: JsonElement): Boolean {
        val o = body.obj() ?: return true
        val code = (o["status_code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        if (code != null && code != 908) return false
        val first = (o["commands"] as? JsonArray)?.firstOrNull()?.obj()
        val state = (o["state_condition"] as? JsonPrimitive)?.contentOrNull ?: (first?.get("state_condition") as? JsonPrimitive)?.contentOrNull
        return state == null || state == "new" || state == "running"
    }

    /**
     * Null while the live view's command is running; otherwise what Blink said when it stopped, as codes only
     * (Jarvis's `commandSummary`): "status_code 901, state_condition done".
     */
    fun commandStopped(body: JsonElement): String? {
        if (commandRunning(body)) return null
        val o = body.obj()
        val first = (o?.get("commands") as? JsonArray)?.firstOrNull()?.obj()
        fun pick(k: String): String? = (o?.get(k) as? JsonPrimitive)?.contentOrNull ?: (first?.get(k) as? JsonPrimitive)?.contentOrNull
        return listOf("status_code", "state_condition", "state_stage", "status_msg").mapNotNull { k -> pick(k)?.let { "$k ${it.take(80)}" } }
            .joinToString(", ").ifEmpty { "no status given" }
    }

    /** The command a snapshot request started: poll [BlinkApi.commandDone] with these. Null when Blink gave none. */
    fun command(body: JsonElement): Pair<String, String?>? {
        val o = body.obj() ?: return null
        val id = o.text("id") ?: return null
        return id to o.text("network_id")
    }

    /** Blink's media list as clips, newest first; deleted ones and snapshots are skipped (Jarvis's `parseBlinkMedia`). */
    fun media(body: JsonElement): List<BlinkMedia> {
        val list = body.obj()?.list("media") ?: return emptyList()
        return list.mapNotNull { m ->
            val id = m.text("id") ?: return@mapNotNull null
            val path = m.text("media") ?: return@mapNotNull null
            val at = time(m.text("created_at")) ?: return@mapNotNull null
            if ((m["deleted"] as? JsonPrimitive)?.booleanOrNull == true || m.text("source") == "snapshot") return@mapNotNull null
            BlinkMedia(id, m.text("device_id").orEmpty(), m.text("device_name") ?: "Blink camera", at, kind(m.text("source")), path, m.text("thumbnail"))
        }.sortedByDescending { it.at }
    }

    private fun kind(source: String?): EventKind = when (source) {
        "pir", "motion" -> EventKind.Motion
        "doorbell", "ding" -> EventKind.Ring
        "liveview", "on_demand" -> EventKind.LiveView
        else -> EventKind.Motion   // Blink records on motion; an unnamed source is that
    }

    /** "2026-10-29T08:34:19+00:00" or "…+0000", as ms since 1970; null when it isn't a time. */
    fun time(text: String?): Long? {
        if (text == null) return null
        return runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()
            ?: runCatching { Instant.parse(text, BASIC_OFFSET).toEpochMilliseconds() }.getOrNull()
    }

    /** The time as Blink's media list wants it: "2026-10-29T08:34:19+0000", in UTC, without milliseconds. */
    fun since(ms: Long): String = Instant.fromEpochSeconds(ms.floorDiv(1000L)).format(BASIC_OFFSET, UtcOffset.ZERO)

    /** `2026-10-29T08:34:19+0000`: seconds and an offset without a colon, the way Blink wants `since` and sometimes writes times. */
    private val BASIC_OFFSET = DateTimeComponents.Format {
        date(LocalDate.Formats.ISO); char('T'); hour(); char(':'); minute(); char(':'); second(); offset(UtcOffset.Formats.FOUR_DIGITS)
    }

    /** The `code` in the address Blink's sign-in ends on; null when there is none. */
    fun codeFrom(location: String?): String? {
        if (location == null) return null
        val query = runCatching { Url(location).encodedQuery }.getOrNull()?.takeIf { it.isNotEmpty() } ?: location.substringAfter('?', "")
        return query.split('&').firstNotNullOfOrNull { p -> p.substringBefore('=').takeIf { it == "code" }?.let { p.substringAfter('=', "") } }?.takeIf { it.isNotEmpty() }
    }

    /** The CSRF token in the sign-in page: the JSON in `<script id="oauth-args" type="application/json">` has a `csrf-token`. */
    fun csrfToken(html: String): String? {
        val script = Regex("""<script[^>]*id=["']oauth-args["'][^>]*>(.*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(1) ?: return null
        return runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(script).obj()?.get("csrf-token") as? JsonPrimitive)?.contentOrNull }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
