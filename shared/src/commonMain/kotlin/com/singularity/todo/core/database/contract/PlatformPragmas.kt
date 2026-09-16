package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL

/**
 * Desktop SQLite tuning commands. Mirrored from the previous hand-rolled JDBC setup so
 * existing desktop performance characteristics are preserved after the Room migration.
 *
 * Applied once per database file open. Pure helper — no Room, no platform imports —
 * easy to unit-test against a fake driver.
 */
object PlatformPragmas {

    /** PRAGMA statements executed in this order. Visible for testing. */
    val Commands: List<String> = listOf(
        "PRAGMA journal_mode = WAL",
        "PRAGMA synchronous = NORMAL",
        "PRAGMA cache_size = -2000",
        "PRAGMA temp_store = MEMORY",
    )

    /**
     * Applies [Commands] to [driver] against [path]. Opens and closes a single connection.
     * Idempotent — PRAGMA-as-Setter statements overwrite prior values.
     */
    fun applyTo(driver: SQLiteDriver, path: String) {
        val conn: SQLiteConnection = driver.open(path)
        conn.use { conn ->
            for (sql in Commands) {
                conn.execSQL(sql)
            }
        }
    }
}
