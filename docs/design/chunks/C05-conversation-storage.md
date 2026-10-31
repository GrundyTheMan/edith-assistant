# C05 — Conversation storage (Room)

| | |
|---|---|
| Phase | 1 |
| Status | **Done** — see "As built" at the end |
| Depends on | C02 |
| Unblocks | C06, C07, C16 |
| Size | Small–medium |
| Read first | 01-architecture §5.1–5.2; 04-lessons L1, L3 |

## Goal

The complete v1 database (all tables, created now), DAOs, and a `ChatRepository` that enforces what
may and may not be stored.

## Specification

### 1. Gradle
KSP is already declared in the root build and version catalog (C01): apply `alias(libs.plugins.ksp)` in `app/build.gradle.kts` (it works with AGP 9's built-in Kotlin). Room runtime + ktx + compiler via **KSP** (not kapt, not annotationProcessor — lessons L1). Schema
export: `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`; commit `app/schemas/`.

### 2. Entities (exactly the schema in 01-architecture §5.1)
```kotlin
@Entity(tableName = "conversations") data class ConversationEntity(@PrimaryKey val id: Long, val title: String, @ColumnInfo(name = "created_at") val createdAt: Long)
@Entity(tableName = "messages", foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversation_id"], onDelete = CASCADE)],
        indices = [Index("conversation_id", "created_at")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    val role: MessageRole, val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val status: MessageStatus, val source: MessageSource,
    val model: String? = null,
    @ColumnInfo(name = "tools_used") val toolsUsed: List<String> = emptyList(),   // TypeConverter → JSON
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
)
@Entity(tableName = "attachments", foreignKeys = [ForeignKey(MessageEntity::class, ["id"], ["message_id"], onDelete = CASCADE)],
        indices = [Index("message_id"), Index("direction", "created_at"), Index(value = ["number"])])
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "message_id") val messageId: Long, val number: Int,
    val direction: AttachmentDirection, val kind: AttachmentKind,
    @ColumnInfo(name = "file_name") val fileName: String, @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long, @ColumnInfo(name = "stored_path") val storedPath: String?,
    @ColumnInfo(name = "resend_of") val resendOf: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long, @ColumnInfo(name = "removed_at") val removedAt: Long? = null,
)
enum class MessageRole { User, Assistant }            // stored as "user"/"assistant"
enum class MessageStatus { Complete, Failed, Cancelled }
enum class MessageSource { Typed, Dictated, VoiceMemo, HandsFree }
enum class AttachmentDirection { FromUser, FromEdith }
enum class AttachmentKind { Image, Audio, File }
```
TypeConverters store enums as lowercase snake strings (`"from_user"`, `"voice_memo"`) to match the SQL in
01-architecture. Database `EdithDatabase` version **1**, file `edith.db`, `exportSchema = true`.
On create: insert conversation `id = 1, title = "Edith"`.

### 3. DAOs
```kotlin
@Dao interface MessageDao {
    @Transaction @Query("SELECT * FROM messages WHERE conversation_id = :cid ORDER BY created_at, id")
    fun observeConversation(cid: Long): Flow<List<MessageWithAttachments>>
    @Query("SELECT * FROM messages WHERE conversation_id = :cid ORDER BY created_at DESC, id DESC LIMIT :limit")
    suspend fun recent(cid: Long, limit: Int): List<MessageWithAttachments>   // newest first; caller reverses
    @Insert suspend fun insert(m: MessageEntity): Long
    @Query("DELETE FROM messages WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM messages WHERE conversation_id = :cid") suspend fun clear(cid: Long)
}
@Dao interface AttachmentDao {
    @Insert suspend fun insert(a: AttachmentEntity): Long
    @Query("SELECT COALESCE(MAX(number), 0) + 1 FROM attachments a JOIN messages m ON a.message_id = m.id WHERE m.conversation_id = :cid")
    suspend fun nextNumber(cid: Long): Int
    @Query("SELECT * FROM attachments WHERE number = :number LIMIT 1") suspend fun byNumber(number: Int): AttachmentEntity?
    @Query("SELECT * FROM attachments WHERE direction = :direction AND stored_path IS NOT NULL ORDER BY created_at DESC")
    suspend fun storedNewestFirst(direction: AttachmentDirection): List<AttachmentEntity>
    @Query("UPDATE attachments SET stored_path = NULL, removed_at = :at WHERE id = :id") suspend fun markRemoved(id: Long, at: Long)
}
data class MessageWithAttachments(@Embedded val message: MessageEntity, @Relation(parentColumn = "id", entityColumn = "message_id") val attachments: List<AttachmentEntity>)
```

### 4. `ChatRepository` (`data/chat/`)
```kotlin
class ChatRepository(db: EdithDatabase, clock: Clock, dispatchers: AppDispatchers) {
    val conversationId = 1L
    fun observe(): Flow<List<MessageWithAttachments>>
    suspend fun recent(limit: Int): List<MessageWithAttachments>          // oldest first
    suspend fun addUserMessage(text: String, source: MessageSource): Long
    suspend fun addAssistantMessage(text: String, model: String, toolsUsed: List<String>): Long
    suspend fun addFailedAssistantMessage(model: String?, error: AppError): Long   // status Failed, content "" + error_message
    suspend fun deleteMessage(id: Long)
    suspend fun clearConversation()
}
```
Guards (enforce lessons L3 in code, not just convention): no API to store `system`/`tool` roles;
`addAssistantMessage` rejects text containing `<tool_call>` (strip it and log a warning).

## Tests (in-memory Room)
- Insert user + assistant messages → `observe()` emits in chronological order with attachments attached.
- `nextNumber` starts at 1 and increments across messages.
- Enum strings stored exactly as in the SQL (`"from_user"`, `"voice_memo"`) — query raw with `SupportSQLiteDatabase`.
- `addAssistantMessage` strips `<tool_call>…</tool_call>`.
- Deleting a message cascades to its attachments.
- Schema JSON for version 1 is exported to `app/schemas/`.

## Acceptance criteria
- [ ] Tests pass; schema exported and committed.
- [ ] App launches with the database created (inspect with `run-as` + `node:sqlite`; paste table list in the PR).

## Pitfalls
- KSP only. Keep `created_at` epoch millis from the injected `Clock`.

## As built (2026-10-31)
Started by another AI agent over three rounds; finished here. Its entities and DAO queries were kept; the rest was
corrected (Room's compiler was missing, a hand-made schema file, broken converters, a wrong `<tool_call>` guard).
- Room **2.8.5** via KSP (`ksp(libs.room.compiler)`), schema exported to
  `app/schemas/com.edith.app.data.db.EdithDatabase/1.json` (written by Room — never edit by hand).
- `EdithDatabase.create(context)` → `edith.db`, version 1; `EdithDatabaseCallback.onCreate` inserts conversation
  (1, "Edith"). Plain `@Insert` (no conflict strategy: `REPLACE` would silently delete a clashing row and, through
  the cascade, its attachments).
- `EnumConverters` write and read the SQL strings (`voice_memo`, `from_user`, …); `ListConverters` stores
  `tools_used` as a JSON array via kotlinx.serialization (no Gson); unreadable data reads as empty, with a warning.
- `ChatRepository` (`data/chat/`): the spec's API; all database work on `dispatchers.io`; `ChatRepository.stripToolCalls`
  removes `<tool_call>…</tool_call>` and logs a warning (lesson L3) — ordinary text is stored exactly as written.
- **Created at launch:** `EdithApp` calls `AppContainer.openDatabaseInBackground()` (new `appScope` on IO), so
  `edith.db` exists from the first launch without disk work on the main thread. (Without it nothing would open the
  database until C06.)
- Tests: `ChatRepositoryTest` (androidTest, in-memory Room, 9 tests: conversation row, order + attachments, recent,
  nextNumber 1→2→3, raw enum strings, `<tool_call>` stripping + tools_used JSON, failed replies, cascade delete,
  clear keeps the conversation) and `StorageRulesTest` (JVM: stripping, converters, schema file).
- Verified on the emulator: launch logs "Database ready"; `edith.db` read with `node:sqlite` →
  tables `attachments, conversations, messages`; conversations `[{id:1, title:"Edith", ...}]`; user_version 1.
