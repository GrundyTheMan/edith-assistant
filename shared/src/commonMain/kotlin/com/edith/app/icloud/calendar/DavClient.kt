package com.edith.app.icloud.calendar

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlin.time.Instant

/** [reference] (an address or a path) resolved against this one, or null when it can't be. */
fun Url.resolve(reference: String): Url? = runCatching { URLBuilder(this).takeFrom(reference).build() }.getOrNull()

/** One calendar collection found by discovery (C24 §3). */
data class DavCalendar(val href: Url, val name: String, val canWrite: Boolean, val color: String?)

/** What discovery found: the calendars that hold events, and the default one if the server names it. */
data class DavHome(val calendars: List<DavCalendar>, val defaultHref: Url?)

/** One resource from a calendar query: where it is, its ETag, and its iCalendar text. */
data class DavResource(val href: Url, val etag: String?, val ics: String)

/** How a CalDAV request went. */
sealed interface DavResult<out T> {
    data class Ok<T>(val value: T) : DavResult<T>
    /** 401: the app-specific password stopped working. */
    data object Unauthorized : DavResult<Nothing>
    /** 412: the resource changed since it was read. */
    data object Changed : DavResult<Nothing>
    data object NotFound : DavResult<Nothing>
    data class Failed(val why: String, val network: Boolean = false) : DavResult<Nothing>
}

/**
 * What the iCloud calendar source needs from a CalDAV server (K2.7): the five requests. Android's [CalDavClient] does them over OkHttp with a
 * network interceptor that keeps the password to Apple's own hosts; the iPhone's will do the same over Darwin (not built yet).
 */
interface DavClient {
    suspend fun discover(): DavResult<DavHome>
    suspend fun query(calendar: Url, from: Instant, to: Instant): DavResult<List<DavResource>>
    suspend fun get(href: Url): DavResult<DavResource>
    suspend fun put(href: Url, ics: String, etag: String?): DavResult<String?>
    suspend fun delete(href: Url, etag: String?): DavResult<Unit>
}
