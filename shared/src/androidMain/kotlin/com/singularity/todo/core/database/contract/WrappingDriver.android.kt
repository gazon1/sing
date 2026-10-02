package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Android implementation of [wrappingDriver].
 *
 * Extends [BundledSQLiteDriver] so that the driver is recognized by the Android framework
 * and Room as a native SQLite driver. The [open] override intercepts every connection
 * acquisition to apply per-connection PRAGMAs before the connection is returned.
 */
actual val wrappingDriver: SQLiteDriver = object : BundledSQLiteDriver() {
    override fun open(path: String): SQLiteConnection {
        val conn = super.open(path)
        PlatformPragmas.applyTo(conn)
        return conn
    }
}
