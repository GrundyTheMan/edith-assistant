package com.edith.app.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** The database in the file at [path], opened with the SQLite that ships inside the app. */
fun EdithDatabase.Companion.open(path: String): EdithDatabase =
    Room.databaseBuilder<EdithDatabase>(name = path)
        .addMigrations(*MIGRATIONS)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
