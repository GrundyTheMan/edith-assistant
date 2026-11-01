package com.edith.app.tools.framework

import com.edith.app.chat.engine.PromptContext
import com.edith.app.chat.engine.PromptSection

/**
 * The system prompt's tool section (C08 §4), generated from the tools actually offered, so it's always accurate
 * (v1's prompt promised tools it didn't have). Nothing when no tools are offered; a plain "can't look things up"
 * line when the model can't use tools at all.
 */
class ToolGuidanceSection(private val registry: () -> ToolRegistry) : PromptSection {
    override fun text(ctx: PromptContext): String? {
        if (!ctx.model.canUseTools) return NO_TOOLS
        val offered = registry().available(ctx.model).ifEmpty { return null }
        val list = offered.joinToString { "${it.spec.name} (${it.spec.description.trimEnd('.').replaceFirstChar(Char::lowercase)})" }
        return "You can use these tools when they help: $list.\n$HOW_TO_USE"
    }

    companion object {
        const val NO_TOOLS = "You can't look things up right now; if asked for current information, say so briefly."
        const val HOW_TO_USE = "Use a tool instead of guessing whenever the answer depends on current or personal information. " +
            "After using tools, answer in your own words and mention where information came from when it's from the web."
    }
}
