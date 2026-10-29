# C10 — Web search and web page reading

| | |
|---|---|
| Phase | 2 |
| Depends on | C08 (and C02 `SecretStore`, C04 settings) |
| Size | Medium |
| Read first | C08; 06-decisions **D3** (ask the owner first); 04-lessons L20 |

## Goal

"Is there a Pistons game tonight?" → Edith searches the web, reads the most relevant result if needed,
and answers with the source. v1 used DuckDuckGo's instant-answer API, which returns nothing for most real
questions (L20). This chunk adds a provider interface plus the provider the owner chooses.

## Before you start
Ask the owner decision **D3** (provider). Recommended: Ollama's web search API. Alternatives: Brave Search
API, self-hosted SearXNG on the PC, Tavily. Most need an API key: the owner obtains it; the app stores it
via `SecretStore` (never in source, logs or the repo). Verify the chosen API's **current** request/response
format and limits from its official docs and capture a real response as a fixture — don't rely on memory.

## Specification

### 1. Provider interface (`tools/web/`)
```kotlin
interface SearchProvider {
    val id: String; val displayName: String; val needsApiKey: Boolean
    suspend fun search(query: String, maxResults: Int = 5): Outcome<List<SearchHit>>
}
data class SearchHit(val title: String, val url: String, val snippet: String, val published: String? = null)
```
Implement the chosen provider (plus, optionally, a second one). Network errors → `AppError`; 401/403 →
"The search API key was rejected — check it in Settings."; 429 → "Search limit reached — try later."

### 2. Tools
- `search_web(query: String, max_results: Integer = 5)` → numbered list, each `1. Title — snippet (url)`,
  ≤ 1,500 chars total. Status "Searching the web…".
- `read_web_page(url: String)` → fetch with OkHttp (10 s timeout, max 2 MB, `User-Agent: EdithAssistant/2 (Android)`),
  extract readable text with Jsoup (drop script/style/nav/footer/aside; prefer `article`/`main`), collapse
  whitespace, return title + first **6,000 chars**. Only `http(s)` URLs; refuse private-network addresses
  (`10.*`, `192.168.*`, `172.16–31.*`, `127.*`, `localhost`) — the model must not probe the LAN.
  Status "Reading <host>…".
- Tool guidance line (C08 section) adds: "For questions about current events, sports, prices or anything that
  changes, search first; read a page if the snippets aren't enough; mention the source site."

### 3. Settings (Web search section)
Provider picker (from implemented providers), API key field (masked; saved via `SecretStore`; **Test**
button runs a sample query "weather" and shows OK/error). If no key is set for a provider that needs one,
`search_web` is **not offered** and the prompt says web search isn't set up (L6 — no broken tools).

## Tests
- Provider response parsing from the captured real fixture; error codes mapped.
- `read_web_page`: HTML fixture → expected text; script/nav removed; size cap; private address refused; non-HTML content type refused.
- Tool not offered without a key; offered with one.
- SecretStore used for the key (fake in tests); key never appears in logs (test the logger output).

## Acceptance criteria
- [ ] Live: "Is there a Pistons game tonight?" and "What's the latest on <current news topic>?" produce answers that
      cite a real site; the app log shows `search_web` (and `read_web_page` when used). Paste in the PR.
- [ ] Wrong key → a clear message in Settings' Test and from Edith.
- [ ] No key/secret in the repo, logs or crash reports.
