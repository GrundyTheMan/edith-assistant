package com.edith.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Edith's database, schema version 1 (C05, 01-architecture §5.1). Every v1 table exists from the start, so no
 * migrations are needed during v1. The schema is exported to app/schemas/ at build time.
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, AttachmentEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class, ListConverters::class)
abstract class EdithDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        const val FILE_NAME = "edith.db"

        /** The app's database. Create once per process (AppContainer does). */
        fun create(context: Context): EdithDatabase =
            Room.databaseBuilder(context, EdithDatabase::class.java, FILE_NAME).addCallback(EdithDatabaseCallback).build()
    }
}

/** Creates the one v1 conversation (id 1, "Edith") when the database file is first created. */
object EdithDatabaseCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO conversations (id, title, created_at) VALUES (?, ?, ?)",
            arrayOf<Any>(1L, "Edith", System.currentTimeMillis()),
        )
    }
}
