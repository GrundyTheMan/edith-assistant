package com.edith.app.alexa

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections

/** The address a finished sign-in ends on, as Amazon's page redirects to it. */
fun maplanding(code: String = "AUTHCODE-1", site: String = "www.amazon.com") =
    "https://$site/ap/maplanding?openid.assoc_handle=amzn_dp_project_dee_ios&openid.oa2.authorization_code=$code&openid.mode=id_res"

/**
 * A pretend Amazon: one server for `api.`, `www.` and `alexa.` (the three are paths on it). It registers "devices"
 * for the authorization code it expects (checking the PKCE verifier against the challenge the sign-in page was given),
 * trades refresh tokens for cookies, hands out a csrf token, and answers the smart-home calls from [endpointItems] and
 * [states].
 */
class FakeAmazon {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    val registerBodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())

    /** The code and PKCE challenge the pretend sign-in page was opened with (set by the test from [AlexaSignInStart.url]). */
    @Volatile var expectedCode = "AUTHCODE-1"
    @Volatile var challenge: String? = null
    @Volatile var refreshToken = "refresh-1"
    /** Refresh tokens Amazon still accepts for the cookie exchange. */
    val validRefresh: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    /** The cookie value Amazon would currently accept; changing it makes the old one a 401. */
    @Volatile var cookieGeneration = 1
    private val validGenerations = Collections.synchronizedSet(mutableSetOf(1))
    @Volatile var csrfOnLanguagePage = true

    @Volatile var endpointItems: JsonArray = JsonArray(emptyList())
    /** Answers for the state calls, by the appliance id a request names; unnamed ones are left out of the answer. */
    @Volatile var states: Map<String, List<JsonObject>> = emptyMap()
    @Volatile var stateErrors: Map<String, String> = emptyMap()
    @Volatile var stateDelayMs = 0L
    /** path → answer, for anything the tests want to override. */
    val overrides = Collections.synchronizedMap(mutableMapOf<String, (RecordedRequest) -> MockResponse>())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                overrides[path]?.let { return it(request) }
                return when (path) {
                    "/auth/register" -> register(request)
                    "/ap/exchangetoken/cookies" -> exchange(request)
                    "/api/language", "/spa/index.html" -> language(request)
                    "/nexus/v1/graphql" -> signedIn(request) { json(buildJsonObject { put("data", buildJsonObject { put("endpoints", buildJsonObject { put("items", endpointItems) }) }) }.toString()) }
                    "/api/phoenix/state" -> signedIn(request) { stateAnswer(request) }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    private fun json(text: String, code: Int = 200) = MockResponse.Builder().code(code).body(text).addHeader("Content-Type", "application/json").build()

    private fun register(r: RecordedRequest): MockResponse {
        val body = Json.parseToJsonElement(r.body?.utf8().orEmpty()).jsonObject
        registerBodies += body
        val auth = body["auth_data"]?.jsonObject
        val verifier = auth?.get("code_verifier")?.jsonPrimitive?.content
        val ok = auth?.get("authorization_code")?.jsonPrimitive?.content == expectedCode &&
            verifier != null && challenge == Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        if (!ok) return json("""{"response":{"error":{"code":"InvalidAuthorizationCode"}}}""", 400)
        validRefresh += refreshToken
        return json("""{"response":{"success":{"tokens":{"bearer":{"refresh_token":"$refreshToken","access_token":"access-1"},"website_cookies":[]}}}}""")
    }

    private fun exchange(r: RecordedRequest): MockResponse {
        val form = r.body?.utf8().orEmpty().split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        if (form["source_token"] !in validRefresh) return json("""{"response":{"error":{"code":"invalid_token"}}}""", 400)
        val gen = cookieGeneration
        validGenerations += gen
        return json("""{"response":{"tokens":{"cookies":{".amazon.com":[{"Name":"at-main","Value":"AT$gen"},{"Name":"session-id","Value":"S$gen"}]}}}}""")
    }

    private fun language(r: RecordedRequest): MockResponse {
        val b = MockResponse.Builder().code(200).body("{}")
        if (csrfOnLanguagePage) b.addHeader("Set-Cookie", "csrf=CSRF${cookieGeneration}; Path=/; Secure")
        return b.build()
    }

    private fun signedIn(r: RecordedRequest, answer: () -> MockResponse): MockResponse {
        val cookie = r.headers["Cookie"].orEmpty()
        val gen = Regex("at-main=AT(\\d+)").find(cookie)?.groupValues?.get(1)?.toIntOrNull()
        if (gen == null || gen !in validGenerations || gen != cookieGeneration || r.headers["csrf"].isNullOrEmpty()) return MockResponse.Builder().code(401).body("{}").build()
        return answer()
    }

    private fun stateAnswer(r: RecordedRequest): MockResponse {
        if (stateDelayMs > 0) Thread.sleep(stateDelayMs)
        val asked = Json.parseToJsonElement(r.body?.utf8().orEmpty()).jsonObject["stateRequests"]!!.jsonArray.map { it.jsonObject["entityId"]!!.jsonPrimitive.content }
        val deviceStates = buildJsonArray {
            for (id in asked) states[id]?.let { caps ->
                add(buildJsonObject {
                    put("entity", buildJsonObject { put("entityId", id); put("entityType", "APPLIANCE") })
                    put("capabilityStates", JsonArray(caps.map { JsonPrimitive(it.toString()) }))
                })
            }
        }
        val errors = buildJsonArray {
            for (id in asked) stateErrors[id]?.let { code -> add(buildJsonObject { put("code", code); put("entity", buildJsonObject { put("entityId", id) }) }) }
        }
        return json(buildJsonObject { put("deviceStates", deviceStates); put("errors", errors) }.toString())
    }

    val base: String get() = server.url("/").toString().trimEnd('/')
    fun api(): AlexaApi = AlexaApi(lazy { OkHttpClient() }, { AlexaEndpoints(base, base, base) })
    fun count(path: String) = requests.count { it.url.encodedPath == path }
    fun shutdown() = server.close()

    /** Pretend the owner signed in before: this refresh token is good. */
    fun signedInBefore(token: String = "refresh-1") { validRefresh += token }

    /** Amazon forgets the current cookies (they expire after days): the next call gets 401 until new ones are fetched. */
    fun expireCookies() { cookieGeneration++ }

    companion object {
        /** A capability state as Alexa reports it. */
        fun cap(namespace: String, name: String, value: Any) = buildJsonObject {
            put("namespace", namespace); put("name", name)
            when (value) {
                is JsonObject -> put("value", value)
                is Number -> put("value", JsonPrimitive(value))
                else -> put("value", value.toString())
            }
        }

        fun temp(value: Number, scale: String) = buildJsonObject { put("value", JsonPrimitive(value)); put("scale", scale) }

        /** One entry of the device list. */
        fun endpoint(
            entityId: String, name: String, type: String, applianceId: String = "app-$entityId", maker: String = "Acme",
            actions: List<String> = emptyList(), merged: List<String> = emptyList(), modes: List<String> = emptyList(),
            retrievable: List<Pair<String, String>> = emptyList(), description: String = "$name by $maker",
        ): JsonObject = buildJsonObject {
            put("id", entityId); put("friendlyName", name)
            put("displayCategories", buildJsonObject { put("primary", buildJsonObject { put("value", type) }) })
            put("manufacturer", buildJsonObject { put("value", buildJsonObject { put("text", maker) }) })
            put("legacyAppliance", buildJsonObject {
                put("applianceId", applianceId); put("entityId", entityId); put("friendlyName", name); put("friendlyDescription", description)
                put("manufacturerName", maker); put("applianceTypes", buildJsonArray { add(JsonPrimitive(type)) })
                put("actions", JsonArray(actions.map { JsonPrimitive(it) }))
                put("mergedApplianceIds", JsonArray(merged.map { JsonPrimitive(it) }))
                put("capabilities", buildJsonArray {
                    for ((ns, prop) in retrievable) add(buildJsonObject {
                        put("interfaceName", ns)
                        put("properties", buildJsonObject {
                            put("retrievable", true)
                            put("supported", buildJsonArray { add(buildJsonObject { put("name", prop) }) })
                        })
                    })
                    if (modes.isNotEmpty()) add(buildJsonObject {
                        put("interfaceName", "Alexa.ThermostatController")
                        put("configuration", buildJsonObject { put("supportedModes", JsonArray(modes.map { JsonPrimitive(it) })) })
                    })
                })
            })
        }

        fun items(vararg e: JsonObject) = JsonArray(e.toList())
    }
}
