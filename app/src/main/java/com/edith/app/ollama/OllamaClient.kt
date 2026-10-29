package com.edith.app.ollama

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.AppErrorException
import com.edith.app.core.error.MalformedResponse
import com.edith.app.core.error.ModelNotFound
import com.edith.app.core.error.OllamaHttp
import com.edith.app.core.error.Outcome
import com.edith.app.core.error.ServerUnreachable
import com.edith.app.core.error.Timeout
import com.edith.app.core.log.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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

data class ChatCall(val model: String, val messages: List<OllamaMessage>, val tools: List<OllamaTool>? = null)

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    /** The assistant message to append to the history verbatim before the tool results. */
    data class ToolCalls(val assistantMessage: OllamaMessage) : ChatEvent
    data class Done(val stats: ReplyStats) : ChatEvent
}

data class ReplyStats(val totalMs: Long?, val evalCount: Int?, val promptEvalCount: Int?)

/**
 * Every request goes through [chatRequest], which applies [ThinkPolicy] and [OllamaDtos.CONTEXT_LENGTH], so
 * no caller can send a chat without them. [keepAlive] is decision D8 (default: not sent).
 */
class OkHttpOllamaClient(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val dispatchers: AppDispatchers,
    private val keepAlive: String? = null,
) : OllamaClient {

    override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = flow {
        val call = okHttp.newCall(chatRequest(baseUrl, request, stream = true))
        val finished = AtomicBoolean(false)
        coroutineScope {
            // The read below blocks, so coroutine cancellation alone can't stop it: cancelling the
            // OkHttp call does, and also makes Ollama stop generating.
            // UNDISPATCHED: enter the try before reading starts, so the finally runs even on an immediate cancel.
            val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!finished.get()) call.cancel() }
            }
            try {
                execute(call, baseUrl, request.model).use { response ->
                    val source = response.body.source()
                    val text = StringBuilder()
                    val toolCalls = mutableListOf<OllamaToolCall>()
                    while (true) {
                        val line = try {
                            source.readUtf8Line()
                        } catch (e: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw AppErrorException(ioError(e, baseUrl))
                        } ?: throw AppErrorException(MalformedResponse("Stream ended before done"))
                        if (line.isBlank()) continue
                        val chunk = parse(line, OllamaChatChunk.serializer())
                        chunk.error?.let { throw AppErrorException(errorFor(response.code, it, request.model)) }
                        chunk.message?.let { message ->
                            if (message.content.isNotEmpty()) {
                                text.append(message.content)
                                emit(ChatEvent.Delta(message.content))
                            }
                            message.toolCalls?.let { toolCalls += it }
                        }
                        if (chunk.done) {
                            if (toolCalls.isNotEmpty()) {
                                emit(ChatEvent.ToolCalls(OllamaMessage("assistant", text.toString(), toolCalls = toolCalls)))
                            }
                            emit(ChatEvent.Done(chunk.stats()))
                            break
                        }
                    }
                }
                finished.set(true)   // only a complete stream skips the cancel; errors and abandonment cancel
            } finally {
                canceller.cancel()
            }
        }
    }.flowOn(dispatchers.io)

    override suspend fun chat(baseUrl: String, request: ChatCall): Outcome<OllamaMessage> = call {
        val chunk = execute(okHttp.newCall(chatRequest(baseUrl, request, stream = false)), baseUrl, request.model)
            .use { parse(it.body.string(), OllamaChatChunk.serializer()) }
        chunk.error?.let { throw AppErrorException(errorFor(200, it, request.model)) }
        chunk.message ?: throw AppErrorException(MalformedResponse("Reply has no message"))
    }

    override suspend fun listModels(baseUrl: String): Outcome<List<OllamaModelTag>> = call {
        get(baseUrl, "api/tags", model = null) { parse(it, OllamaTags.serializer()).models }
    }

    override suspend fun show(baseUrl: String, model: String): Outcome<OllamaShow> = call {
        val body = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("model" to JsonPrimitive(model))))
        val httpRequest = Request.Builder().url(url(baseUrl, "api/show")).post(body.toRequestBody(JSON_TYPE)).build()
        execute(okHttp.newCall(httpRequest), baseUrl, model).use { parse(it.body.string(), OllamaShow.serializer()) }
    }

    override suspend fun version(baseUrl: String): Outcome<String> = call {
        get(baseUrl, "api/version", model = null) {
            parse(it, JsonObject.serializer())["version"]?.jsonPrimitive?.contentOrNull
                ?: throw AppErrorException(MalformedResponse("No version in reply"))
        }
    }

    private fun chatRequest(baseUrl: String, call: ChatCall, stream: Boolean): Request {
        val body = OllamaChatRequest(
            model = call.model,
            messages = call.messages,
            stream = stream,
            think = ThinkPolicy.forModel(call.model),
            options = OllamaOptions(numCtx = OllamaDtos.CONTEXT_LENGTH),
            tools = call.tools?.takeIf { it.isNotEmpty() },
            keepAlive = keepAlive,
        )
        val payload = json.encodeToString(OllamaChatRequest.serializer(), body)
        return Request.Builder().url(url(baseUrl, "api/chat")).post(payload.toRequestBody(JSON_TYPE)).build()
    }

    private fun <T> get(baseUrl: String, path: String, model: String?, parse: (String) -> T): T {
        val httpRequest = Request.Builder().url(url(baseUrl, path)).get().build()
        return execute(okHttp.newCall(httpRequest), baseUrl, model).use { parse(it.body.string()) }
    }

    /** Runs the call; a non-2xx reply is turned into an [AppError] (thrown). The caller closes the response. */
    private fun execute(call: Call, baseUrl: String, model: String?): Response {
        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw AppErrorException(ioError(e, baseUrl))
        }
        if (!response.isSuccessful) {
            val body = response.use { it.body.string() }
            val message = runCatching { parse(body, OllamaChatChunk.serializer()).error }.getOrNull() ?: body
            throw AppErrorException(errorFor(response.code, message, model))
        }
        return response
    }

    private fun errorFor(code: Int, message: String, model: String?): AppError =
        if (model != null && message.contains("not found", ignoreCase = true)) ModelNotFound(model)
        else OllamaHttp(code, message)

    private fun ioError(e: IOException, baseUrl: String): AppError =
        if (e is SocketTimeoutException) Timeout else ServerUnreachable(baseUrl, e)

    private fun <T> parse(text: String, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw AppErrorException(MalformedResponse("Unreadable reply from Ollama", e))
    } catch (e: IllegalArgumentException) {
        throw AppErrorException(MalformedResponse("Unreadable reply from Ollama", e))
    }

    private fun url(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + "/" + path

    /** Runs [block] on IO and turns thrown [AppErrorException]s into [Outcome.Failure]. */
    private suspend fun <T> call(block: () -> T): Outcome<T> = withContext(dispatchers.io) {
        try {
            Outcome.Success(block())
        } catch (e: AppErrorException) {
            Log.w(Log.LogTag.Ollama, "Request failed: ${e.error::class.simpleName}")
            Outcome.Failure(e.error)
        } catch (e: IllegalArgumentException) {
            // OkHttp rejects malformed server URLs (e.g. a typo in Settings).
            Outcome.Failure(ServerUnreachable("(invalid address)", e))
        }
    }

    private fun OllamaChatChunk.stats() =
        ReplyStats(totalMs = totalDuration?.let { it / 1_000_000 }, evalCount = evalCount, promptEvalCount = promptEvalCount)

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
