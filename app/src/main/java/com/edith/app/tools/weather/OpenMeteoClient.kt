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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Open-Meteo (C09 §1–2): free, keyless, structured JSON. DTOs mirror responses captured live on 2026-11-01
 * (`src/test/resources/weather/`), never a guessed shape (L5). Base URLs are injectable for tests.
 */
class OpenMeteoClient(
    baseClient: Lazy<OkHttpClient>,
    private val dispatchers: AppDispatchers,
    private val geocodingUrl: String = "https://geocoding-api.open-meteo.com/",
    private val forecastUrl: String = "https://api.open-meteo.com/",
) {
    /** Built lazily on the IO dispatcher, like the Ollama client; 10 s is plenty for a small JSON reply. */
    private val http by lazy {
        baseClient.value.newBuilder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS).readTimeout(TIMEOUT_S, TimeUnit.SECONDS).callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    suspend fun geocode(name: String): Outcome<List<Place>> = get(
        geocodingUrl.toHttpUrl().newBuilder().addPathSegments("v1/search")
            .addQueryParameter("name", name)   // OkHttp URL-encodes
            .addQueryParameter("count", "10").addQueryParameter("language", "en").addQueryParameter("format", "json")
            .build(),
    ) { body -> json.decodeFromString(GeocodingResponse.serializer(), body).results.orEmpty().map { it.toPlace() } }

    suspend fun forecast(latitude: Double, longitude: Double, units: WeatherUnits, days: Int): Outcome<Forecast> = get(
        forecastUrl.toHttpUrl().newBuilder().addPathSegments("v1/forecast")
            .addQueryParameter("latitude", latitude.toString()).addQueryParameter("longitude", longitude.toString())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("temperature_unit", units.temperatureParam).addQueryParameter("wind_speed_unit", units.windParam)
            .addQueryParameter("timezone", "auto").addQueryParameter("forecast_days", (days + 1).coerceIn(1, 4).toString())
            .build(),
    ) { body -> json.decodeFromString(ForecastResponse.serializer(), body).toForecast() }

    private suspend fun <T> get(url: HttpUrl, parse: (String) -> T): Outcome<T> = withContext(dispatchers.io) {
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(LogTag.Tools, "Open-Meteo HTTP ${response.code} for ${url.encodedPath}")
                    return@withContext Outcome.Failure(AppError.ToolFailed("weather", "the weather service answered HTTP ${response.code}"))
                }
                Outcome.Success(parse(response.body.string()))
            }
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
