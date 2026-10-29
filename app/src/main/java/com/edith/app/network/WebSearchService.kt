package com.edith.app.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class SearchResult(
    val title: String,
    val snippet: String,
    val link: String
)

class WebSearchService {

    /**
     * Fetches search results from DuckDuckGo.
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.duckduckgo.com/?q=$query&format=json&pretty=1")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = Gson().fromJson(response, JsonObject::class.java)
                
                val results = mutableListOf<SearchResult>()

                
                val resultsArray = json.getAsJsonArray("results")
                if (resultsArray != null) {
                    for (item in resultsArray) {
                        val obj = item.asJsonObject
                        val title = obj.get("title")?.asString ?: "No Title"
                        val snippet = obj.get("summary")?.asString ?: ""
                        val link = obj.get("href")?.asString ?: ""
                        results.add(SearchResult(title, snippet, link))
                    }
                }

                if (results.isEmpty()) {
                    val abstracts = json.getAsJsonArray("Abstracts")
                    if (abstracts != null) {
                        for (item in abstracts) {
                            val obj = item.asJsonObject
                            results.add(SearchResult(
                                obj.get("title")?.asString ?: "No Title",
                                obj.get("text")?.asString ?: "",
                                obj.get("url")?.asString ?: ""
                            ))
                        }
                    }
                }

                return@withContext results
            } else {
                Log.e("WebSearchService", "DuckDuckGo returned ${connection.responseCode}")
                return@withContext emptyList()
            }
        } catch (e: Exception) {
            Log.e("WebSearchService", "Error searching web", e)
            return@withContext emptyList()
        }
    }
}
