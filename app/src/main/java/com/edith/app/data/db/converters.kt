package com.edith.app.data.db

import androidx.room.TypeConverter
import org.json.JSONArray
import java.util.ArrayList

/**
 * Converts enums to/from snake_case strings for Room (to match SQL enum values).
 */
object EnumConverters {

    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = when (role) {
        MessageRole.User -> "user"
        MessageRole.Assistant -> "assistant"
    }

    @TypeConverter
    fun toMessageRole(value: String): MessageRole = when (value) {
        "user" -> MessageRole.User
        "assistant" -> MessageRole.Assistant
        else -> throw IllegalArgumentException("Unknown message role: $value")
    }

    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = when (status) {
        MessageStatus.Complete -> "complete"
        MessageStatus.Failed -> "failed"
        MessageStatus.Cancelled -> "cancelled"
    }

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = when (value) {
        "complete" -> MessageStatus.Complete
        "failed" -> MessageStatus.Failed
        "cancelled" -> MessageStatus.Cancelled
        else -> throw IllegalArgumentException("Unknown message status: $value")
    }

    @TypeConverter
    fun fromMessageSource(source: MessageSource): String = when (source) {
        MessageSource.Typed -> "typed"
        MessageSource.Dictated -> "dictated"
        MessageSource.VoiceMemo -> "voice_memo"
        MessageSource.HandsFree -> "hands_free"
    }

    @TypeConverter
    fun toMessageSource(value: String): MessageSource = when (value) {
        "typed" -> MessageSource.Typed
        "dictated" -> MessageSource.Dictated
        "voice_memo" -> MessageSource.VoiceMemo
        "hands_free" -> MessageSource.HandsFree
        else -> throw IllegalArgumentException("Unknown message source: $value")
    }

    @TypeConverter
    fun fromAttachmentDirection(direction: AttachmentDirection): String = when (direction) {
        AttachmentDirection.FromUser -> "from_user"
        AttachmentDirection.FromEdith -> "from_edith"
    }

    @TypeConverter
    fun toAttachmentDirection(value: String): AttachmentDirection = when (value) {
        "from_user" -> AttachmentDirection.FromUser
        "from_edith" -> AttachmentDirection.FromEdith
        else -> throw IllegalArgumentException("Unknown attachment direction: $value")
    }

    @TypeConverter
    fun fromAttachmentKind(kind: AttachmentKind): String = when (kind) {
        AttachmentKind.Image -> "image"
        AttachmentKind.Audio -> "audio"
        AttachmentKind.File -> "file"
    }

    @TypeConverter
    fun toAttachmentKind(value: String): AttachmentKind = when (value) {
        "image" -> AttachmentKind.Image
        "audio" -> AttachmentKind.Audio
        "file" -> AttachmentKind.File
        else -> throw IllegalArgumentException("Unknown attachment kind: $value")
    }
}

/**
 * Converts JSON strings to/from lists of strings.
 */
object ListConverters {

    @TypeConverter
    fun fromStringList(jsonString: String?): List<String>? {
        return if (jsonString == null || jsonString.isBlank()) null else {
            try {
                val arr = JSONArray(jsonString)
                (0 until arr.length()).map { arr.getString(it) }.toList()
            } catch (e: Exception) {
                null // Fail gracefully if JSON is malformed
            }
        }
    }

    @TypeConverter
    fun toStringList(list: List<String>?): String? {
        return list?.let {
            try {
                JSONArray().apply {
                    for (item in list) put(item)
                }.toString()
            } catch (e: Exception) {
                null
            }
        }
    }
}
