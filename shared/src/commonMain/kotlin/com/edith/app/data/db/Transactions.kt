package com.edith.app.data.db

import androidx.room.RoomDatabase

/** Runs [block] (which may call the DAOs) in one transaction: all of it is kept, or, if it throws, none of it. */
expect suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R

/** A write was refused because a row it names isn't there (a foreign key) or a value is duplicated. */
expect fun Throwable.isConstraintViolation(): Boolean
