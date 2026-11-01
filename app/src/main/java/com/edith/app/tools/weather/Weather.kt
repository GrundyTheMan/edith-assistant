package com.edith.app.tools.weather

import com.edith.app.settings.Units
import java.util.Locale

data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val admin1: String?,
    val country: String?,
    val countryCode: String?,
    val population: Long,
) {
    /** "Fowlerville, Michigan (US)"; "Paris, Île-de-France (FR)". */
    val label: String get() = listOfNotNull(name, admin1?.takeIf { it != name }).joinToString(", ") + (countryCode?.let { " ($it)" } ?: "")
}

data class Forecast(val current: CurrentWeather, val days: List<DailyWeather>)
data class CurrentWeather(
    val time: String, val temperature: Double?, val feelsLike: Double?, val humidity: Int?,
    val weatherCode: Int?, val wind: Double?, val precipitation: Double?,
)
data class DailyWeather(val date: String, val weatherCode: Int?, val high: Double?, val low: Double?, val rainChance: Int?)

/** Decision D7: automatic from the phone's locale unless Settings forces one. */
enum class WeatherUnits(val temperatureParam: String, val windParam: String, val degrees: String, val speed: String) {
    Imperial("fahrenheit", "mph", "°F", "mph"),
    Metric("celsius", "kmh", "°C", "km/h");

    companion object {
        /** The countries that use Fahrenheit and miles per hour day to day. */
        private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

        fun from(setting: Units, locale: Locale = Locale.getDefault()): WeatherUnits = when (setting) {
            Units.Imperial -> Imperial
            Units.Metric -> Metric
            Units.Auto -> if (locale.country.uppercase() in IMPERIAL_COUNTRIES) Imperial else Metric
        }
    }
}

/** WMO weather codes (Open-Meteo's `weather_code`) as short phrases (C09 §4). */
fun describeWeatherCode(code: Int?): String? = when (code) {
    0 -> "clear"
    1 -> "mainly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "foggy"
    51, 53, 55 -> "drizzle"
    56, 57 -> "freezing drizzle"
    61, 63, 65 -> "rain"
    66, 67 -> "freezing rain"
    71, 73, 75 -> "snow"
    77 -> "snow grains"
    80, 81, 82 -> "rain showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorms"
    96, 99 -> "thunderstorms with hail"
    else -> null
}

/**
 * Picks the place the owner meant (C09 §3). "Fowlerville, MI" → the first part is the name, the rest a hint
 * matched against the region, country or country code (US state abbreviations included). Without a hint, or when
 * nothing matches it, the most populous result wins; [Choice.ambiguous] then says other places share the name.
 */
object PlaceResolver {
    data class Query(val name: String, val hint: String?)
    data class Choice(val place: Place, val ambiguous: Boolean)

    fun parse(input: String): Query {
        val parts = input.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return Query(parts.firstOrNull().orEmpty(), parts.drop(1).joinToString(", ").ifEmpty { null })
    }

    fun choose(results: List<Place>, hint: String?): Choice? {
        if (results.isEmpty()) return null
        val wanted = hint?.let { h -> h.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() } }.orEmpty()
            .flatMap { listOfNotNull(it, US_STATES[it.uppercase()]?.lowercase()) }
        val matching = if (wanted.isEmpty()) emptyList() else results.filter { p ->
            listOfNotNull(p.admin1, p.country, p.countryCode).any { it.lowercase() in wanted }
        }
        if (matching.isNotEmpty()) return Choice(matching.maxBy { it.population }, ambiguous = false)
        val best = results.maxBy { it.population }
        val sameName = results.count { it.name.equals(best.name, ignoreCase = true) }
        return Choice(best, ambiguous = sameName > 1)
    }

    private val US_STATES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California",
        "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware", "DC" to "District of Columbia", "FL" to "Florida",
        "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa",
        "KS" to "Kansas", "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
        "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi", "MO" to "Missouri",
        "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada", "NH" to "New Hampshire", "NJ" to "New Jersey",
        "NM" to "New Mexico", "NY" to "New York", "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio",
        "OK" to "Oklahoma", "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina",
        "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont",
        "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
    )
}
