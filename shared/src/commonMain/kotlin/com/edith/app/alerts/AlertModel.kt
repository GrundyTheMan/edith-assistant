package com.edith.app.alerts

import com.edith.app.core.error.Outcome
import com.edith.app.home.EventKind

/** What an alert is about: the three kinds the owner can switch on per service (C33). */
enum class AlertKind(val label: String, val channelId: String) {
    Doorbell("Doorbell rings", "alerts_doorbell"),
    Person("A person is seen", "alerts_person"),
    Motion("Motion", "alerts_motion"),
    ;

    companion object {
        /** The alert kind a camera event counts as; null for events that are never alerts (the owner's own live view). */
        fun of(kind: EventKind): AlertKind? = when (kind) {
            EventKind.Ring -> Doorbell
            EventKind.Person -> Person
            EventKind.Motion, EventKind.Other -> Motion
            EventKind.LiveView -> null
        }
    }
}

/** Which kinds each service can alert on (Ring tells doorbell, person and motion apart; Blink's recordings are motion). */
object AlertCatalog {
    /** Debug builds: the Demo home's doorbell is on the list, so the whole path can be tried on the emulator (C33). The app sets this at start (it has the build type; the shared module has none). */
    var demoHome: Boolean = false

    private fun kinds(): Map<String, List<AlertKind>> = buildMap {
        put("ring", listOf(AlertKind.Doorbell, AlertKind.Person, AlertKind.Motion))
        put("blink", listOf(AlertKind.Motion))
        if (demoHome) put(com.edith.app.home.FakeHomeBackend.ID, listOf(AlertKind.Doorbell))
    }

    /** The services that can alert, by [com.edith.app.home.HomeBackend.id]. */
    val services: Set<String> get() = kinds().keys

    fun kindsOf(backendId: String): List<AlertKind> = kinds()[backendId].orEmpty()
}

/** The owner's alert switches: a set of `service:Kind`. Everything is off until the owner turns it on. */
data class AlertChoices(val on: Set<String> = emptySet()) {
    fun enabled(backendId: String, kind: AlertKind): Boolean = key(backendId, kind) in on

    /** The services with at least one kind on. */
    val backends: Set<String> get() = on.map { it.substringBefore(SEP) }.toSet()
    val any: Boolean get() = on.isNotEmpty()

    companion object {
        const val SEP = ':'
        fun key(backendId: String, kind: AlertKind) = "$backendId$SEP${kind.name}"
    }
}

/** One thing a camera or doorbell did, as a service reports it. [key] is unique per service (a clip's id, or device and time). */
data class Activity(val key: String, val backend: String, val device: String, val at: Long, val kind: EventKind)

/** One notification to show. */
data class Alert(val backend: String, val device: String, val kind: AlertKind, val at: Long, val key: String)

/** Where the watcher asks what happened (implemented by `Home`). */
fun interface ActivitySource {
    /** What [backendId]'s cameras and doorbells reported since [sinceMs] (ms since 1970). */
    suspend fun activity(backendId: String, sinceMs: Long): Outcome<List<Activity>>
}
