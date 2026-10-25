package com.edith.app.data.room

import android.content.Context
import androidx.room.*

@Database(entities = [ChatHistoryEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatHistoryDao(): ChatHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "edith_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
