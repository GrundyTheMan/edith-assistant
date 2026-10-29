package com.edith.app.network

import org.json.JSONObject
import org.json.JSONException

/**
 * A defensive gateway for tool calls.
 * Inspired by Jarvis, this utility repairs common mistakes made by LLMs when
 * calling tools (typos, wrong parameter names, messy JSON).
 */
object ToolRepair {

    private val TOOL_ALIASES = mapOf(
        "read_file" to listOf("read", "cat", "open_file", "view_file", "view", "get_file", "get_file_contents", "read_text_file", "file_read"),
        "list_dir" to listOf("ls", "list", "list_files", "list_directory", "listdir", "dir", "list_folder"),
        "glob" to listOf("find", "find_files", "search_files", "file_search", "find_file", "glob_files"),
        "grep" to listOf("search", "grep_search", "search_code", "code_search", "ripgrep", "rg", "search_text", "find_in_files"),
        "edit_file" to listOf("edit", "replace", "str_replace", "str_replace_editor", "apply_edit", "replace_in_file", "modify_file", "update_file", "file_edit", "search_replace"),
        "multi_edit" to listOf("multiedit", "edit_multiple", "multi_replace", "batch_edit", "apply_edits", "edit_many", "multi_str_replace"),
        "write_file" to listOf("write", "create_file", "save_file", "overwrite_file", "file_write", "new_file"),
        "run_command" to listOf("bash", "shell", "powershell", "execute", "execute_command", "run", "run_shell", "run_shell_command", "run_terminal_command", "terminal", "exec", "cmd", "command"),
        "todo_write" to listOf("todo", "todos", "update_todos", "write_todos", "task_list", "todowrite", "update_todo", "set_todos"),
        "web_search" to listOf("search_web", "websearch", "google", "internet_search", "duckduckgo"),
        "web_fetch" to listOf("fetch", "fetch_url", "open_url", "browse", "webfetch", "read_url", "get_url", "fetch_page"),
        "explore" to listOf("research", "investigate", "ask_helper", "subagent", "delegate"),
        "get_emails" to listOf("emails", "get_emails", "read_emails", "fetch_emails", "list_emails"),
        "transcribe_recording" to listOf("transcribe", "transcription", "speech_to_text", "audio_to_text", "transcribe_audio")
    )

    private val KEY_ALIASES = mapOf(
        "path" to listOf("file_path", "filepath", "file", "filename", "file_name", "target_file", "directory", "dir", "folder", "relative_path", "dir_path"),
        "old_string" to listOf("old", "old_str", "old_text", "search", "original", "target", "before", "old_content", "find"),
        "new_string" to listOf("new", "new_str", "new_text", "replace", "replacement", "after", "new_content"),
        "content" to listOf("contents", "text", "data", "file_text", "body", "code", "file_content"),
        "command" to listOf("cmd", "script", "command_line", "shell", "commands"),
        "pattern" to listOf("regex", "query", "glob_pattern", "search", "expression", "search_pattern", "name"),
        "query" to listOf("q", "search_query", "search_term", "keywords", "term", "text"),
        "url" to listOf("link", "href", "uri", "address", "page"),
        "task" to listOf("question", "prompt", "query", "description", "request"),
        "plan" to listOf("content", "text", "markdown", "plan_text", "steps"),
        "todos" to listOf("items", "tasks", "todo_list", "list", "todo"),
        "offset" to listOf("start_line", "line", "from_line", "start", "line_start", "begin"),
        "limit" to listOf("num_lines", "lines", "count", "max_lines", "line_count", "n"),
        "case_insensitive" to listOf("ignore_case", "insensitive", "nocase", "i"),
        "replace_all" to listOf("all", "global", "replace_every"),
        "timeout_seconds" to listOf("timeout", "timeout_sec", "seconds", "time_limit"),
        "wait_seconds" to listOf("wait", "wait_time", "timeout"),
        "id" to listOf("job_id", "job", "background_id", "task_id"),
        "cwd" to listOf("working_directory", "workdir", "directory", "dir", "folder"),
        "glob" to listOf("include", "file_pattern", "files", "file_glob"),
        "edits" to listOf("changes", "replacements", "edit_list", "operations", "diffs")
    )

    private fun normalizeKotlin(s: String): String {
        return s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[\\s.\\-]+"), "_")
            .replace(Regex("_+"), "_")
            .replace(Regex("^_|_$"), "")
    }

    private fun stripPrefix(s: String): String {
        return s.replace(Regex("^(functions|function|tools|tool|default_api|api)[.:]", RegexOption.IGNORE_CASE), "")
    }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            d[i][0] = i
            for (j in 1..b.length) {
                d[i][j] = if (d[i - 1][j] + 1 < d[i][j - 1] + 1) {
                    d[i - 1][j] + 1
                } else if (d[i][j - 1] + 1 < d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1) {
                    d[i][j - 1] + 1
                } else {
                    d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                }
            }
        }
        return d[a.length][b.length]
    }

    fun resolveToolName(raw: String, availableNames: List<String>): String? {
        if (availableNames.contains(raw)) return raw
        
        val want = normalizeKotlin(stripPrefix(raw.trim()))
        val exact = availableNames.find { normalizeKotlin(it) == want }
        if (exact != null) return exact
        
        for ((tool, aliases) in TOOL_ALIASES) {
            if (availableNames.contains(tool) && aliases.contains(want)) return tool
        }
        
        if (want.length < 4) return null
        
        val scored = availableNames.map { n ->
            val dist = distance(want, normalizeKotlin(n))
            Pair(n, dist)
        }.sortedBy { it.second }
        
        val best = scored.firstOrNull()
        if (best != null && best.second <= (if (want.length >= 8) 2 else 1)) {
            return best.first
        }
        
        return null
    }

    fun repairCall(rawToolCall: ToolCall, availableTools: List<String>, expectedArgs: List<String>): ToolCall? {
        val repairedName = resolveToolName(rawToolCall.function, availableTools)
        if (repairedName == null) return null
        
        val repairedArgs = fitArguments(rawToolCall.arguments, expectedArgs)
        return ToolCall(repairedName, repairedArgs)
    }

    fun fitArguments(inputArgs: Map<String, String>, expectedNames: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val leftover = mutableMapOf<String, String>()
        
        for ((k, v) in inputArgs) {
            if (expectedNames.contains(k)) {
                result[k] = v
            } else {
                leftover[k] = v
            }
        }
        
        for ((k, v) in leftover) {
            val target = expectedNames.find { 
                !(it in result) && (KEY_ALIASES[it]?.contains(normalizeKotlin(k)) == true) 
            }
            if (target != null) {
                result[target] = v
            } else {
                result[k] = v 
            }
        }
        
        return result
    }

    /**
     * Attempts to parse a JSON string which might be "loose" (e.g. contains tabs or extra commas).
     */
    fun parseLooseJson(json: String): JSONObject? {
        return try {
            JSONObject(json)
        } catch (e: JSONException) {
            // Try to strip common LLM artifacts: trailing commas, and tabs/newlines in strings
            val cleaned = json
                .replace(Regex(",\\s*([}\\]])"), "$1") // remove trailing commas
                .replace("\\t", " ")                 // replace literal tabs
                .replace("\\n", " ")                 // replace literal newlines
                .trim()
            try {
                JSONObject(cleaned)
            } catch (e2: JSONException) {
                null
            }
        }
    }
}
