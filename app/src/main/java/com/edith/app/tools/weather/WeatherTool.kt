package com.edith.app.tools.weather

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.tools.framework.PermissionNeed
import com.edith.app.tools.location.LocationProvider
import com.edith.app.tools.framework.ParamType
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolParam
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolSpec
import com.edith.app.core.time.formatPattern
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Real weather from Open-Meteo (C09): "What's the weather in Fowlerville?" → current conditions, plus up to three
 * days ahead when asked. Units follow decision D7. Results are short plain text for the model (≤ 400 chars).
 */
class WeatherTool(
    private val client: OpenMeteoClient,
    private val device: LocationProvider? = null,
    private val locale: () -> Locale = Locale::getDefault,
) : Tool {
    override val spec = ToolSpec(
        name = "get_weather",
        description = "Get the current weather for a place, and a forecast for up to 3 days when asked.",
        parameters = listOf(
            ToolParam("location", ParamType.String, "City, optionally with state or country, e.g. \"Fowlerville, MI\"; \"here\" for the user's current location",
                aliases = listOf("place", "city", "where")),
            ToolParam("days", ParamType.Integer, "Days of forecast after today: 0 for now only (default), up to 3", aliases = listOf("forecast_days")),
        ),
    )
    override val statusLabel = "Checking the weather…"
    override val displayName = "weather"

    override val guidance: String?
        get() = if (device == null) null else "If the user says \"here\" or \"near me\", or doesn't name a place, call get_weather with location \"here\"."

    /** C11: "here" needs approximate location, asked with Android's dialog the first time. */
    override fun permission(args: ToolArgs): PermissionNeed? =
        if (device != null && isHere(args.string("location"))) PermissionNeed(LocationProvider.PERMISSION, LocationProvider.DENIED) else null

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val location = args.string("location")
        val days = (args.int("days") ?: 0).coerceIn(0, 3)
        if (location == null || isHere(location)) return here(days, ctx)
        val query = PlaceResolver.parse(location)
        val places = when (val r = client.geocode(query.name)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return ToolResult.Error(UNAVAILABLE)
        }
        val choice = PlaceResolver.choose(places, query.hint) ?: return ToolResult.Error("Couldn't find a place called $location.")
        val units = WeatherUnits.from(ctx.settings.units, locale())
        val forecast = when (val r = client.forecast(choice.place.latitude, choice.place.longitude, units, days)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return ToolResult.Error(UNAVAILABLE)
        }
        return ToolResult.Ok(describe(choice, forecast, units, days))
    }

    /** The phone's location, named on the phone when it can be (the coordinates never reach the model). */
    private suspend fun here(days: Int, ctx: ToolContext): ToolResult {
        val device = device ?: return ToolResult.Error("I need your location — ask the user for a city.")
        val at = when (val r = device.current()) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return ToolResult.Error((r.error as? AppError.ToolFailed)?.detail ?: LocationProvider.NO_FIX)
        }
        val name = device.placeName(at) ?: CURRENT_LOCATION
        val units = WeatherUnits.from(ctx.settings.units, locale())
        val forecast = when (val r = client.forecast(at.latitude, at.longitude, units, days)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return ToolResult.Error(UNAVAILABLE)
        }
        val place = Place(name, at.latitude, at.longitude, admin1 = null, country = null, countryCode = null, population = 0)
        return ToolResult.Ok(describe(PlaceResolver.Choice(place, ambiguous = false), forecast, units, days))
    }

    private fun isHere(location: String?) = location.isNullOrBlank() || location.lowercase().trim().trimEnd('.', '?', '!') in HERE

    /** "Fowlerville, Michigan (US) — now 72°F (feels like 66°F), partly cloudy, wind 16 mph, humidity 46%. Tomorrow: …" */
    internal fun describe(choice: PlaceResolver.Choice, forecast: Forecast, units: WeatherUnits, days: Int): String {
        val c = forecast.current
        fun deg(v: Double?) = v?.let { "${it.roundToInt()}${units.degrees}" }
        val now = listOfNotNull(
            deg(c.temperature)?.let { t -> "now $t" + (deg(c.feelsLike)?.takeIf { it != t }?.let { " (feels like $it)" } ?: "") },
            describeWeatherCode(c.weatherCode),
            c.wind?.let { "wind ${it.roundToInt()} ${units.speed}" },
            c.humidity?.let { "humidity $it%" },
        ).joinToString(", ")
        val ahead = forecast.days.drop(1).take(days).mapIndexed { i, d ->
            val label = if (i == 0) "Tomorrow" else formatPattern(LocalDate.parse(d.date).atStartOfDayIn(TimeZone.UTC), TimeZone.UTC, "EEEE", "en-US")
            label + ": " + listOfNotNull(
                deg(d.high)?.let { "high $it" }, deg(d.low)?.let { "low $it" }, describeWeatherCode(d.weatherCode),
                d.rainChance?.let { "$it% chance of rain" },
            ).joinToString(", ") + "."
        }
        val place = choice.place.label + if (choice.ambiguous) " (other places share this name — say a state or country if you meant another)" else ""
        return ("$place — $now." + ahead.joinToString(" ", prefix = if (ahead.isEmpty()) "" else " ")).take(MAX_CHARS)
    }

    private companion object {
        const val MAX_CHARS = 400
        const val UNAVAILABLE = "The weather service didn't respond — try again shortly."
        val HERE = setOf("here", "my location", "current location", "my current location", "where i am", "my area", "near me", "nearby")
        const val CURRENT_LOCATION = "Your current location"
    }
}
