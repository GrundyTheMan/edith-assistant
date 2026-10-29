package com.edith.app.network

import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class WeatherData(
    val temp_c: String,
    val description: String,
    val city: String
)

class WeatherService {
    /**
     * Fetches weather data for a given location using wttr.in.
     */
    suspend fun getWeather(location: String): WeatherData? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://wttr.in/$location?format=j1")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val current = json.getJSONObject("current_condition")
                
                return@withContext WeatherData(
                    temp_c = current.optString("temp_C", "N/A"),
                    description = current.optString("weatherDesc", "N/A"),
                    city = location
                )
            } else {
                Log.e("WeatherService", "wttr.in returned ${connection.responseCode}")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("WeatherService", "Error fetching weather", e)
            return@withContext null
        }
    }
}
