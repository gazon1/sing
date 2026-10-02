package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * SQLite tuning PRAGMAs for [androidx.sqlite.driver.bundled.BundledSQLiteDriver].
 *
 * ## Connection semantics
 *
 * `journal_mode = WAL` is **file-level** — SQLite writes it to the database file header
 * on first set; it persists across connections and is idempotent. Applied once via
 * [applyOnceToFile].
 *
 * `synchronous`, `cache_size`, `temp_store`, `foreign_keys`, `busy_timeout` are
 * **per-connection** — each new connection starts with SQLite defaults. Applied on every
 * connection via [WrappingDriver] so that every connection Room obtains has these settings.
 *
 * @see <https://www.sqlite.org/pragma.html>
 */
object PlatformPragmas {

    /** File-level PRAGMAs — written once to the database file header. */
    val FileLevelCommands: List<String> = listOf(
        "PRAGMA journal_mode = WAL",
    )

    /** Per-connection PRAGMAs — must be set on every new connection. */
    val PerConnectionCommands: List<String> = listOf(
        "PRAGMA synchronous = NORMAL", // fsync cost: O(1) vs FULL
        "PRAGMA cache_size = -2000", // 2 MiB page cache
        "PRAGMA temp_store = MEMORY", // temporary tables & sorting in RAM
        "PRAGMA foreign_keys = ON", // enforce FK constraints on this connection
        "PRAGMA busy_timeout = 5000", // 5 s before returning SQLITE_BUSY
    )

    /**
     * Applies [FileLevelCommands] to [driver] against [path] using a single ephemeral
     * connection. Safe to call before [androidx.room3.Room.databaseBuilder]; the WAL
     * journal mode is written to the file header and survives subsequent connections.
     *
     * Idempotent — setting `journal_mode` on a WAL-mode database is a no-op.
     */
    fun applyOnceToFile(driver: androidx.sqlite.SQLiteDriver, path: String) {
        val conn: SQLiteConnection = driver.open(path)
        conn.use { conn ->
            for (sql in FileLevelCommands) {
                conn.execSQL(sql)
            }
        }
    }

    /**
     * Applies [PerConnectionCommands] to [connection] immediately after it is opened.
     * Call this from within [WrappingDriver.open] every time a new connection is acquired.
     */
    fun applyTo(connection: SQLiteConnection) {
        for (sql in PerConnectionCommands) {
            connection.execSQL(sql)
        }
    }
}
