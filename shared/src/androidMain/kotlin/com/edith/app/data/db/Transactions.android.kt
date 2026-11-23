package com.edith.app.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.SQLiteException

actual suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R = withTransaction(block)

actual fun Throwable.isConstraintViolation(): Boolean =
    this is SQLiteConstraintException || (this is SQLiteException && message.orEmpty().contains("constraint", ignoreCase = true))
