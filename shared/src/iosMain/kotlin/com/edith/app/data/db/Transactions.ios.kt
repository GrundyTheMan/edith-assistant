package com.edith.app.data.db

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException

actual suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }

actual fun Throwable.isConstraintViolation(): Boolean = this is SQLiteException && message.orEmpty().contains("constraint", ignoreCase = true)
