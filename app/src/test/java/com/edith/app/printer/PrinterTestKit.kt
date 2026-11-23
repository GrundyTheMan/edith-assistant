package com.edith.app.printer

import com.edith.app.core.net.HttpClients
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import java.util.Collections

/** Klipper's objects as Moonraker's `/printer/objects/query` answers them, for the parts C34 reads. */
fun printerStatus(
    state: String = "printing",
    filename: String? = "Benchy.gcode",
    fileProgress: Double = 0.42,
    /** The slicer's own progress (M73); when it differs from [fileProgress] it is what is shown. */
    displayProgress: Double = fileProgress,
    printDuration: Double = 1800.0,
    nozzle: Pair<Double, Double>? = 215.0 to 220.0,
    bed: Pair<Double, Double>? = 60.0 to 60.0,
    klippy: String = "ready",
    klippyMessage: String? = null,
    layers: Pair<Int, Int>? = null,
    message: String? = null,
): JsonObject = buildJsonObject {
    put("webhooks", buildJsonObject { put("state", klippy); klippyMessage?.let { put("state_message", it) } })
    put("print_stats", buildJsonObject {
        put("state", state)
        if (filename != null) put("filename", filename) else put("filename", "")
        put("print_duration", printDuration)
        message?.let { put("message", it) }
        layers?.let { (current, total) -> put("info", buildJsonObject { put("current_layer", current); put("total_layer", total) }) }
    })
    put("virtual_sdcard", buildJsonObject { put("progress", fileProgress) })
    put("display_status", buildJsonObject { put("progress", displayProgress) })
    nozzle?.let { (t, target) -> put("extruder", buildJsonObject { put("temperature", t); put("target", target) }) }
    bed?.let { (t, target) -> put("heater_bed", buildJsonObject { put("temperature", t); put("target", target) }) }
}

/** `{"result": …}`, as Moonraker wraps every answer. */
fun moonraker(result: JsonObject): String = buildJsonObject { put("result", result) }.toString()

/**
 * A pretend Moonraker on one server (the API and the web server share it, as with any address that has a port). It answers
 * the calls Edith makes from [status], [estimatedTime] and [webcams], records what it is asked to do, and can demand an API key.
 */
class FakeMoonraker {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    @Volatile var status: JsonObject = printerStatus()
    @Volatile var estimatedTime: Double? = 3600.0
    /** Webcams as Moonraker lists them: name to snapshot address (relative or absolute). Empty: none. */
    @Volatile var webcams: List<Pair<String, String>> = listOf("Chamber" to "/webcam/?action=snapshot")
    @Volatile var requireKey: String? = null
    @Volatile var picture: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
    /** Runs when a print command arrives ("pause", "resume", "cancel"), to change [status] the way the printer would. */
    @Volatile var onPrint: (String) -> Unit = {}
    /** path → answer, for anything the tests want to override. */
    val overrides: MutableMap<String, (RecordedRequest) -> MockResponse> = Collections.synchronizedMap(mutableMapOf())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                overrides[path]?.let { return it(request) }
                val key = requireKey
                if (key != null && request.headers["X-Api-Key"] != key) return json("""{"error":{"code":401,"message":"Unauthorized"}}""", 401)
                return when {
                    path == "/printer/info" -> json(moonraker(buildJsonObject { put("state", "ready"); put("hostname", "ad5x") }))
                    path == "/printer/objects/query" -> json(moonraker(buildJsonObject { put("status", status) }))
                    path == "/server/files/metadata" -> estimatedTime?.let { json(moonraker(buildJsonObject { put("estimated_time", it) })) }
                        ?: json("""{"error":{"code":404,"message":"Metadata not available"}}""", 404)
                    path == "/server/webcams/list" -> json(moonraker(buildJsonObject {
                        put("webcams", buildJsonArray { webcams.forEach { (name, url) -> add(buildJsonObject { put("name", name); put("snapshot_url", url) }) } })
                    }))
                    path.startsWith("/printer/print/") && request.method == "POST" -> {
                        onPrint(path.substringAfterLast('/'))
                        json("""{"result":"ok"}""")
                    }
                    path == "/webcam/" -> MockResponse.Builder().code(200).body(Buffer().write(picture)).addHeader("Content-Type", "image/jpeg").build()
                    else -> json("""{"error":{"code":404,"message":"Not found"}}""", 404)
                }
            }
        }
        server.start()
    }

    private fun json(text: String, code: Int = 200) = MockResponse.Builder().code(code).body(text).addHeader("Content-Type", "application/json").build()

    /** What the owner would type: this server's address, with a port other than Moonraker's, so it serves the API and the web both. */
    val address: String get() = server.url("/").toString().trimEnd('/')

    fun api() = MoonrakerApi(lazy { HttpClients.over(OkHttpClient()) })
    fun conn(key: String? = null) = PrinterConn(PrinterAddress.parse(address).getOrThrow(), key)
    fun count(path: String) = requests.count { it.url.encodedPath == path }
    fun posts(): List<String> = requests.filter { it.method == "POST" }.map { it.url.encodedPath }
    fun shutdown() = server.close()
}

