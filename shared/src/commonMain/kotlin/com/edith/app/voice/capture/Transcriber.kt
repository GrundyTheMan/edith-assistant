package com.edith.app.voice.capture

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.ollama.ChatCall
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaMessage
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.withContext
import com.edith.app.core.util.readFileBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeSource

/** Turns a recording into text. "" means no speech was heard (L11): nothing is sent. */
fun interface Transcriber {
    suspend fun transcribe(audio: RecordedAudio): Outcome<String>
}

/**
 * The current model transcribes (C12 §3, 03-ollama-reference §5): one non-streaming `/api/chat` call with the WAV in
 * `images` (audio uses the same field) and the prompt that makes silence answer `[NO_SPEECH]` instead of an apology.
 * Only for models with the "audio" capability; [VoiceMemos] uses live speech recognition for the others.
 */
@OptIn(ExperimentalEncodingApi::class)
class OllamaTranscriber(
    private val client: OllamaClient,
    private val settings: SettingsRepository,
    private val dispatchers: AppDispatchers,
) : Transcriber {
    override suspend fun transcribe(audio: RecordedAudio): Outcome<String> {
        val s = settings.current()
        val model = s.model ?: return Outcome.Failure(AppError.NoChatModels)
        val wav = withContext(dispatchers.io) { Base64.encode(readFileBytes(audio.path)) }
        val started = TimeSource.Monotonic.markNow()
        val reply = client.chat(s.serverUrl, ChatCall(model, listOf(OllamaMessage("user", PROMPT, images = listOf(wav)))))
        Log.i(LogTag.Voice, "Transcribed ${audio.durationMs} ms of audio in ${started.elapsedNow().inWholeMilliseconds} ms")
        return when (reply) {
            is Outcome.Success -> Outcome.Success(clean(reply.value.content))
            is Outcome.Failure -> reply
        }
    }

    companion object {
        const val PROMPT = "Transcribe this audio verbatim. Reply with only the transcript, no commentary. " +
            "If there is no intelligible speech, reply with exactly [NO_SPEECH] and nothing else."
        const val NO_SPEECH = "[NO_SPEECH]"

        fun clean(reply: String): String = if (NO_SPEECH in reply) "" else reply.trim()
    }
}
