package com.edith.app.core.error

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorMapperTest {
    @Test fun `connection refused maps to ServerUnreachable with the server address`() {
        val error = ErrorMapper.map(ConnectException("refused"), "http://10.0.2.2:11434")
        assertTrue(error is AppError.ServerUnreachable)
        assertEquals("http://10.0.2.2:11434", (error as AppError.ServerUnreachable).url)
    }

    @Test fun `unknown host maps to ServerUnreachable`() {
        assertTrue(ErrorMapper.map(UnknownHostException("no-such-pc")) is AppError.ServerUnreachable)
    }

    @Test fun `socket timeout maps to Timeout`() {
        assertEquals(AppError.Timeout, ErrorMapper.map(SocketTimeoutException()))
    }

    @Test fun `an AppErrorException keeps its error`() {
        val error = AppError.ModelNotFound("gemma4")
        assertEquals(error, ErrorMapper.map(AppErrorException(error)))
    }

    @Test fun `anything else maps to Unknown`() {
        val e = IOException("disk full")
        assertEquals(AppError.Unknown(e), ErrorMapper.map(e))
    }

    @Test fun `outcome wraps success and failure`() {
        assertEquals(Outcome.Success(42), outcome { 42 })
        assertEquals(Outcome.Failure(AppError.Timeout), outcome { throw SocketTimeoutException() })
    }

    @Test fun `outcome rethrows cancellation instead of mapping it`() {
        try {
            outcome { throw CancellationException("cancelled") }
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test fun `every error has a user message that says something`() {
        val errors = listOf(
            AppError.ServerUnreachable("http://pc:11434"), AppError.Timeout, AppError.ModelNotFound("m"), AppError.NoChatModels,
            AppError.ModelLacksCapability("m", "see images"), AppError.OllamaHttp(500, "boom"),
            AppError.MalformedResponse("x"), AppError.ToolFailed("weather", "no network"),
            AppError.PermissionDenied("microphone"), AppError.GoogleNotConnected, AppError.GoogleAuth("expired"),
            AppError.Unknown(IllegalStateException("odd")),
        )
        errors.forEach { assertTrue("$it", it.userMessage.length > 10) }
    }
}
