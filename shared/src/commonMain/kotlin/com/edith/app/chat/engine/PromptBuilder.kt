package com.edith.app.chat.engine

import com.edith.app.core.time.Clock
import com.edith.app.ollama.ModelInfo
import com.edith.app.core.time.formatPattern
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlin.time.Instant

/**
 * Edith's personality — 00-product §5, verbatim, as the owner approved it. Don't edit it; capabilities belong in
 * [PromptSection]s, which describe only what's actually available.
 */
const val PERSONA = """You are Edith, a highly sophisticated, warm, and genuinely complimentary personal assistant.
Your goal is to assist the user with intelligence and flair, acting as a true digital partner.
Your communication must be warm and flattering, but you are refreshingly honest—never sugarcoat difficult truths or exaggerate for effect.
You possess a sharp sense of humor and enjoy using idioms, metaphors, and appropriate pop culture references to make conversations feel natural and engaging.
Always maintain your warm and intelligent demeanor, even when dealing with complex technical issues.
If a request is ambiguous or lacks necessary detail, you must ask for clarification with style before attempting any action.
Never use generic AI phrasing. Speak like a genuinely competent companion, not a machine.
Be concise: this is a chat on a phone screen. Most replies should be two to four sentences, and lists should stay short. Go longer only when the user asks for detail or the task genuinely needs it, such as a step-by-step plan. Let your warmth and wit come through in your word choice, not in extra length."""

/** One capability paragraph of the system prompt (01-architecture §6). Return null when the feature is off. */
fun interface PromptSection {
    fun text(ctx: PromptContext): String?
}

/** [hasAttachments]: the messages sent with this prompt include attachments (C15). */
data class PromptContext(
    val now: Instant,
    val model: ModelInfo,
    val hasAttachments: Boolean = false,
    /** The owner's time zone, which [now] is shown in. */
    val zone: TimeZone = TimeZone.UTC,
)

/** Builds the system prompt fresh for every request: [PERSONA], a blank line, then each active section. */
class PromptBuilder(private val clock: Clock, private val sections: () -> List<PromptSection>) {
    fun system(model: ModelInfo, hasAttachments: Boolean = false): String {
        val ctx = PromptContext(clock.now(), model, hasAttachments, clock.zone())
        val active = sections().mapNotNull { it.text(ctx)?.takeIf(String::isNotBlank) }
        return if (active.isEmpty()) PERSONA else PERSONA + "\n\n" + active.joinToString("\n")
    }
}

/** "Current date and time: Thursday, October 29, 2026, 2:30 PM (America/Detroit)." (C06), in the device's language. */
class DateTimeSection(private val languageTag: () -> String? = { null }) : PromptSection {
    override fun text(ctx: PromptContext): String {
        val formatted = formatPattern(ctx.now, ctx.zone, "EEEE, MMMM d, yyyy, h:mm a", languageTag())
        // The offset too (C18): without it gemma4 guessed -04:00 the day after daylight saving ended.
        return "Current date and time: $formatted (${ctx.zone.id}, UTC${ctx.zone.offsetAt(ctx.now).toString().replace("Z", "+00:00")})."
    }
}
