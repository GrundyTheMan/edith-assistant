package com.edith.app.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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

    @TypeConverter
    fun fromTitleSource(source: TitleSource): String = when (source) {
        TitleSource.None -> "none"
        TitleSource.FirstMessage -> "first_message"
        TitleSource.Edith -> "edith"
        TitleSource.Owner -> "owner"
    }

    @TypeConverter
    fun toTitleSource(value: String): TitleSource = when (value) {
        "none" -> TitleSource.None
        "first_message" -> TitleSource.FirstMessage
        "edith" -> TitleSource.Edith
        "owner" -> TitleSource.Owner
        else -> throw IllegalArgumentException("Unknown title source: $value")
    }
}

/** `tools_used` as a JSON array of strings, via kotlinx.serialization (no Gson — 01-architecture §1). */
object ListConverters {
    /** Told when a stored list can't be read (it is read as empty): the app logs it. (Logging itself moves to shared code in K1.6.) */
    var onUnreadable: (Throwable) -> Unit = {}

    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromStringList(json: String): List<String> = try {
        Json.decodeFromString(serializer, json)
    } catch (e: SerializationException) {
        onUnreadable(e)
        emptyList()
    }

    @TypeConverter
    fun toStringList(list: List<String>): String = Json.encodeToString(serializer, list)
}
