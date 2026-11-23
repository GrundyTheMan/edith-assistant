package com.edith.app.data.db

import android.content.Context
import androidx.room.Room

/** The app's database, in the app's own folder. Create once per process (AppContainer does). */
fun EdithDatabase.Companion.create(context: Context): EdithDatabase =
    Room.databaseBuilder(context, EdithDatabase::class.java, FILE_NAME).addMigrations(*MIGRATIONS).build()
