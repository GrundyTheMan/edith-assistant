package com.edith.app.printer.feed

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * The typed commands Edith sends to Jarvis's `POST /v1/printer/command` (C39 part 4.2). They are Jarvis's own
 * `PrinterCommand`: all 33 kinds, with exactly these names and fields. Edith never builds G-code; Jarvis does.
 * Axes and modes are plain strings from [Axis], [HomeAxes] and [AirMode] so a typo can't turn into another kind.
 * Fields without a default are always sent; an optional number left null is left out of the JSON.
 */
@Serializable
internal sealed interface PrinterCommand {
    @Serializable @SerialName("pause") data object Pause : PrinterCommand
    @Serializable @SerialName("resume") data object Resume : PrinterCommand
    @Serializable @SerialName("cancel") data object Cancel : PrinterCommand
    @Serializable @SerialName("start") data class Start(val path: String) : PrinterCommand
    @Serializable @SerialName("estop") data object EStop : PrinterCommand
    @Serializable @SerialName("restart") data object Restart : PrinterCommand
    @Serializable @SerialName("firmwareRestart") data object FirmwareRestart : PrinterCommand
    @Serializable @SerialName("setTemp") data class SetTemp(val heater: String, val target: Double) : PrinterCommand
    @Serializable @SerialName("preset") data class Preset(val name: String) : PrinterCommand
    @Serializable @SerialName("heatersOff") data object HeatersOff : PrinterCommand
    @Serializable @SerialName("home") data class Home(val axes: String) : PrinterCommand
    @Serializable @SerialName("jog") data class Jog(val axis: String, val distance: Double) : PrinterCommand
    @Serializable @SerialName("zOffset") data class ZOffset(val delta: Double) : PrinterCommand
    @Serializable @SerialName("extrude") data class Extrude(val length: Double, val speed: Double) : PrinterCommand
    @Serializable @SerialName("motorsOff") data object MotorsOff : PrinterCommand
    @Serializable @SerialName("speedFactor") data class SpeedFactor(val percent: Double) : PrinterCommand
    @Serializable @SerialName("flowFactor") data class FlowFactor(val percent: Double) : PrinterCommand
    @Serializable @SerialName("limits")
    data class Limits(
        val velocity: Double? = null,
        val accel: Double? = null,
        val scv: Double? = null,
        val cruiseRatio: Double? = null,
    ) : PrinterCommand
    @Serializable @SerialName("retraction")
    data class Retraction(
        val length: Double? = null,
        val speed: Double? = null,
        val extraLength: Double? = null,
        val unretractSpeed: Double? = null,
    ) : PrinterCommand
    @Serializable @SerialName("fan") data class Fan(val fan: String, val speed: Double) : PrinterCommand
    @Serializable @SerialName("light") data class Light(val level: Double) : PrinterCommand
    @Serializable @SerialName("airCirculation") data class AirCirculation(val mode: String) : PrinterCommand
    @Serializable @SerialName("filamentSensor") data class FilamentSensor(val sensor: String, val enabled: Boolean) : PrinterCommand
    @Serializable @SerialName("camera") data class Camera(val on: Boolean) : PrinterCommand
    @Serializable @SerialName("macro") data class Macro(val name: String, val params: Map<String, String> = emptyMap()) : PrinterCommand
    @Serializable @SerialName("excludeObject") data class ExcludeObject(val name: String) : PrinterCommand
    @Serializable @SerialName("gcode") data class Gcode(val script: String) : PrinterCommand
    @Serializable @SerialName("deleteFile") data class DeleteFile(val path: String) : PrinterCommand
    @Serializable @SerialName("screenTap") data class ScreenTap(val x: Int, val y: Int) : PrinterCommand
    @Serializable @SerialName("queueAdd") data class QueueAdd(val path: String) : PrinterCommand
    @Serializable @SerialName("queueRemove") data class QueueRemove(val jobId: String) : PrinterCommand
    @Serializable @SerialName("queueStart") data object QueueStart : PrinterCommand
    @Serializable @SerialName("queuePause") data object QueuePause : PrinterCommand
}

internal object Axis {
    const val X = "x"
    const val Y = "y"
    const val Z = "z"
}

internal object HomeAxes {
    const val ALL = "xyz"
    const val XY = "xy"
    const val Z = "z"
}

internal object AirMode {
    const val INTERNAL = "internal"
    const val EXTERNAL = "external"
    const val STOP = "stop"
}

/** Writes commands as `{"kind":"jog","axis":"x","distance":10}` (the kind first, left-out optionals not sent). */
internal val commandJson = Json {
    classDiscriminator = "kind"
    encodeDefaults = false
    explicitNulls = false
}

internal fun PrinterCommand.toJson(): String = commandJson.encodeToString(PrinterCommand.serializer(), this)

/** The command's kind ("jog", "pause"…), for logs and the `quick` map's keys. */
internal val PrinterCommand.kind: String
    get() = (commandJson.encodeToJsonElement(PrinterCommand.serializer(), this) as JsonObject).getValue("kind").jsonPrimitive.content
