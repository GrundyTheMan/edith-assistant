package com.edith.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo

/**
 * Conversation entity - represents a single chat thread.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: Long,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * Message entity - represents a chat message (user or assistant).
 */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversation_id", "created_at")]
)
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
 * Enums stored as lowercase snake_case strings in Room.
 */
enum class MessageRole { User, Assistant }
enum class MessageStatus { Complete, Failed, Cancelled }
enum class MessageSource { Typed, Dictated, VoiceMemo, HandsFree }
enum class AttachmentDirection { FromUser, FromEdith }
enum class AttachmentKind { Image, Audio, File }
