package com.edith.app.ollama

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.AppErrorException
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLParserException
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.readUTF8Line
import com.edith.app.core.error.isSocketTimeout
import kotlinx.io.IOException

/** C03: Ollama client interface - streaming chat with tool calls, model list, model details. */
interface OllamaClient {
    /** Streams a chat reply. Emits Delta for text, ToolCalls when the model asks for tools, then Done.
     *  Failures are thrown as [AppErrorException]. */
    fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent>
    suspend fun chat(baseUrl: String, request: ChatCall): Outcome<OllamaMessage>
    suspend fun listModels(baseUrl: String): Outcome<List<OllamaModelTag>>
    suspend fun show(baseUrl: String, model: String): Outcome<OllamaShow>
    suspend fun version(baseUrl: String): Outcome<String>
}

/** One chat request to Ollama: the model, the messages and any tools. */
data class ChatCall(
    val model: String,
    val messages: List<OllamaMessage>,
    val tools: List<OllamaTool>? = null,
    /** C35: a JSON schema for the reply (Ollama's `format`), e.g. the to-do ranking's. */
    val format: kotlinx.serialization.json.JsonElement? = null,
    val temperature: Double? = null,
)

/** What a streamed chat request produces: text, tool calls and the end. */
sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    /** The assistant message to append to the history verbatim before the tool results. */
    data class ToolCalls(val assistantMessage: OllamaMessage) : ChatEvent
    data class Done(val stats: ReplyStats) : ChatEvent
}

/** The timing and token counts Ollama reports at the end of a reply. */
data class ReplyStats(val totalMs: Long?, val evalCount: Int?, val promptEvalCount: Int?)

/**
 * Every request goes through [chatRequest], which applies [ThinkPolicy] and [OllamaDtos.CONTEXT_LENGTH], so
 * no caller can send a chat without them. [keepAlive] is decision D8 (default: not sent).
 *
 * The Ktor client is [Lazy] and first touched on the IO dispatcher: building it sets up TLS (~190 ms on the
 * emulator), which must not happen on the main thread when a screen merely gets hold of this client.
 */
class KtorOllamaClient(
    httpProvider: Lazy<HttpClient>,
    private val json: Json,
    private val dispatchers: AppDispatchers,
    private val keepAlive: String? = null,
) : OllamaClient {
    constructor(http: HttpClient, json: Json, dispatchers: AppDispatchers, keepAlive: String? = null) :
        this(lazyOf(http), json, dispatchers, keepAlive)

    private val http by httpProvider

    /**
     * Streams the reply line by line. Cancelling the collector cancels the request (Ktor cancels the OkHttp call under it),
     * which also makes Ollama stop generating.
     */
    override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = channelFlow {
        try {
            http.preparePost(url(baseUrl, "api/chat")) { chatRequest(request, stream = true) }.execute { response ->
                if (!response.status.isSuccess()) throw AppErrorException(failure(response.status.value, response.bodyAsText(), request.model))
                val channel = response.bodyAsChannel()
                val text = StringBuilder()
                val toolCalls = mutableListOf<OllamaToolCall>()
                while (true) {
                    val line = try {
                        channel.readUTF8Line()
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw AppErrorException(ioError(e, baseUrl))
                    } ?: throw AppErrorException(AppError.MalformedResponse("Stream ended before done"))
                    if (line.isBlank()) continue
                    val chunk = parse(line, OllamaChatChunk.serializer())
                    chunk.error?.let { throw AppErrorException(errorFor(response.status.value, it, request.model)) }
                    chunk.message?.let { message ->
                        if (message.content.isNotEmpty()) {
                            text.append(message.content)
                            send(ChatEvent.Delta(message.content))
                        }
                        message.toolCalls?.let { toolCalls += it }
                    }
                    if (chunk.done) {
                        if (toolCalls.isNotEmpty()) {
                            send(ChatEvent.ToolCalls(OllamaMessage("assistant", text.toString(), toolCalls = toolCalls)))
                        }
                        send(ChatEvent.Done(chunk.stats()))
                        break
                    }
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw AppErrorException(ioError(e, baseUrl))
        }
    }.flowOn(dispatchers.io)

    override suspend fun chat(baseUrl: String, request: ChatCall): Outcome<OllamaMessage> = call {
        val body = send(baseUrl) { http.post(url(baseUrl, "api/chat")) { chatRequest(request, stream = false) } }.checked(request.model)
        val chunk = parse(body, OllamaChatChunk.serializer())
        chunk.error?.let { throw AppErrorException(errorFor(200, it, request.model)) }
        chunk.message ?: throw AppErrorException(AppError.MalformedResponse("Reply has no message"))
    }

    override suspend fun listModels(baseUrl: String): Outcome<List<OllamaModelTag>> = call {
        get(baseUrl, "api/tags", model = null) { parse(it, OllamaTags.serializer()).models }
    }

    override suspend fun show(baseUrl: String, model: String): Outcome<OllamaShow> = call {
        val body = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("model" to JsonPrimitive(model))))
        val text = send(baseUrl) { http.post(url(baseUrl, "api/show")) { setBody(TextContent(body, JSON_TYPE)) } }.checked(model)
        parse(text, OllamaShow.serializer())
    }

    override suspend fun version(baseUrl: String): Outcome<String> = call {
        get(baseUrl, "api/version", model = null) {
            parse(it, JsonObject.serializer())["version"]?.jsonPrimitive?.contentOrNull
                ?: throw AppErrorException(AppError.MalformedResponse("No version in reply"))
        }
    }

    private fun HttpRequestBuilder.chatRequest(call: ChatCall, stream: Boolean) {
        val body = OllamaChatRequest(
            model = call.model,
            messages = call.messages,
            stream = stream,
            think = ThinkPolicy.forModel(call.model),
            options = OllamaOptions(numCtx = OllamaDtos.CONTEXT_LENGTH, temperature = call.temperature),
            tools = call.tools?.takeIf { it.isNotEmpty() },
            keepAlive = keepAlive,
            format = call.format,
        )
        setBody(TextContent(json.encodeToString(OllamaChatRequest.serializer(), body), JSON_TYPE))
    }

    private suspend fun <T> get(baseUrl: String, path: String, model: String?, parse: (String) -> T): T =
        parse(send(baseUrl) { http.get(url(baseUrl, path)) }.checked(model))

    /** Sends the request and reads the whole answer; a network failure is turned into an [AppError] (thrown). */
    private suspend fun send(baseUrl: String, block: suspend () -> HttpResponse): Answer = try {
        val response = block()
        Answer(response.status.value, response.status.isSuccess(), response.bodyAsText())
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw AppErrorException(ioError(e, baseUrl))
    }

    private class Answer(val code: Int, val ok: Boolean, val body: String)

    /** The body of a 2xx answer; a non-2xx one is turned into an [AppError] (thrown). */
    private fun Answer.checked(model: String?): String {
        if (!ok) throw AppErrorException(failure(code, body, model))
        return body
    }

    private fun failure(code: Int, body: String, model: String?): AppError {
        val message = runCatching { parse(body, OllamaChatChunk.serializer()).error }.getOrNull() ?: body
        return errorFor(code, message, model)
    }

    private fun errorFor(code: Int, message: String, model: String?): AppError =
        if (model != null && message.contains("not found", ignoreCase = true)) AppError.ModelNotFound(model)
        else AppError.OllamaHttp(code, message)

    private fun ioError(e: IOException, baseUrl: String): AppError =
        if (e.isSocketTimeout() || e is SocketTimeoutException || e is ConnectTimeoutException) AppError.Timeout
        else AppError.ServerUnreachable(baseUrl, e)

    private fun <T> parse(text: String, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw AppErrorException(AppError.MalformedResponse("Unreadable reply from Ollama", e))
    } catch (e: IllegalArgumentException) {
        throw AppErrorException(AppError.MalformedResponse("Unreadable reply from Ollama", e))
    }

    private fun url(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + "/" + path

    /** Runs [block] on IO and turns thrown [AppErrorException]s into [Outcome.Failure]. */
    private suspend fun <T> call(block: suspend () -> T): Outcome<T> = withContext(dispatchers.io) {
        try {
            Outcome.Success(block())
        } catch (e: AppErrorException) {
            Log.w(LogTag.Ollama, "Request failed: ${e.error::class.simpleName}")
            Outcome.Failure(e.error)
        } catch (e: IllegalArgumentException) {
            // A malformed server URL (e.g. a typo in Settings).
            Outcome.Failure(AppError.ServerUnreachable("(invalid address)", e))
        } catch (e: URLParserException) {
            Outcome.Failure(AppError.ServerUnreachable("(invalid address)", e))
        }
    }

    private fun OllamaChatChunk.stats() =
        ReplyStats(totalMs = totalDuration?.let { it / 1_000_000 }, evalCount = evalCount, promptEvalCount = promptEvalCount)

    private companion object {
        val JSON_TYPE = ContentType.Application.Json.withCharset(Charsets.UTF_8)
    }
}
