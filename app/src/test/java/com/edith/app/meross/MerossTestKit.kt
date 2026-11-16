package com.edith.app.meross

import com.edith.app.testing.FakeClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.Base64
import java.util.Collections

/** A pretend Meross cloud: checks every request's `sign` the way Meross does, and answers from [answers] by path. */
class FakeMerossCloud {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    /** The decoded `params` of each request, by path. */
    val sent = Collections.synchronizedMap(mutableMapOf<String, JsonObject>())
    val badSigns = Collections.synchronizedList(mutableListOf<String>())
    /** path → the JSON text to answer; a path not here gets HTTP 404. */
    val answers = Collections.synchronizedMap(mutableMapOf<String, () -> String>())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val body = Json.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject
                val params = body["params"]!!.toString().trim('"')
                val expected = MerossProtocol.md5("${MerossProtocol.SECRET}${body["timestamp"]}${body["nonce"]!!.toString().trim('"')}$params")
                if (expected != body["sign"]!!.toString().trim('"')) badSigns += request.url.encodedPath
                sent[request.url.encodedPath] = Json.parseToJsonElement(String(Base64.getDecoder().decode(params))).jsonObject
                val answer = answers[request.url.encodedPath] ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().code(200).body(answer()).addHeader("Content-Type", "application/json").build()
            }
        }
    }

    init { server.start() }

    val base: String get() = server.url("/").toString().trimEnd('/')
    fun authorization(path: String): String? = requests.firstOrNull { it.url.encodedPath == path }?.headers?.get("Authorization")

    fun loginOk(domain: String = base, mqtt: String = "mqtt.example.test", token: String = "tok-1") = """{"apiStatus":0,"data":{"token":"$token","key":"key-1","userid":"4242","email":"owner@example.com","domain":"$domain","mqttDomain":"$mqtt"}}"""
    fun status(code: Int, data: String = "{}") = """{"apiStatus":$code,"data":$data}"""
    fun devList(vararg devices: String) = """{"apiStatus":0,"data":[${devices.joinToString(",")}]}"""
    fun device(uuid: String, name: String, type: String = "msg100", online: Int = 1) = """{"uuid":"$uuid","devName":"$name","deviceType":"$type","onlineStatus":$online}"""

    /** Trusts every address: this pretend Meross is a local http server. [MerossApiTest] covers what the real rule refuses. */
    fun api(clock: FakeClock = FakeClock()) = MerossApi(lazy { OkHttpClient() }, clock, base, trusted = { true })
    /** The same, with the real rule for which addresses Meross may name. */
    fun strictApi(clock: FakeClock = FakeClock()) = MerossApi(lazy { OkHttpClient() }, clock, base)
    fun shutdown() = server.close()
}

/** A bus that answers from a table instead of an MQTT broker. [seen] is every request: (device, method, namespace, payload). */
class FakeMerossBus : MerossBus {
    data class Seen(val device: String, val method: String, val namespace: String, val payload: JsonObject)

    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
    /** `(device, method, namespace)` → the answer; no entry: the device doesn't answer. */
    val replies = mutableMapOf<Triple<String, String, String>, MerossReply<JsonObject>>()
    var connect: MerossReply<Unit> = MerossReply.Ok(Unit)
    /** Requests that fail this many times before they answer, by `(device, method, namespace)`. */
    val failFirst = mutableMapOf<Triple<String, String, String>, Int>()

    override suspend fun <T> withSession(creds: MerossCreds, block: suspend (MerossSession) -> T): MerossReply<T> {
        when (val c = connect) {
            MerossReply.Rejected -> return MerossReply.Rejected
            is MerossReply.Failed -> return c
            is MerossReply.Ok -> Unit
        }
        return MerossReply.Ok(block(object : MerossSession {
            override suspend fun request(deviceUuid: String, method: String, namespace: String, payload: JsonObject): MerossReply<JsonObject> {
                seen += Seen(deviceUuid, method, namespace, payload)
                val key = Triple(deviceUuid, method, namespace)
                failFirst[key]?.takeIf { it > 0 }?.let { failFirst[key] = it - 1; return MerossReply.Failed("the device didn't answer") }
                return replies[Triple(deviceUuid, method, namespace)] ?: MerossReply.Failed("the device didn't answer")
            }
        }))
    }
}

/** What `GET Appliance.System.All` answers for a garage opener with these doors: (channel, open, lmTime). */
fun systemAll(vararg doors: Triple<Int, Int, Long>): JsonObject = Json.parseToJsonElement(
    """{"all":{"system":{},"digest":{"garageDoor":[${doors.joinToString(",") { """{"channel":${it.first},"open":${it.second},"lmTime":${it.third}}""" }}]}}}""",
).jsonObject
