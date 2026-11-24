package com.edith.app.tools.framework

import com.edith.app.ollama.ModelInfo

/**
 * The tools Edith has (C08 §3). Offers only built-in tools and tools of integrations that are connected (L6), and
 * none at all to a model without the "tools" capability.
 */
class ToolRegistry(private val tools: List<Tool>, private val integrations: List<Integration> = emptyList()) {
    init {
        require(tools.map { it.spec.name }.toSet().size == tools.size) { "Tool names must be unique" }
    }

    /** Tools to offer this model right now. */
    fun available(model: ModelInfo): List<Tool> {
        if (!model.canUseTools) return emptyList()
        val connected = integrations.filter { it.status.value == IntegrationStatus.Connected }.map { it.id }.toSet()
        return tools.filter { it.offered() && (it.integrationId == null || it.integrationId in connected) }
    }

    /**
     * The offered tool a model meant: the exact name, then ignoring case, '-', '.' and a "functions." prefix
     * (`functions.get_weather`, `Get-Weather`), then one typo for names of 8+ characters (`get_wether`).
     */
    fun resolve(name: String, offered: List<Tool>): Tool? {
        offered.firstOrNull { it.spec.name == name }?.let { return it }
        val wanted = normalize(name)
        offered.firstOrNull { normalize(it.spec.name) == wanted }?.let { return it }
        return offered.filter { it.spec.name.length >= 8 && editDistanceAtMostOne(normalize(it.spec.name), wanted) }.singleOrNull()
    }

    /** What the model should know about integrations that aren't connected (e.g. "Web search isn't set up…"). */
    fun notConnectedNotes(): List<String> =
        integrations.filter { it.status.value != IntegrationStatus.Connected }.mapNotNull { it.notConnectedNote }

    /** What the model should know about connected ones ("Google: connected as …"), given the tools each has (C17). */
    fun connectedNotes(): List<String> =
        integrations.filter { it.status.value == IntegrationStatus.Connected }
            .mapNotNull { i -> i.connectedNote(tools.filter { it.integrationId == i.id }) }

    /** For the "Used: …" line: "get_current_time" → "time". Unknown names are shown as they are. */
    fun displayName(toolName: String): String = tools.firstOrNull { it.spec.name == toolName }?.displayName ?: toolName

    private companion object {
        fun normalize(name: String) = name.trim().removePrefix("functions.").lowercase().replace('-', '_').replace('.', '_')

        fun editDistanceAtMostOne(a: String, b: String): Boolean {
            if (a == b) return true
            if (kotlin.math.abs(a.length - b.length) > 1) return false
            var i = 0
            var j = 0
            var edits = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) { i++; j++; continue }
                if (++edits > 1) return false
                when {
                    a.length > b.length -> i++
                    a.length < b.length -> j++
                    else -> { i++; j++ }
                }
            }
            return edits + (a.length - i) + (b.length - j) <= 1
        }
    }
}
