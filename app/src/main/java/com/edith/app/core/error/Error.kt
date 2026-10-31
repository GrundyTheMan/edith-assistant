package com.edith.app.core.error

import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Everything that can go wrong, with a plain-language message that says what to do (C02 §3). */
sealed interface AppError {
    val userMessage: String
    val cause: Throwable? get() = null

    data class ServerUnreachable(val url: String, override val cause: Throwable? = null) : AppError {
        override val userMessage get() = "Can't reach your Ollama server at $url. Is the PC on and Ollama running?"
    }

    data object Timeout : AppError {
        override val userMessage get() = "Edith took too long to answer. Try again."
    }

    data class ModelNotFound(val model: String) : AppError {
        override val userMessage get() = "The model \"$model\" isn't installed on your server. Pick another in Settings."
    }

    data object NoChatModels : AppError {
        override val userMessage get() = "No chat models are installed on your Ollama server. Install one on the PC, e.g. \"ollama pull gemma4\"."
    }

    data class ModelLacksCapability(val model: String, val capability: String) : AppError {
        override val userMessage get() = "The model \"$model\" can't $capability. Pick a different model in Settings."
    }

    data class OllamaHttp(val code: Int, val body: String) : AppError {
        override val userMessage get() = "Your Ollama server reported an error (HTTP $code): $body"
    }

    data class MalformedResponse(val detail: String, override val cause: Throwable? = null) : AppError {
        override val userMessage get() = "Your Ollama server sent a reply Edith couldn't read. Try again."
    }

    data class ToolFailed(val tool: String, val detail: String) : AppError {
        override val userMessage get() = "Edith couldn't use $tool: $detail"
    }

    data class PermissionDenied(val permission: String) : AppError {
        override val userMessage get() = "Edith needs the $permission permission for that. You can allow it in Android Settings."
    }

    data object GoogleNotConnected : AppError {
        override val userMessage get() = "Your Google account isn't connected yet. Connect it in Settings."
    }

    data class GoogleAuth(val detail: String, override val cause: Throwable? = null) : AppError {
        override val userMessage get() = "Google sign-in didn't work: $detail"
    }

    data class Unknown(override val cause: Throwable) : AppError {
        override val userMessage get() = "Something went wrong: ${cause.message ?: cause::class.simpleName}"
    }
}

/** Success or failure with an [AppError]. */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

/** Carries an [AppError] where a value can't be returned, e.g. out of a Flow. Collectors catch it. */
class AppErrorException(val error: AppError) : Exception(error.userMessage, error.cause)

/** Turns exceptions into [AppError]s at a layer boundary. */
object ErrorMapper {
    /** [serverUrl] names the server in [AppError.ServerUnreachable] when the failure was a network one. */
    fun map(t: Throwable, serverUrl: String = "the address in Settings"): AppError = when (t) {
        is AppErrorException -> t.error
        is SocketTimeoutException -> AppError.Timeout
        is ConnectException, is UnknownHostException, is NoRouteToHostException -> AppError.ServerUnreachable(serverUrl, t)
        else -> AppError.Unknown(t)
    }
}

/**
 * Runs [block] and returns its value as [Outcome.Success], or the mapped error as [Outcome.Failure].
 * Cancellation is rethrown, never turned into an error.
 */
inline fun <T> outcome(serverUrl: String = "the address in Settings", block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Outcome.Failure(ErrorMapper.map(e, serverUrl))
}
