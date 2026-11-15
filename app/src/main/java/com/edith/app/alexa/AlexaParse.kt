package com.edith.app.alexa

import com.edith.app.home.Capability
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceState
import com.edith.app.home.DoorState
import com.edith.app.home.LockState
import com.edith.app.home.TempUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Amazon sites an Alexa account can sign in through, by the country the account is in (Jarvis's `AMAZON_SITES`). */
data class AmazonSite(val site: String, val label: String, val language: String) {
    /** The scale Alexa expects for an account on this site (`.com` → °F, as Jarvis's `temperatureScale`). */
    val scale: TempScale get() = if (site == "amazon.com") TempScale.Fahrenheit else TempScale.Celsius

    companion object {
        val ALL = listOf(
            AmazonSite("amazon.com", "United States (amazon.com)", "en_US"),
            AmazonSite("amazon.ca", "Canada (amazon.ca)", "en_CA"),
            AmazonSite("amazon.co.uk", "United Kingdom (amazon.co.uk)", "en_GB"),
            AmazonSite("amazon.com.au", "Australia (amazon.com.au)", "en_AU"),
            AmazonSite("amazon.de", "Germany (amazon.de)", "de_DE"),
            AmazonSite("amazon.fr", "France (amazon.fr)", "fr_FR"),
            AmazonSite("amazon.it", "Italy (amazon.it)", "it_IT"),
            AmazonSite("amazon.es", "Spain (amazon.es)", "es_ES"),
        )
        val DEFAULT = ALL.first()
        fun of(site: String): AmazonSite? = ALL.firstOrNull { it.site == site }
    }
}

/** The temperature scales Alexa reports in. */
enum class TempScale(val alexa: String) { Fahrenheit("FAHRENHEIT"), Celsius("CELSIUS") }

/** One property a state request asks for, e.g. `Alexa.PowerController` / `powerState`. */
data class StateProperty(val namespace: String, val name: String, val instance: String? = null)

/** One smart-home device on the Alexa account, as the device list gives it (Jarvis's `AlexaDevice`, without the state). */
data class AlexaDevice(
    /** The id Alexa's control requests take. */
    val entityId: String,
    /** The ids its state requests take: its appliance id, and any merged into it. */
    val applianceIds: List<String>,
    /** The state properties Alexa can report for it; none, and it isn't asked. */
    val properties: List<StateProperty>,
    val name: String,
    /** Alexa's category code: `LIGHT`, `SMARTLOCK`, … */
    val type: String,
    /** The control actions Alexa lists for it (`turnOn`, `setBrightness`, …); empty when it lists none. */
    val actions: List<String>,
    /** A thermostat's modes, lower case, when its capabilities list them. */
    val modes: List<String>,
    val manufacturer: String?,
)

/** What one lookup learned about one device. */
data class AlexaReading(val state: DeviceState?, val unreachable: Boolean = false, val noAnswer: Boolean = false)

/**
 * Parsing what Amazon's device and state services answer (ported from Jarvis's `shared/alexa.ts`). Amazon offers no
 * official way for an app to see the devices linked to someone's Alexa, so this can break whenever Amazon changes
 * things: everything here reads defensively and never throws on an answer it doesn't understand.
 */
object AlexaParse {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()
    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /**
     * The properties a device's capabilities say Alexa can report: each capability's supported properties where
     * it's marked retrievable (as the ioBroker Alexa adapter reads them).
     */
    fun stateProperties(capabilities: JsonElement?): List<StateProperty> = buildList {
        for (raw in capabilities.array()) {
            val cap = raw.obj()
            val namespace = cap["interfaceName"].text() ?: continue
            val props = cap["properties"].obj()
            val retrievable = (props["retrievable"] as? JsonPrimitive)?.booleanOrNull == true
            if (!retrievable) continue
            for (p in props["supported"].array()) {
                val name = p.obj()["name"].text() ?: continue
                add(StateProperty(namespace, name, cap["instance"].text()))
            }
        }
    }

    /** The thermostat modes a device's capabilities list (`Alexa.ThermostatController`'s `supportedModes`), lower case. */
    fun thermostatModes(capabilities: JsonElement?): List<String> {
        val out = mutableListOf<String>()
        for (raw in capabilities.array()) {
            val cap = raw.obj()
            if (cap["interfaceName"].text() != "Alexa.ThermostatController") continue
            for (m in cap["configuration"].obj()["supportedModes"].array()) {
                val mode = (m.text() ?: m.obj()["value"].text())?.lowercase() ?: continue
                if (mode !in out) out += mode
            }
        }
        return out
    }

    /**
     * The devices from the endpoints Alexa's device service lists. The Alexa app registers itself as a device and is
     * left out; Echo speakers and Fire TVs (`ALEXA_VOICE_ENABLED`) report no smart-home state and are left out too
     * (Jarvis lists them as "Alexa device"; on a phone they would only clutter the Home tab).
     */
    fun parseEndpoints(items: JsonElement?): List<AlexaDevice> {
        val out = mutableListOf<AlexaDevice>()
        for (raw in items.array()) {
            val item = raw.obj()
            val legacy = item["legacyAppliance"].obj()
            val entityId = legacy["entityId"].text() ?: item["id"].text() ?: continue
            val types = legacy["applianceTypes"].array().mapNotNull { it.text() }
            val primary = item["displayCategories"].obj()["primary"].obj()["value"].text() ?: types.firstOrNull()
            if (Regex("alexa (mobile|app)", RegexOption.IGNORE_CASE).containsMatchIn(legacy["friendlyDescription"].text().orEmpty())) continue
            if (primary == "ALEXA_VOICE_ENABLED") continue
            val merged = legacy["mergedApplianceIds"].array().mapNotNull { it.text() }
            val applianceId = legacy["applianceId"].text()
            out += AlexaDevice(
                entityId = entityId,
                applianceIds = (listOfNotNull(applianceId) + merged).distinct(),
                properties = stateProperties(legacy["capabilities"]),
                name = item["friendlyName"].text() ?: legacy["friendlyName"].text() ?: "Unnamed device",
                type = primary ?: "OTHER",
                actions = legacy["actions"].array().mapNotNull { it.text() },
                modes = thermostatModes(legacy["capabilities"]),
                manufacturer = item["manufacturer"].obj()["value"].obj()["text"].text() ?: legacy["manufacturerName"].text(),
            )
        }
        return out.sortedWith(compareBy({ it.type }, { it.name.lowercase() }))
    }

    /** A temperature Alexa reported (`{value, scale}`), in [want] when given: 18 °C on a °F account reads 64.4. */
    private fun inScale(v: JsonElement?, want: TempScale?): Double? {
        val t = v.obj()
        val value = t["value"].number()?.takeIf { it.isFinite() } ?: return null
        val scale = when (t["scale"].text()) { "CELSIUS" -> TempScale.Celsius; "FAHRENHEIT" -> TempScale.Fahrenheit; else -> null }
        if (want == null || scale == null || scale == want) return value
        return if (want == TempScale.Fahrenheit) value * 9 / 5 + 32 else (value - 32) * 5 / 9
    }

    private fun percent(v: JsonElement?): Int? = (v.number() ?: v.obj()["value"].number())?.toInt()?.coerceIn(0, 100)

    /** Adds one capability state's value to [into]; temperatures in [scale]. A value it doesn't know is ignored. */
    private fun read(s: JsonObject, into: Values, scale: TempScale?) {
        val v = s["value"]
        val text = v.text()
        when ("${s["namespace"].text()}.${s["name"].text()}") {
            "Alexa.PowerController.powerState" -> if (text == "ON" || text == "OFF") into.on = text == "ON"
            "Alexa.BrightnessController.brightness" -> into.brightness = percent(v)
            "Alexa.PercentageController.percentage" -> into.percentage = percent(v)
            "Alexa.LockController.lockState" -> into.lock = when (text) {
                "LOCKED" -> LockState.Locked; "UNLOCKED" -> LockState.Unlocked; "JAMMED" -> LockState.Jammed; else -> null
            }
            "Alexa.ThermostatController.targetSetpoint" -> into.setpoint = v.number() ?: inScale(v, scale)
            "Alexa.TemperatureSensor.temperature" -> into.temperature = v.number() ?: inScale(v, scale)
            "Alexa.ThermostatController.thermostatMode" -> if (text != null) into.mode = text.lowercase()
            // A contact sensor reads as a door: detected = closed.
            "Alexa.ContactSensor.detectionState" -> into.door = when (text) {
                "DETECTED" -> DoorState.Closed; "NOT_DETECTED" -> DoorState.Open; else -> null
            }
        }
    }

    private class Values {
        var on: Boolean? = null; var brightness: Int? = null; var percentage: Int? = null; var lock: LockState? = null
        var setpoint: Double? = null; var temperature: Double? = null; var mode: String? = null; var door: DoorState? = null
        fun toState(): DeviceState? {
            val s = DeviceState(on = on, brightness = brightness, percentage = percentage, temperature = temperature, setpoint = setpoint, mode = mode, lock = lock, door = door)
            return s.takeIf { it != DeviceState() }
        }
    }

    /**
     * Each device's reading from a `phoenix/state` answer: `{deviceStates: [{entity, capabilityStates}], errors: [{entity, code}]}`.
     * `capabilityStates` are JSON strings. Keyed by the id the answer names (an appliance id or the entity id).
     */
    fun parseStates(body: JsonElement?, scale: TempScale? = null): Map<String, AlexaReading> {
        val out = mutableMapOf<String, AlexaReading>()
        val b = body.obj()
        for (raw in b["deviceStates"].array()) {
            val d = raw.obj()
            val id = d["entity"].obj()["entityId"].text() ?: continue
            val values = Values()
            var unreachable = false
            for (c in d["capabilityStates"].array()) {
                val s = (if (c is JsonPrimitive && c.isString) runCatching { json.parseToJsonElement(c.content) }.getOrNull() else c).obj()
                if (s["namespace"].text() == "Alexa.EndpointHealth" && s["value"].obj()["value"].text() == "UNREACHABLE") unreachable = true
                read(s, values, scale)
            }
            out[id] = AlexaReading(values.toState(), unreachable)
        }
        for (raw in b["errors"].array()) {
            val e = raw.obj()
            val id = e["entity"].obj()["entityId"].text() ?: continue
            if (id in out) continue
            // Echo speakers, Fire TVs and the like have no smart-home state to report.
            out[id] = when (e["code"].text()) {
                "TargetApplianceNotFoundException" -> AlexaReading(null)
                "ENDPOINT_UNREACHABLE" -> AlexaReading(null, unreachable = true)
                else -> AlexaReading(null)
            }
        }
        return out
    }

    /** One request per appliance id (state is asked by appliance, not entity), naming the properties wanted. */
    fun stateRequests(devices: List<AlexaDevice>): JsonArray = JsonArray(devices.filter { it.properties.isNotEmpty() }.flatMap { d ->
        d.applianceIds.map { id ->
            JsonObject(mapOf(
                "entityId" to JsonPrimitive(id), "entityType" to JsonPrimitive("APPLIANCE"),
                "properties" to JsonArray(d.properties.map { p ->
                    JsonObject(buildMap {
                        put("namespace", JsonPrimitive(p.namespace)); put("name", JsonPrimitive(p.name))
                        p.instance?.let { put("instance", JsonPrimitive(it)) }
                    })
                }),
            ))
        }
    })

    /** The reason Amazon gives when it refuses a state request as a whole (`{message}`), or null. */
    fun stateRefusal(body: JsonElement?): String? {
        val b = body.obj()
        if (b["deviceStates"].array().isNotEmpty()) return null
        return b["message"].text()?.take(300)
    }

    /** A device's reading: the first of its appliance ids (or its entity id) that reports a state. */
    fun readingOf(device: AlexaDevice, readings: Map<String, AlexaReading>): AlexaReading {
        val found = (device.applianceIds + device.entityId).mapNotNull { readings[it] }
        found.firstOrNull { it.state != null }?.let { return it }
        return if (found.isNotEmpty() && found.all { it.unreachable }) AlexaReading(null, unreachable = true) else AlexaReading(null)
    }

    /**
     * Whether to ask a device for its state. Cameras only report motion, which isn't shown, and asking them is also
     * what times out, blanking the devices batched with them.
     */
    fun shouldAskState(d: AlexaDevice): Boolean = d.type != "ALEXA_VOICE_ENABLED" && d.type != "CAMERA" && d.properties.isNotEmpty()

    // ---- Commands (part 2) ----

    /** The control action Alexa lists for each command (Jarvis's `NEEDS`); a device must support it. */
    private fun needed(command: DeviceCommand): String? = when (command) {
        DeviceCommand.TurnOn -> "turnOn"
        DeviceCommand.TurnOff -> "turnOff"
        is DeviceCommand.SetBrightness -> "setBrightness"
        is DeviceCommand.SetPercentage -> "setPercentage"
        is DeviceCommand.SetTemperature -> "setTargetTemperature"
        is DeviceCommand.SetMode -> "setThermostatMode"
        is DeviceCommand.SetColor -> "setColor"
        is DeviceCommand.SetWhite -> "setColorTemperature"
        DeviceCommand.Lock, DeviceCommand.Unlock -> "lockAction"
        // Jarvis can't open or close a garage door through Alexa, and neither can Edith.
        DeviceCommand.Open, DeviceCommand.Close, DeviceCommand.Pause, DeviceCommand.Resume, DeviceCommand.CancelPrint -> null
    }

    /** What the state properties a device reports say it can be told, for a device whose list of control actions is empty. */
    private val ACTIONS_BY_NAMESPACE = mapOf(
        "Alexa.PowerController" to listOf("turnOn", "turnOff"),
        "Alexa.BrightnessController" to listOf("setBrightness"),
        "Alexa.PercentageController" to listOf("setPercentage"),
        "Alexa.ColorController" to listOf("setColor"),
        "Alexa.ColorTemperatureController" to listOf("setColorTemperature"),
        "Alexa.ThermostatController" to listOf("setTargetTemperature", "setThermostatMode"),
        "Alexa.LockController" to listOf("lockAction"),
    )

    /** Kinds Edith lists but never controls through Alexa: Jarvis can't either (garage doors, cameras, alarm panels). */
    private val NOT_CONTROLLED = setOf("GARAGE_DOOR", "CAMERA", "SECURITY_PANEL", "ALEXA_VOICE_ENABLED")

    /** The control actions [d] supports: the ones Alexa lists, else (when it lists none) what its state properties imply. */
    private fun supportedActions(d: AlexaDevice): Set<String> {
        if (d.type in NOT_CONTROLLED) return emptySet()
        val listed = d.actions.map { if (it == "setLockState") "lockAction" else it }.toSet()
        if (listed.isNotEmpty()) return listed
        return d.properties.flatMap { ACTIONS_BY_NAMESPACE[it.namespace].orEmpty() }.toSet()
    }

    /** The thermostat modes Alexa takes when a device lists none (Jarvis's `THERMOSTAT_MODES`). */
    private val DEFAULT_MODES = listOf("heat", "cool", "auto", "eco", "off")

    /** What [d] can be told to do, as Edith's capabilities; temperatures are in [scale]'s unit. */
    fun capabilitiesOf(d: AlexaDevice, scale: TempScale): Set<Capability> {
        val actions = supportedActions(d)
        return buildSet {
            if ("turnOn" in actions || "turnOff" in actions) add(Capability.OnOff)
            if ("setBrightness" in actions) add(Capability.Brightness)
            if ("setPercentage" in actions) add(Capability.Percentage)
            if ("setColor" in actions) add(Capability.Color())
            if ("setColorTemperature" in actions) add(Capability.WhiteTone())
            if ("setTargetTemperature" in actions || "setThermostatMode" in actions) {
                val modes = if ("setThermostatMode" in actions) d.modes.ifEmpty { DEFAULT_MODES } else emptyList()
                add(Capability.Thermostat(modes, if (scale == TempScale.Fahrenheit) TempUnit.Fahrenheit else TempUnit.Celsius))
            }
            if ("lockAction" in actions) add(Capability.LockUnlock)
        }
    }

    /**
     * The parameters Alexa's control request takes for [command] (as the ioBroker Alexa adapter sends them, and
     * Jarvis's `controlParameters`), or null when [d] can't be sent it. Brightness and speed go as fractions: 50% is 0.5.
     */
    fun controlParameters(command: DeviceCommand, d: AlexaDevice, scale: TempScale): JsonObject? {
        val action = needed(command) ?: return null
        if (action !in supportedActions(d)) return null
        fun name(s: String) = s.trim().lowercase().replace(Regex("""\s+"""), "_")
        return buildJsonObject {
            when (command) {
                DeviceCommand.TurnOn -> put("action", "turnOn")
                DeviceCommand.TurnOff -> put("action", "turnOff")
                is DeviceCommand.SetBrightness -> { put("action", "setBrightness"); put("brightness", command.percent / 100.0) }
                is DeviceCommand.SetPercentage -> { put("action", "setPercentage"); put("percentage", command.percent / 100.0) }
                is DeviceCommand.SetTemperature -> {
                    put("action", "setTargetTemperature"); put("targetTemperature.value", command.value); put("targetTemperature.scale", scale.alexa)
                }
                is DeviceCommand.SetMode -> { put("action", "setThermostatMode"); put("thermostatMode.value", command.mode.trim().uppercase()) }
                is DeviceCommand.SetColor -> { put("action", "setColor"); put("colorName", name(command.name)) }
                is DeviceCommand.SetWhite -> { put("action", "setColorTemperature"); put("colorTemperatureName", name(command.name)) }
                DeviceCommand.Lock -> { put("action", "lockAction"); put("targetLockState.value", "LOCKED") }
                DeviceCommand.Unlock -> { put("action", "lockAction"); put("targetLockState.value", "UNLOCKED") }
                DeviceCommand.Open, DeviceCommand.Close, DeviceCommand.Pause, DeviceCommand.Resume, DeviceCommand.CancelPrint -> return null
            }
        }
    }

    /**
     * Why Alexa's answer to a control request says it didn't work, in words, or null when it did (Jarvis's `controlProblem`):
     * `{controlResponses: [{code}], errors: [{code, message}]}`. Amazon answers 200 for a refusal, so this is the only way to know.
     */
    fun controlProblem(body: JsonElement?): String? {
        val b = body.obj()
        val errors = b["errors"].array().map { it.obj() }
        if (errors.isNotEmpty()) {
            val code = errors[0]["code"].text() ?: "ERROR"
            if (code == "ENDPOINT_UNREACHABLE") return "Alexa couldn't reach the device."
            val message = errors[0]["message"].text()
            return "Alexa refused: $code${message?.let { " ($it)" }.orEmpty()}."
        }
        val failed = b["controlResponses"].array().map { it.obj() }.firstOrNull { r -> r["code"].text()?.let { it != "SUCCESS" } == true }
        return failed?.let { "Alexa answered ${it["code"].text()}." }
    }

    /** Batches of at most [size], each from one maker where it can be, so a slow brand holds up only its own devices. */
    fun stateBatches(devices: List<AlexaDevice>, size: Int): List<List<AlexaDevice>> {
        val out = mutableListOf<MutableList<AlexaDevice>>()
        for (d in devices.sortedBy { it.manufacturer.orEmpty() }) {
            val last = out.lastOrNull()
            if (last != null && last.size < size && last[0].manufacturer.orEmpty() == d.manufacturer.orEmpty()) last += d else out += mutableListOf(d)
        }
        // Small leftovers of different makers share batches, so a home of many brands isn't one request each.
        val full = out.filter { it.size == size }.toMutableList()
        val rest = out.filter { it.size < size }.flatten()
        rest.chunked(size).forEach { full += it.toMutableList() }
        return full
    }
}

