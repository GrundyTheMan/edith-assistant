package com.edith.app.jarvis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire types of Jarvis's "Edith feed" (C38; the contract is `docs/design/jarvis-feed-spec.md` part 7). Unknown
 * fields are ignored, so Jarvis may add fields without breaking Edith; times are milliseconds since 1970 UTC.
 */
val feedJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

@Serializable data class Hello(val app: String = "", val version: Int = 0)

@Serializable data class PairRequest(val name: String, val proof: String)

@Serializable data class PairReply(val token: String, val deviceId: String = "", val pcName: String = "", val version: Int = 1)

@Serializable data class FeedErrorBody(val code: String = "", val message: String = "")

@Serializable data class FeedErrorReply(val error: FeedErrorBody = FeedErrorBody())

@Serializable data class SharedMail(val id: String, val label: String = "", val email: String = "")

@Serializable data class SharedCalendar(val id: String, val label: String = "", val color: String? = null)

@Serializable data class SharedTasks(val id: String, val label: String = "")

/** `GET /v1/shared`: what the owner ticked in Jarvis (spec 7.2). */
@Serializable data class Shared(
    val pcName: String = "",
    val mail: List<SharedMail> = emptyList(),
    val calendars: List<SharedCalendar> = emptyList(),
    val tasks: List<SharedTasks> = emptyList(),
    /** The 3D printer (C39): absent when the owner doesn't share it, or Jarvis is too old to know it. */
    val printer: com.edith.app.printer.feed.SharedPrinter? = null,
)

@Serializable data class Page<T>(val items: List<T> = emptyList(), val more: Boolean = false)

@Serializable data class WireAddress(val name: String = "", val address: String = "")

@Serializable data class WireMail(
    val id: String,
    val account: String = "",
    val from: WireAddress = WireAddress(),
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String = "",
    val date: Long = 0,
    val unread: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val toMe: String = "",
    val preview: String = "",
    val score: Int? = null,
)

@Serializable data class WireAttachment(val filename: String = "", val size: Long = 0)

@Serializable data class WireMailBody(
    val message: WireMail,
    val text: String = "",
    val truncated: Boolean = false,
    val attachments: List<WireAttachment> = emptyList(),
)

@Serializable data class WireEvent(
    val id: String,
    val calendar: String = "",
    val title: String = "",
    val start: Long,
    val end: Long,
    val allDay: Boolean = false,
    val location: String? = null,
    val description: String? = null,
    val recurring: Boolean = false,
    val joinUrl: String? = null,
    val organizer: String? = null,
    val myResponse: String? = null,
    val showAs: String? = null,
    val cancelled: Boolean = false,
)

@Serializable data class WireTaskList(val id: String, val title: String = "")

@Serializable data class WireTask(
    val id: String,
    val title: String = "",
    val notes: String? = null,
    val due: String? = null,
    val done: Boolean = false,
    val completed: Long? = null,
    val importance: String? = null,
)

/** `POST /v1/mail/drafts` (spec 7.5): a reply to [replyTo], or a new email from [account]. */
@Serializable data class DraftRequest(
    val replyTo: String? = null,
    val text: String? = null,
    val account: String? = null,
    val to: String? = null,
    val subject: String? = null,
    val body: String? = null,
)

@Serializable data class DraftSaved(val saved: Boolean = false, val note: String = "")
