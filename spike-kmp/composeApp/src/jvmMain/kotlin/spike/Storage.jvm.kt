package spike

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

actual fun appDataDir(): String = File(System.getProperty("java.io.tmpdir"), "edith-spike").apply { mkdirs() }.absolutePath

actual fun databaseBuilder(): RoomDatabase.Builder<AppDatabase> = Room.databaseBuilder<AppDatabase>(name = appDataDir() + "/spike.db")
