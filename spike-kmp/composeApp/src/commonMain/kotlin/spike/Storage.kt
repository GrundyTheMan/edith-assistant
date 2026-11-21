package spike

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath

/** K0.4: the same database and preferences code on both platforms. */
@Entity
data class Note(@PrimaryKey(autoGenerate = true) val id: Long = 0, val text: String)

@Dao
interface NoteDao {
    @Insert
    suspend fun add(note: Note): Long

    @Query("SELECT * FROM Note ORDER BY id")
    suspend fun all(): List<Note>
}

@Database(entities = [Note::class], version = 1)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
}

// Room's processor writes the actual of this for each platform.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Where the app keeps its files: the app's own Documents folder on the iPhone, a temp folder on the desktop. */
expect fun appDataDir(): String

/** The platform's way to name the database file (Android would need a Context; the others need a path). */
expect fun databaseBuilder(): RoomDatabase.Builder<AppDatabase>

fun openDatabase(): AppDatabase = databaseBuilder().setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Default).build()

fun preferences(): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(produceFile = { (appDataDir() + "/spike.preferences_pb").toPath() })

/** Writes a row, reads it back, closes: the probe line shown on screen. */
suspend fun roomProbe(): String {
    val db = openDatabase()
    try {
        val id = db.notes().add(Note(text = "hello from Room"))
        val all = db.notes().all()
        return "Room: ${all.size} row(s); last is \"${all.last().text}\" (id $id)"
    } finally {
        db.close()
    }
}

/** Counts launches in a preferences file: on a phone the number goes up each time the app starts. */
suspend fun dataStoreProbe(): String {
    val store = preferences()
    val key = intPreferencesKey("launches")
    val n = (store.data.first()[key] ?: 0) + 1
    store.edit { it[key] = n }
    return "DataStore: launch number $n"
}
