package com.edith.app.core.error

import kotlinx.coroutines.CancellationException

/** User-facing error types with plain-language messages that say what to do. */
sealed interface AppError {
    val userMessage: String
    val cause: Throwable? get() = null
}

data class ServerUnreachable(val url: String, override val cause: Throwable? = null) : AppError {
    override val userMessage: String
        get() = "Can't reach your Ollama server at $url. Is the PC on and Ollama running?"
}

object Timeout : AppError {
    override val userMessage: String
        get() = "Edith took too long to answer. Try again."
}

data class ModelNotFound(val model: String) : AppError {
    override val userMessage: String
        get() = "The model \"$model\" isn't installed on your server. Pick another in Settings."
}

data class ModelLacksCapability(val model: String, val capability: String) : AppError {
    override val userMessage: String
        get() = "The model \"$model\" can't $capability. Pick a different model in Settings."
}

data class OllamaHttp(val code: Int, val body: String) : AppError {
    override val userMessage: String
        get() = "Ollama error (HTTP $code): $body"
}

data class MalformedResponse(val detail: String, override val cause: Throwable? = null) : AppError {
    override val userMessage: String
        get() = "The server sent an unparseable response. This isn't Edith's fault — try again."
}

data class ToolFailed(val tool: String, val detail: String) : AppError {
    override val userMessage: String
        get() = "$tool failed: $detail"
}

object PermissionDenied : AppError {
    override val userMessage: String
        get() = "Edith needs permission to access a device feature. Please grant it in Settings."
}

object GoogleNotConnected : AppError {
    override val userMessage: String
        get() = "You're not signed into your Google account yet. Connect it in Settings."
}

data class GoogleAuth(val detail: String, override val cause: Throwable? = null) : AppError {
    override val userMessage: String
        get() = "Google sign-in failed: $detail"
}

data class Unknown(override val cause: Throwable) : AppError {
    override val userMessage: String
        get() = "Something went wrong: ${cause.message}"
}

/** Success or failure with an [AppError]. */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

/** Carries an [AppError] where a value can't be returned, e.g. out of a Flow. Collectors catch it. */
class AppErrorException(val error: AppError) : Exception(error.userMessage, error.cause)

/** Maps exceptions to [AppError], rethrows [CancellationException]. */
object ErrorMapper {
    /** Converts an exception to an [AppError]. Use inside a layer that can catch exceptions. */
    fun toError(block: () -> Unit): Outcome<Unit> {
        return try {
            block()
            Outcome.Success(Unit)
        } catch (e: CancellationException) {
            throw e // Don't swallow cancellation
        } catch (e: Exception) {
            Outcome.Failure(mapToAppError(e))
        }
    }

    private fun mapToAppError(t: Throwable): AppError {
        val cause = t.cause
        return when {
            t is java.net.ConnectException || cause?.javaClass?.name == "java.net.ConnectException" ->
                ServerUnreachable("10.0.2.2:11434", t)
            t is java.net.SocketTimeoutException || cause?.javaClass?.name == "java.net.SocketTimeoutException" ->
                Timeout
            else -> Unknown(t)
        }
    }

    /** A suspend version that awaits a coroutine block and returns an [Outcome]. */
    suspend fun <T> outcome(block: () -> T): Outcome<T> {
        return try {
            Outcome.Success(block())
        } catch (e: CancellationException) {
            throw e // Don't swallow cancellation
        } catch (e: Exception) {
            Outcome.Failure(mapToAppError(e))
        }
    }
}
