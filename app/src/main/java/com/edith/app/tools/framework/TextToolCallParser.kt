package com.edith.app.tools.framework

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Fallback for tool calls a model wrote as text instead of a real `tool_calls` entry (C08 §7, 03-ollama-reference
 * §4.2). Recognises gemma's `<tool_call>name{key:<|"|>value<|"|>}</tool_call>`, `<tool_call>{"name":…,"arguments":…}</tool_call>`,
 * `[TOOL_CALLS][{…}]`, and a reply that is nothing but `{"name":…,"arguments":…}`. Plain prose gives nothing.
 * Used only when a round returned no structured calls; every use is logged, because it points at a prompt or
 * history problem.
 */
object TextToolCallParser {
    data class ParsedCall(val name: String, val arguments: JsonObject)

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val tagged = Regex("""(?s)<tool_call>\s*(.*?)\s*(?:</tool_call>|$)""")
    // Braces escaped: Android's ICU regex rejects a bare `}` that the JVM accepts (this crashed the app once; L25).
    private val gemmaCall = Regex("""(?s)^([A-Za-z_][\w.\-]*)\s*(\{.*\})\s*$""")
    private val bareKey = Regex("""([{,]\s*)([A-Za-z_]\w*)\s*:""")

    fun extract(text: String): List<ParsedCall> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if ("<tool_call>" in trimmed) return tagged.findAll(trimmed).mapNotNull { parseOne(it.groupValues[1]) }.toList()
        if (trimmed.startsWith("[TOOL_CALLS]")) {
            val body = parse(trimmed.removePrefix("[TOOL_CALLS]").trim()) ?: return emptyList()
            return (body as? JsonArray ?: JsonArray(listOf(body))).mapNotNull { (it as? JsonObject)?.let(::fromObject) }
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return listOfNotNull((parse(trimmed) as? JsonObject)?.let(::fromObject))
        return emptyList()
    }

    private fun parseOne(body: String): ParsedCall? {
        val unquoted = body.replace("<|\"|>", "\"")
        if (unquoted.startsWith("{")) return (parse(unquoted) as? JsonObject)?.let(::fromObject)
        val m = gemmaCall.matchEntire(unquoted) ?: return null
        // Lenient JSON reads gemma's unquoted keys as they are; quoting them first is the fallback.
        val args = parse(m.groupValues[2]) as? JsonObject
            ?: parse(bareKey.replace(m.groupValues[2]) { "${it.groupValues[1]}\"${it.groupValues[2]}\":" }) as? JsonObject
            ?: return null
        return ParsedCall(m.groupValues[1], args)
    }

    private fun fromObject(o: JsonObject): ParsedCall? {
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val args = (o["arguments"] ?: o["parameters"]).let { a ->
            when (a) {
                is JsonObject -> a
                is JsonPrimitive -> a.contentOrNull?.let { parse(it) as? JsonObject }   // arguments as a JSON string
                else -> null
            }
        } ?: JsonObject(emptyMap())
        return ParsedCall(name, args)
    }

    private fun parse(text: String): JsonElement? = try { json.parseToJsonElement(text) } catch (_: Exception) { null }
}
