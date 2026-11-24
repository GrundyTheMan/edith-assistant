package com.edith.app.tools.weather

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.edith.app.core.net.withTimeouts
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.io.IOException

/**
 * Open-Meteo (C09 §1–2): free, keyless, structured JSON. DTOs mirror responses captured live on 2026-11-01
 * (`src/test/resources/weather/`), never a guessed shape (L5). Base URLs are injectable for tests.
 */
class OpenMeteoClient(
    baseClient: Lazy<HttpClient>,
    private val dispatchers: AppDispatchers,
    private val geocodingUrl: String = "https://geocoding-api.open-meteo.com/",
    private val forecastUrl: String = "https://api.open-meteo.com/",
) {
    /** Built lazily on the IO dispatcher, like the Ollama client; 10 s is plenty for a small JSON reply. */
    private val http by lazy { baseClient.value.withTimeouts(TIMEOUT_S, TIMEOUT_S, TIMEOUT_S) }

    suspend fun geocode(name: String): Outcome<List<Place>> = get(
        geocodingUrl, "v1/search",
        "name" to name,   // Ktor URL-encodes
        "count" to "10", "language" to "en", "format" to "json",
    ) { body -> json.decodeFromString(GeocodingResponse.serializer(), body).results.orEmpty().map { it.toPlace() } }

    suspend fun forecast(latitude: Double, longitude: Double, units: WeatherUnits, days: Int): Outcome<Forecast> = get(
        forecastUrl, "v1/forecast",
        "latitude" to latitude.toString(), "longitude" to longitude.toString(),
        "current" to "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation",
        "daily" to "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
        "temperature_unit" to units.temperatureParam, "wind_speed_unit" to units.windParam,
        "timezone" to "auto", "forecast_days" to (days + 1).coerceIn(1, 4).toString(),
    ) { body -> json.decodeFromString(ForecastResponse.serializer(), body).toForecast() }

    private suspend fun <T> get(base: String, path: String, vararg query: Pair<String, String>, parse: (String) -> T): Outcome<T> = withContext(dispatchers.io) {
        try {
            val response = http.get(base.trimEnd('/') + "/" + path) { query.forEach { (k, v) -> parameter(k, v) } }
            if (!response.status.isSuccess()) {
                Log.w(LogTag.Tools, "Open-Meteo HTTP ${response.status.value} for /$path")
                return@withContext Outcome.Failure(AppError.ToolFailed("weather", "the weather service answered HTTP ${response.status.value}"))
            }
            Outcome.Success(parse(response.bodyAsText()))
        } catch (e: IOException) {
            Log.w(LogTag.Tools, "Open-Meteo unreachable (${e::class.simpleName})")
            Outcome.Failure(AppError.ToolFailed("weather", "the weather service didn't respond"))
        } catch (e: kotlinx.serialization.SerializationException) {
            Log.w(LogTag.Tools, "Open-Meteo reply couldn't be read", e)
            Outcome.Failure(AppError.ToolFailed("weather", "the weather service sent something unreadable"))
        } catch (e: IllegalArgumentException) {   // a malformed reply also surfaces as this from kotlinx.serialization
            Log.w(LogTag.Tools, "Open-Meteo reply couldn't be read", e)
            Outcome.Failure(AppError.ToolFailed("weather", "the weather service sent something unreadable"))
        }
    }

    private companion object {
        const val TIMEOUT_S = 10L
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

// --- DTOs (from the captured responses) ----------------------------------------------------------------------------

/** "No results" is `{"generationtime_ms":…}` with no `results` key at all. */
@Serializable private data class GeocodingResponse(val results: List<GeocodingResult>? = null)

@Serializable private data class GeocodingResult(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("country_code") val countryCode: String? = null,
    val country: String? = null,
    val admin1: String? = null,
    val population: Long? = null,
) {
    fun toPlace() = Place(name, latitude, longitude, admin1, country, countryCode, population ?: 0)
}

@Serializable private data class ForecastResponse(
    val current: Current,
    val daily: Daily? = null,
) {
    fun toForecast() = Forecast(
        current = CurrentWeather(
            time = current.time,
            temperature = current.temperature,
            feelsLike = current.apparentTemperature,
            humidity = current.humidity,
            weatherCode = current.weatherCode,
            wind = current.windSpeed,
            precipitation = current.precipitation,
        ),
        days = daily?.let { d ->
            d.time.indices.map { i ->
                DailyWeather(
                    date = d.time[i],
                    weatherCode = d.weatherCode.getOrNull(i),
                    high = d.max.getOrNull(i),
                    low = d.min.getOrNull(i),
                    rainChance = d.rainChance.getOrNull(i),
                )
            }
        }.orEmpty(),
    )
}

@Serializable private data class Current(
    val time: String,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
    @SerialName("relative_humidity_2m") val humidity: Int? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("wind_speed_10m") val windSpeed: Double? = null,
    val precipitation: Double? = null,
)

/** Parallel arrays, one entry per day; any value may be null for a day Open-Meteo lacks data for. */
@Serializable private data class Daily(
    val time: List<String> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("temperature_2m_max") val max: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val min: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val rainChance: List<Int?> = emptyList(),
)
