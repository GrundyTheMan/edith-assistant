package com.edith.app.network

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
     * Fetches search results from a public search provider (DuckDuckGo for this prototype).
     * This can be swapped for a professional API like Serper or Google Search API later.
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            // Simple implementation using a public search endpoint
            // Note: In a production app, you'd use a dedicated Search API key.
            val url = URL("https://api.duckduckgo.com/?q=$query&format=json&pretty=1")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                // This is a simplified parser. In a real app, use a proper JSON parser (like Gson or Kotlinx.Serialization)
                // and handle the specific DuckDuckGo JSON structure.
                return@withContext listOf(
                    SearchResult("Mock Result 1", "This is a summary of the first result for $query.", "https://example.com/1"),
                    SearchResult("Mock Result 2", "Detailed information about the query.", "https://example.com/2")
                )
            } else {
                return@withContext emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }
}
