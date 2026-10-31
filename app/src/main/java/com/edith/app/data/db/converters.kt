package com.edith.app.data.db

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Converts enums to/from lowercase snake_case strings for Room.
 */
object EnumConverters {

    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = role.name.lowercase()

    @TypeConverter
    fun toMessageRole(value: String): MessageRole = when (value.lowercase()) {
        "user" -> MessageRole.User
        "assistant" -> MessageRole.Assistant
        else -> throw IllegalArgumentException("Unknown message role: $value")
    }

    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name.lowercase()

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = when (value.lowercase()) {
        "complete" -> MessageStatus.Complete
        "failed" -> MessageStatus.Failed
        "cancelled" -> MessageStatus.Cancelled
        else -> throw IllegalArgumentException("Unknown message status: $value")
    }

    @TypeConverter
    fun fromMessageSource(source: MessageSource): String = source.name.lowercase()

    @TypeConverter
    fun toMessageSource(value: String): MessageSource = when (value.lowercase()) {
        "typed" -> MessageSource.Typed
        "dictated" -> MessageSource.Dictated
        "voice_memo" -> MessageSource.VoiceMemo
        "hands_free" -> MessageSource.HandsFree
        else -> throw IllegalArgumentException("Unknown message source: $value")
    }

    @TypeConverter
    fun fromAttachmentDirection(direction: AttachmentDirection): String = direction.name.lowercase()

    @TypeConverter
    fun toAttachmentDirection(value: String): AttachmentDirection = when (value.lowercase()) {
        "from_user" -> AttachmentDirection.FromUser
        "from_edith" -> AttachmentDirection.FromEdith
        else -> throw IllegalArgumentException("Unknown attachment direction: $value")
    }

    @TypeConverter
    fun fromAttachmentKind(kind: AttachmentKind): String = kind.name.lowercase()

    @TypeConverter
    fun toAttachmentKind(value: String): AttachmentKind = when (value.lowercase()) {
        "image" -> AttachmentKind.Image
        "audio" -> AttachmentKind.Audio
        "file" -> AttachmentKind.File
        else -> throw IllegalArgumentException("Unknown attachment kind: $value")
    }
}

/**
 * Converts JSON strings to/from lists of strings for the tools_used field.
 */
object JsonConverters {

    private val gson = Gson()

    @TypeConverter
    fun fromJsonStringList(jsonString: String?): List<String>? {
        return if (jsonString == null || jsonString.isBlank()) null else {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(jsonString, type)
        }
    }

    @TypeConverter
    fun toJsonStringList(list: List<String>?): String? {
        return list?.let { gson.toJson(it) } ?: null
    }
}
