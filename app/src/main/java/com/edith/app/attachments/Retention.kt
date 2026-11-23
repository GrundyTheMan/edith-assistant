package com.edith.app.attachments

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.Clock
import com.edith.app.data.db.AttachmentDao
import com.edith.app.data.db.AttachmentDirection
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the newest N attachments per direction (C16 §2, decision O1): the owner's and Edith's are counted apart,
 * across all chats. Older files are deleted and their rows marked removed, so the chat shows "no longer available".
 * Resends don't count (they point at the original), and neither do this run's session files (keep = 0).
 */
class Retention(
    private val dao: AttachmentDao,
    private val store: FileAttachmentStore,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
) {
    private val lock = Mutex()

    /** Prunes to the saved setting (after a message with attachments, and at startup). Returns how many went. */
    suspend fun apply(): Int = prune(settings.current().keepRecentAttachments)

    /** Prunes to [keep]: a lower setting applies at once (Settings asks first, with [wouldRemove]). */
    suspend fun prune(keep: Int): Int = lock.withLock {
        withContext(dispatchers.io) {
            val now = clock.now().toEpochMilliseconds()
            var removed = 0
            for (direction in AttachmentDirection.entries) {
                for (old in dao.keptNewestFirst(direction).drop(keep.coerceAtLeast(0))) {
                    store.delete(old.storedPath)
                    dao.markRemoved(old.id, now)
                    removed++
                }
            }
            if (removed > 0) Log.d(LogTag.Chat, "Retention: removed $removed attachment(s), keeping $keep per direction")
            removed
        }
    }

    /** How many files keeping [keep] would delete, for "This will delete 7 older attachments." */
    suspend fun wouldRemove(keep: Int): Int = withContext(dispatchers.io) {
        AttachmentDirection.entries.sumOf { (dao.keptNewestFirst(it).size - keep.coerceAtLeast(0)).coerceAtLeast(0) }
    }

    /**
     * At startup: last run's session files are gone, so their rows are marked removed; files no row points at
     * (a crash between writing and storing, or rows removed some other way) are deleted; then the setting applies.
     */
    suspend fun onStart() {
        lock.withLock {
            withContext(dispatchers.io) {
                store.clearSessionAndTemp()
                val now = clock.now().toEpochMilliseconds()
                dao.markSessionRemoved(now)
                val referenced = dao.allStoredPaths().toSet()
                val orphans = store.storedFiles().filter { (path, _) -> path !in referenced }
                orphans.forEach { (path, _) -> store.delete(path) }
                if (orphans.isNotEmpty()) Log.d(LogTag.Chat, "Deleted ${orphans.size} attachment file(s) nothing referred to")
            }
        }
        apply()
    }

    /** Settings → Delete all attachments now. */
    suspend fun deleteAll(): Int = lock.withLock {
        withContext(dispatchers.io) {
            val paths = dao.allStoredPaths()
            paths.forEach(store::delete)
            dao.markAllRemoved(clock.now().toEpochMilliseconds())
            paths.size
        }
    }

    /** "12 files, 8.4 MB" in Settings: what's kept on the phone now. */
    suspend fun usage(): Usage = withContext(dispatchers.io) {
        val files = store.storedFiles()
        Usage(files.size, files.sumOf { it.second.length() })
    }

    data class Usage(val files: Int, val bytes: Long)
}
