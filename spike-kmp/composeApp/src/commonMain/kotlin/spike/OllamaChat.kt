package spike

import io.ktor.client.HttpClient
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A thing that went wrong that the owner can be told in words. */
class ChatException(message: String) : Exception(message)

/** One line of Ollama's streaming answer: the next piece of text, and whether the reply is finished. */
data class ChatChunk(val text: String, val done: Boolean)

/** Reads one line of Ollama's `/api/chat` stream. An empty or damaged line is nothing; an `error` line is an exception. */
fun parseChatLine(line: String): ChatChunk? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    val obj = try {
        Json.parseToJsonElement(trimmed).jsonObject
    } catch (e: SerializationException) {
        return null
    } catch (e: IllegalArgumentException) {
        return null
    }
    obj["error"]?.jsonPrimitive?.contentOrNull?.let { throw ChatException(it) }
    val text = obj["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
    val done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: false
    return ChatChunk(text, done)
}

/** Streams a reply from the owner's Ollama server (the same call the Android and iPhone apps make). Shared by every platform. */
class OllamaChat(private val client: HttpClient) {
    // channelFlow, not flow: the pieces are sent from inside the HTTP call, which on some platforms is not the collector's own coroutine.
    fun stream(baseUrl: String, model: String, prompt: String): Flow<String> = channelFlow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "user"); put("content", prompt) })
                },
            )
        }.toString()
        client.preparePost("${baseUrl.trimEnd('/')}/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            if (!response.status.isSuccess()) throw ChatException("The server answered HTTP ${response.status.value}.")
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readLine() ?: break
                val chunk = parseChatLine(line) ?: continue
                if (chunk.text.isNotEmpty()) send(chunk.text)
                if (chunk.done) break
            }
        }
    }
}
