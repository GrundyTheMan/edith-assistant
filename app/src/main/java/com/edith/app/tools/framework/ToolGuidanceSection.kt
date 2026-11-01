package com.edith.app.tools.framework

import com.edith.app.chat.engine.PromptContext
import com.edith.app.chat.engine.PromptSection

/**
 * The system prompt's tool section (C08 §4), generated from the tools actually offered, so it's always accurate
 * (v1's prompt promised tools it didn't have). Offered tools' own guidance lines follow, then a note for each
 * integration that isn't set up (C10 §3). A plain "can't look things up" line when the model can't use tools at all.
 */
class ToolGuidanceSection(private val registry: () -> ToolRegistry) : PromptSection {
    override fun text(ctx: PromptContext): String? {
        if (!ctx.model.canUseTools) return NO_TOOLS
        val reg = registry()
        val offered = reg.available(ctx.model)
        val lines = buildList {
            if (offered.isNotEmpty()) {
                add("You can use these tools when they help: " +
                    offered.joinToString { "${it.spec.name} (${it.spec.description.trimEnd('.').replaceFirstChar(Char::lowercase)})" } + ".")
                add(HOW_TO_USE)
                offered.mapNotNullTo(this) { it.guidance }
            }
            addAll(reg.notConnectedNotes())
        }
        return lines.joinToString("\n").ifEmpty { null }
    }

    companion object {
        const val NO_TOOLS = "You can't look things up right now; if asked for current information, say so briefly."
        const val HOW_TO_USE = "Use a tool instead of guessing whenever the answer depends on current or personal information. " +
            "After using tools, answer in your own words and mention where information came from when it's from the web."
    }
}
