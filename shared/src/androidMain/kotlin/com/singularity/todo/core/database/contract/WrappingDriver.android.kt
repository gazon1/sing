package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Android implementation of [wrappingDriver].
 *
 * `BundledSQLiteDriver` is **final** on Android too (current androidx.sqlite), so —
 * like the JVM actual — this uses composition: it holds an internal
 * [BundledSQLiteDriver] and implements the [SQLiteDriver] interface, intercepting
 * only [open] to apply per-connection PRAGMAs. Room only ever sees the interface.
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
