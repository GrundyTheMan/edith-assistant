package com.edith.app.accounts

import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.MailSource
import com.edith.app.accounts.tasks.TaskSource
import com.edith.app.accounts.tasks.TasksAccess
import kotlinx.coroutines.flow.StateFlow

/** A live account: its provider's connection (C22 §1). */
interface AccountConnection {
    val state: StateFlow<AccountState>
    val capabilities: Capabilities
    val calendar: CalendarSource?
    val mail: MailSource?
    /** C35: the account's to-dos; null for providers without them here (Microsoft, iCloud). */
    val tasks: TaskSource? get() = null
    /** C35 §2: whether the to-dos may be used, apart from [state]. */
    val tasksAccess: StateFlow<TasksAccess>? get() = null
    /** At startup and after signing in: checks quietly whether access is still granted. */
    suspend fun start()
    /** Grant access / Sign in again / Try again. [show] shows a consent screen when the provider has one. */
    suspend fun grant(activity: PlatformActivity, show: (PlatformPendingIntent) -> Unit)
    /** C35: **To-dos: Grant access**. */
    suspend fun grantTasks(activity: PlatformActivity, show: (PlatformPendingIntent) -> Unit) = Unit
    suspend fun onConsentResult(data: PlatformIntent?)
    /** Revokes Edith's access at the provider (a failure is only logged) and deletes the account's secrets. */
    suspend fun disconnect()
}
