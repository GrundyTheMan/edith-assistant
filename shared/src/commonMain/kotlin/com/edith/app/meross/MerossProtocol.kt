@file:OptIn(ExperimentalEncodingApi::class)

package com.edith.app.meross

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.edith.app.core.util.Md5
import kotlin.random.Random
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Meross's cloud protocol, as the maintained `meross-iot` Python library (albertogeniola/MerossIot, 0.4.x) does it;
 * checked against its source on 2026-11-14. The functions here are pure: signing, topics, message building.
 *
 * [SECRET] is the salt Meross's own app signs sign-in requests with. It is published in that open-source library and
 * is the same for everyone: it isn't a credential of the owner's.
 */
object MerossProtocol {
    const val SECRET = "23x17ahWarFH6w29"

    private val hex = "0123456789abcdef".toCharArray()

    fun md5(text: String): String {
        val bytes = Md5.digest(text.encodeToByteArray())
        return buildString(32) { for (b in bytes) { append(hex[b.toInt() shr 4 and 15]); append(hex[b.toInt() and 15]) } }
    }

    /** 16 random characters from A–Z and 0–9: the sign-in's nonce. */
    fun nonce(random: Random = Random.Default): String =
        buildString(16) { repeat(16) { append(NONCE_CHARS[random.nextInt(NONCE_CHARS.length)]) } }

    /** The sign-in's `params`: the request's data as JSON, then base64 (no line breaks). */
    fun encodeParams(params: JsonObject): String = Base64.encode(params.toString().encodeToByteArray())

    /** `md5(SECRET + timestamp-in-ms + nonce + encoded params)`, in lower-case hex. */
    fun requestSign(timestampMs: Long, nonce: String, encodedParams: String): String = md5("$SECRET$timestampMs$nonce$encodedParams")

    /** The body of every HTTP call: `{params, sign, timestamp, nonce}`. */
    fun envelope(params: JsonObject, timestampMs: Long, nonce: String): JsonObject {
        val encoded = encodeParams(params)
        return buildJsonObject {
            put("params", encoded)
            put("sign", requestSign(timestampMs, nonce, encoded))
            put("timestamp", timestampMs)
            put("nonce", nonce)
        }
    }

    /** The MQTT password: `md5(userId + key)`. The user name is the user id. */
    fun mqttPassword(userId: String, key: String): String = md5("$userId$key")

    /** The app id this connection says it is: `md5("API" + a random UUID)`. The MQTT client id is `app:<appId>`. */
    fun appId(uuid: String): String = md5("API$uuid")

    /** Where a device listens for commands. */
    fun requestTopic(deviceUuid: String) = "/appliance/$deviceUuid/subscribe"

    /** Where devices answer commands sent by this app. */
    fun responseTopic(userId: String, appId: String) = "/app/$userId-$appId/subscribe"

    /** A message to a device and its id: `{header: {…}, payload}`, with `sign = md5(messageId + key + timestamp)`. */
    fun message(
        method: String, namespace: String, payload: JsonObject, deviceUuid: String, key: String, replyTopic: String,
        nowSeconds: Long, random: Random = Random.Default,
    ): Pair<String, String> {
        val messageId = md5(buildString(16) { repeat(16) { append(NONCE_CHARS[random.nextInt(NONCE_CHARS.length)]) } })
        val header = buildJsonObject {
            put("from", replyTopic)
            put("messageId", messageId)
            put("method", method)
            put("namespace", namespace)
            put("payloadVersion", 1)
            put("sign", md5("$messageId$key$nowSeconds"))
            put("timestamp", nowSeconds)
            put("triggerSrc", "Android")
            put("uuid", deviceUuid)
        }
        return buildJsonObject { put("header", header); put("payload", payload) }.toString() to messageId
    }

    /** True when [header]'s `sign` is `md5(messageId + key + timestamp)`: only messages that pass are believed. */
    fun verify(header: JsonObject, key: String): Boolean {
        val id = header["messageId"]?.jsonPrimitive?.contentOrNull ?: return false
        val timestamp = header["timestamp"]?.jsonPrimitive?.contentOrNull ?: return false
        val sign = header["sign"]?.jsonPrimitive?.contentOrNull ?: return false
        return md5("$id$key$timestamp").equals(sign, ignoreCase = true)
    }

    /** `host` and `port` from a Meross domain such as `mqtt-us.meross.com` or `mqtt-us.meross.com:443`. */
    fun hostAndPort(domain: String, defaultPort: Int = MQTT_PORT): Pair<String, Int> {
        val bare = domain.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val port = bare.substringAfter(':', "").toIntOrNull()
        return bare.substringBefore(':') to (port ?: defaultPort)
    }

    /**
     * Whether [domain], as Meross's sign-in names it, is a Meross address Edith may send an account's credentials to:
     * a host under `meross.com` (an optional `https://` and `:port`), never plain `http://` and never another site.
     * Cleartext is allowed app-wide for Ollama on the LAN (01-architecture §9), so this is checked here.
     */
    fun isTrustedDomain(domain: String): Boolean {
        val bare = domain.removePrefix("https://").trimEnd('/')
        return TRUSTED_DOMAIN.matches(bare)
    }

    private val TRUSTED_DOMAIN = Regex("""(?:[A-Za-z0-9-]+\.)+meross\.com(?::\d{1,5})?""", RegexOption.IGNORE_CASE)

    /** An API address with a scheme: a redirect gives a bare host name. */
    fun apiBase(domain: String): String = if (domain.startsWith("http://") || domain.startsWith("https://")) domain.trimEnd('/') else "https://${domain.trimEnd('/')}"

    const val MQTT_PORT = 443
    const val GARAGE_STATE = "Appliance.GarageDoor.State"
    const val SYSTEM_ALL = "Appliance.System.All"

    private const val NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** The `apiStatus` codes Edith acts on (meross-iot's `ErrorCodes`). */
    object Status {
        const val OK = 0
        const val UNKNOWN_ACCOUNT = 1002
        const val WRONG_CREDENTIALS = 1004
        const val TOKEN_INVALID = 1019
        const val TOKEN_ERROR = 1022
        const val REDIRECT_REGION = 1030
        const val WRONG_MFA = 1032
        const val MFA_REQUIRED = 1033
        const val TOKEN_EXPIRED = 1200
        const val TOO_MANY_TOKENS = 1301
        val TOKEN_REJECTED = setOf(TOKEN_INVALID, TOKEN_ERROR, TOKEN_EXPIRED)
    }
}

/** What a Meross call came back with. A refused token is its own case: it means "sign in again", not "try again". */
sealed interface MerossReply<out T> {
    data class Ok<T>(val value: T) : MerossReply<T>
    /** The saved sign-in isn't accepted any more (token expired or revoked, or the MQTT broker refused it). */
    data object Rejected : MerossReply<Nothing>
    /** Anything else: [why] is plain words for the model and the owner. */
    data class Failed(val why: String) : MerossReply<Nothing>
}

/** What signing in gives, kept encrypted on the phone. The password is never part of it. */
data class MerossCreds(
    val token: String,
    val key: String,
    val userId: String,
    val email: String,
    /** The API address of the owner's region. */
    val domain: String,
    val mqttDomain: String,
    /** Sent as the sign-in's `mobileInfo.uuid`, made once per sign-in. */
    val installId: String,
)

/** A device in the account's list (`devList`). */
data class MerossDevice(val uuid: String, val name: String, val type: String, val online: Boolean) {
    /** Garage door openers are `msg100`, `msg200`, … */
    val isGarageOpener: Boolean get() = type.startsWith("msg", ignoreCase = true)
}

fun JsonElement?.obj(): JsonObject? = (this as? JsonObject)
fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

