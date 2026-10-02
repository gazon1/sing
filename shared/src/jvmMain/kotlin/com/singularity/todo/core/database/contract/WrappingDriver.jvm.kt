package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * JVM implementation of [wrappingDriver].
 *
 * `BundledSQLiteDriver` is **final** on the JVM, so it cannot be extended.
 * This implementation uses composition: it holds an internal [BundledSQLiteDriver]
 * and implements the [SQLiteDriver] interface by delegating every member to it,
 * intercepting only [open] to apply PRAGMAs.
 *
 * Room only ever sees the [SQLiteDriver] interface, so the concrete type is irrelevant.
 */
actual val wrappingDriver: SQLiteDriver = object : SQLiteDriver {
    private val native = BundledSQLiteDriver()

    override fun open(path: String): SQLiteConnection {
        val conn = native.open(path)
        PlatformPragmas.applyTo(conn)
        return conn
    }

    override val hasConnectionPool: Boolean get() = native.hasConnectionPool
}
