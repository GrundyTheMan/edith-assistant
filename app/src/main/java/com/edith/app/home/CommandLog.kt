package com.edith.app.home

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** Where a command came from, for the log. */
enum class CommandSource { Chat, HomeTab }

/** One line of the command log (C20 §5, C25 §8). Never a camera image, never a token. */
data class LoggedCommand(
    val at: Long,
    val deviceName: String,
    val backend: String,
    /** The tool's action name ("unlock", "set_temperature"). */
    val action: String,
    val value: String?,
    val ok: Boolean,
    /** What the device reported after, or why it failed. */
    val result: String,
    val source: CommandSource,
    val id: Long = 0,
)

/** The owner's audit view of every command Edith sent (a fake in tests). */
interface CommandLog {
    val recent: Flow<List<LoggedCommand>>
    suspend fun add(entry: LoggedCommand)
}

/** Kept on the phone, in Room; the newest [KEEP] are kept. */
class RoomCommandLog(private val dao: HomeCommandDao) : CommandLog {
    override val recent: Flow<List<LoggedCommand>> = dao.recentRows(KEEP).map { rows -> rows.map { it.toLogged() } }

    override suspend fun add(entry: LoggedCommand) {
        dao.insert(HomeCommandEntity.from(entry))
        dao.keepNewest(KEEP)
    }

    companion object {
        const val KEEP = 500
    }
}

/** For tests and previews. */
class MemoryCommandLog : CommandLog {
    private val entries = MutableStateFlow<List<LoggedCommand>>(emptyList())
    override val recent: Flow<List<LoggedCommand>> = entries.asStateFlow()
    val all: List<LoggedCommand> get() = entries.value
    override suspend fun add(entry: LoggedCommand) {
        entries.value = (listOf(entry.copy(id = entries.value.size + 1L)) + entries.value).take(RoomCommandLog.KEEP)
    }
}

@Entity(tableName = "home_commands", indices = [Index("at")])
data class HomeCommandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    @ColumnInfo(name = "device_name") val deviceName: String,
    val backend: String,
    val action: String,
    val value: String?,
    val ok: Boolean,
    val result: String,
    val source: String,
) {
    fun toLogged() = LoggedCommand(at, deviceName, backend, action, value, ok, result,
        if (source == "home_tab") CommandSource.HomeTab else CommandSource.Chat, id)

    companion object {
        fun from(e: LoggedCommand) = HomeCommandEntity(0, e.at, e.deviceName, e.backend, e.action, e.value, e.ok, e.result,
            if (e.source == CommandSource.HomeTab) "home_tab" else "chat")
    }
}

@Dao
interface HomeCommandDao {
    @Insert suspend fun insert(entity: HomeCommandEntity): Long

    @Query("SELECT * FROM home_commands ORDER BY at DESC, id DESC LIMIT :limit")
    fun recentRows(limit: Int): Flow<List<HomeCommandEntity>>

    @Query("DELETE FROM home_commands WHERE id NOT IN (SELECT id FROM home_commands ORDER BY at DESC, id DESC LIMIT :keep)")
    suspend fun keepNewest(keep: Int)
}

