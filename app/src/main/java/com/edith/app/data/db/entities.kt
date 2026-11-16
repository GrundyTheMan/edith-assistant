package com.edith.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo

/**
 * A chat thread (decision D6: many conversations). [title] is "" until one is set; [titleSource] says who set it,
 * so a name the owner chose is never replaced (D12). [updatedAt] is the newest message time: the list sorts by it.
 * The id stays a plain INTEGER PRIMARY KEY (the v1 shape), so the v1→v2 migration only adds columns and never
 * rebuilds a table that messages reference with ON DELETE CASCADE. New rows get their id from SQLite.
 */
@Entity(tableName = "conversations", indices = [Index("updated_at")])
data class ConversationEntity(
    @PrimaryKey val id: Long,
    val title: String,
    @ColumnInfo(name = "title_source", defaultValue = "none") val titleSource: TitleSource,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long,
)

/** Who titled a conversation (D12): nobody yet, the first message (provisional), Edith, or the owner (final). */
enum class TitleSource { None, FirstMessage, Edith, Owner }

/**
 * Message entity - represents a chat message (user or assistant).
 */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversation_id", "created_at")]
)
/** A chat message as stored in Room (C05). */
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    val role: MessageRole,
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val status: MessageStatus,
    val source: MessageSource,
    val model: String? = null,
    @ColumnInfo(name = "tools_used") val toolsUsed: List<String> = emptyList(),
    @ColumnInfo(name = "error_message") val errorMessage: String? = null
)

/**
 * Attachment entity - represents a media/file attachment to a message.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(MessageEntity::class, ["id"], ["message_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("message_id"), Index("direction", "created_at"), Index(value = ["number"])]
)
/** An attachment's record in Room; the file itself is in the attachment store (C16). */
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "message_id") val messageId: Long,
    val number: Int,
    val direction: AttachmentDirection,
    val kind: AttachmentKind,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "stored_path") val storedPath: String?,
    @ColumnInfo(name = "resend_of") val resendOf: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "removed_at") val removedAt: Long? = null
)

/**
 * Enums stored as snake_case strings in Room.
 */
enum class MessageRole { User, Assistant }
/** How a message ended: written in full, failed, or stopped by the owner. */
enum class MessageStatus { Complete, Failed, Cancelled }
/** How the owner's message got into the chat: typed, dictated, a voice memo or hands-free. */
enum class MessageSource { Typed, Dictated, VoiceMemo, HandsFree }
/** Which way an attachment went: from the owner, or from Edith. */
enum class AttachmentDirection { FromUser, FromEdith }
/** What an attachment is: a photo, audio or a file. */
enum class AttachmentKind { Image, Audio, File }
