package com.edith.app.google.calendar

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.google.GoogleAuth
import com.edith.app.google.GoogleCall
import com.edith.app.google.GoogleScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A calendar the owner has checked in Google Calendar (C18; the owner chose all of them, not only the primary). */
data class CalendarInfo(val id: String, val name: String, val primary: Boolean)

/** One event, in the device's zone. All-day events have [allDay] and run from [start] (midnight) to [end] (exclusive). */
data class CalendarEvent(
    val id: String,
    val calendarId: String,
    val calendarName: String,
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    val location: String? = null,
    val description: String? = null,
)

/** A new event, or the changes to one ([CalendarClient.patch]): only the fields that are set are sent. */
data class EventChange(
    val title: String? = null,
    val start: ZonedDateTime? = null,
    val end: ZonedDateTime? = null,
    val allDay: Boolean = false,
    val location: String? = null,
    val description: String? = null,
)

/**
 * Google Calendar over its REST API (C18 §1), with OkHttp and a token from [GoogleAuth] for each call. Times go out as
 * RFC 3339 with an offset and come back in the device's zone; all-day events use `date`.
 */
class CalendarClient(
    private val http: Lazy<OkHttpClient>,
    private val google: GoogleAuth,
    private val dispatchers: AppDispatchers,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val baseUrl: String = BASE_URL,
) {
    /** The calendars checked in Google Calendar, primary first. */
    suspend fun calendars(): Outcome<List<CalendarInfo>> = call(setOf(GoogleScope.CalendarList)) { token ->
        get(token, url("users/me/calendarList") { addQueryParameter("minAccessRole", "reader") }) { body ->
            json.decodeFromString<CalendarListDto>(body).items
                .filter { it.selected == true || it.primary == true }
                .map { CalendarInfo(it.id, it.summaryOverride ?: it.summary ?: it.id, it.primary == true) }
                .sortedByDescending { it.primary }
        }
    }

    /** Events in [from, to) across [calendars], in start order (recurring events as their instances). */
    suspend fun events(calendars: List<CalendarInfo>, from: ZonedDateTime, to: ZonedDateTime, query: String? = null): Outcome<List<CalendarEvent>> {
        val all = mutableListOf<CalendarEvent>()
        for (cal in calendars) {
            val r = call(setOf(GoogleScope.CalendarEvents)) { token ->
                get(token, url("calendars/${enc(cal.id)}/events") {
                    addQueryParameter("timeMin", rfc3339(from))
                    addQueryParameter("timeMax", rfc3339(to))
                    addQueryParameter("singleEvents", "true")
                    addQueryParameter("orderBy", "startTime")
                    addQueryParameter("maxResults", MAX_RESULTS.toString())
                    if (!query.isNullOrBlank()) addQueryParameter("q", query)
                }) { body -> json.decodeFromString<EventsDto>(body).items.mapNotNull { it.toEvent(cal) } }
            }
            when (r) {
                is Outcome.Success -> all += r.value
                is Outcome.Failure -> return r
            }
        }
        return Outcome.Success(all.sortedWith(compareBy({ it.start }, { it.title })))
    }

    suspend fun insert(calendar: CalendarInfo, change: EventChange): Outcome<CalendarEvent> = call(setOf(GoogleScope.CalendarEvents)) { token ->
        send(token, "POST", url("calendars/${enc(calendar.id)}/events") {}, change.toJson()) { body ->
            json.decodeFromString<EventDto>(body).toEvent(calendar)
        }
    }

    suspend fun patch(event: CalendarEvent, change: EventChange): Outcome<CalendarEvent> = call(setOf(GoogleScope.CalendarEvents)) { token ->
        val calendar = CalendarInfo(event.calendarId, event.calendarName, primary = false)
        send(token, "PATCH", url("calendars/${enc(event.calendarId)}/events/${enc(event.id)}") {}, change.toJson()) { body ->
            json.decodeFromString<EventDto>(body).toEvent(calendar)
        }
    }

    suspend fun delete(event: CalendarEvent): Outcome<Unit> = call(setOf(GoogleScope.CalendarEvents)) { token ->
        send(token, "DELETE", url("calendars/${enc(event.calendarId)}/events/${enc(event.id)}") {}, null) { }
    }

    // --- HTTP -------------------------------------------------------------------------------------------------

    private suspend fun <T> call(scopes: Set<GoogleScope>, block: (String) -> GoogleCall<T?>): Outcome<T> =
        when (val r = google.authorized(scopes) { token -> withContext(dispatchers.io) { block(token) } }) {
            is Outcome.Success -> r.value?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.ToolFailed("calendar", "Google sent an event Edith couldn't read."))
            is Outcome.Failure -> r
        }

    private fun <T> get(token: String, url: HttpUrl, parse: (String) -> T): GoogleCall<T> =
        execute(Request.Builder().url(url).header("Authorization", "Bearer $token").build(), parse)

    private fun <T> send(token: String, method: String, url: HttpUrl, body: String?, parse: (String) -> T): GoogleCall<T> =
        execute(
            Request.Builder().url(url).header("Authorization", "Bearer $token")
                .method(method, body?.toRequestBody(JSON_TYPE)).build(),
            parse,
        )

    private fun <T> execute(request: Request, parse: (String) -> T): GoogleCall<T> = try {
        http.value.newCall(request).execute().use { response -> handle(response, parse) }
    } catch (e: java.io.IOException) {
        Log.w(LogTag.Google, "Calendar request failed", e)
        GoogleCall.Failed(AppError.ToolFailed("calendar", "Couldn't reach Google Calendar. Check the phone's connection."))
    } catch (e: kotlinx.serialization.SerializationException) {
        Log.w(LogTag.Google, "Couldn't read Google Calendar's answer", e)
        GoogleCall.Failed(AppError.ToolFailed("calendar", "Google Calendar sent something Edith couldn't read."))
    }

    private fun <T> handle(response: Response, parse: (String) -> T): GoogleCall<T> {
        val body = response.body.string()
        return when {
            response.isSuccessful -> GoogleCall.Ok(parse(body))
            response.code == 401 -> GoogleCall.Unauthorized
            response.code == 403 && (body.contains("insufficientPermissions") || body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT")) -> GoogleCall.MissingScope
            response.code == 404 || response.code == 410 -> GoogleCall.Failed(AppError.ToolFailed("calendar", "That event isn't in your calendar any more."))
            else -> {
                Log.w(LogTag.Google, "Calendar HTTP ${response.code}")
                GoogleCall.Failed(AppError.ToolFailed("calendar", "Google Calendar said no (HTTP ${response.code})."))
            }
        }
    }

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit): HttpUrl =
        (baseUrl.trimEnd('/') + "/" + path).toHttpUrl().newBuilder().apply(query).build()

    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    // --- JSON -------------------------------------------------------------------------------------------------

    private fun EventDto.toEvent(cal: CalendarInfo): CalendarEvent? {
        if (status == "cancelled") return null
        val zone = zone()
        val allDay = start?.date != null
        val s = start?.parse(zone) ?: return null
        val e = end?.parse(zone) ?: (if (allDay) s.plusDays(1) else s.plusHours(1))
        return CalendarEvent(id ?: return null, cal.id, cal.name, summary?.takeIf { it.isNotBlank() } ?: "(No title)", s, e, allDay, location, description)
    }

    private fun EventTimeDto.parse(zone: ZoneId): ZonedDateTime? = when {
        dateTime != null -> OffsetDateTime.parse(dateTime).atZoneSameInstant(zone)
        date != null -> LocalDate.parse(date).atStartOfDay(zone)
        else -> null
    }

    private fun EventChange.toJson(): String {
        val time = { t: ZonedDateTime -> if (allDay) EventTimeDto(date = t.toLocalDate().toString()) else EventTimeDto(dateTime = rfc3339(t), timeZone = t.zone.id) }
        return json.encodeToString(EventWriteDto.serializer(), EventWriteDto(title, location, description, start?.let(time), end?.let(time)))
    }

    companion object {
        const val BASE_URL = "https://www.googleapis.com/calendar/v3"
        const val MAX_RESULTS = 50
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun rfc3339(t: ZonedDateTime): String = t.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}

@Serializable private class CalendarListDto(val items: List<CalendarListEntryDto> = emptyList())
@Serializable private class CalendarListEntryDto(
    val id: String,
    val summary: String? = null,
    val summaryOverride: String? = null,
    val primary: Boolean? = null,
    val selected: Boolean? = null,
)

@Serializable private class EventsDto(val items: List<EventDto> = emptyList())
@Serializable private class EventDto(
    val id: String? = null,
    val status: String? = null,
    val summary: String? = null,
    val location: String? = null,
    val description: String? = null,
    val start: EventTimeDto? = null,
    val end: EventTimeDto? = null,
)
@Serializable private class EventTimeDto(val dateTime: String? = null, val date: String? = null, val timeZone: String? = null)

/** What's sent on insert/patch: null fields are left out (`explicitNulls = false`), so a patch changes only those. */
@Serializable private class EventWriteDto(
    @SerialName("summary") val title: String?,
    val location: String?,
    val description: String?,
    val start: EventTimeDto?,
    val end: EventTimeDto?,
)
