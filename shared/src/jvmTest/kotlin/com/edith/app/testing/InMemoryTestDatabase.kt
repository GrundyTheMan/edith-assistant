package com.edith.app.testing

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.edith.app.data.db.EdithDatabase
import kotlinx.coroutines.Dispatchers

/** A fresh in-memory database for the engine tests in src/engineTest, which the desktop and the iPhone simulator both run. */
fun inMemoryTestDatabase(): EdithDatabase =
    Room.inMemoryDatabaseBuilder<EdithDatabase>().setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Default).build()
