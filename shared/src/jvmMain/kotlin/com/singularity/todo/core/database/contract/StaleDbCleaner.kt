package com.singularity.todo.core.database.contract

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

/**
 * Probes [dbPath] to determine whether it is a Room-managed database.
 *
 * A Room database writes `room_master_table` on first creation.
 * If that table is absent, the file is either:
 *  - a pre-Room hand-rolled SQLite file (e.g. from the old jdbc-based setup), or
 *  - a fresh empty file that Room has never opened.
 *
 * In either case the caller can safely delete the file and let Room
 * recreate it with the current schema.
 *
 * @return `true` if the file was deleted; `false` if it already looked like
 *   a Room database or did not exist.
 */
fun wipeIfNotRoomManaged(dbPath: String): Boolean {
    val file = File(dbPath)
    if (!file.exists()) return false

    return runCatching {
        val driver: SQLiteDriver = BundledSQLiteDriver()
        val connection = driver.open(dbPath)
        connection.use { connection ->
            // Execute a query against room_master_table.
            // execSQL throws SQLiteException if the table/column does not exist.
            // This is the cheapest possible "does the table exist" probe.
            connection.execSQL("SELECT 1 FROM room_master_table LIMIT 1")
            false // table exists — Room database, leave it alone
        }
    }.getOrElse {
        // table missing or query failed — not a Room database
        val wal = File("$dbPath-wal")
        val shm = File("$dbPath-shm")
        file.delete()
        wal.delete()
        shm.delete()
        true
    }
}
