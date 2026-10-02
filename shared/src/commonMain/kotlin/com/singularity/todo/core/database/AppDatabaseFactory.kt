package com.singularity.todo.core.database

import androidx.room3.Room
import androidx.sqlite.SQLiteDriver
import com.singularity.todo.core.database.contract.PlatformPragmas
import com.singularity.todo.core.database.contract.wrappingDriver

/**
 * Single seam where [androidx.room3.Room] is referenced.
 *
 * - Encapsulates Room so feature code never imports `androidx.room3.*`.
 * - [wrappingDriver] (the result of [createSqlDriver]) applies per-connection PRAGMAs
 *   (synchronous, cache_size, temp_store, foreign_keys, busy_timeout) on every connection
 *   that Room opens from the pool.
 * - Applies file-level PRAGMAs (journal_mode=WAL) once via
 *   [PlatformPragmas.applyOnceToFile] before the builder is called — WAL is persistent
 *   in the file header and survives across connections.
 * - Keeps the Android-side EROFS workaround centralised — callers pass an absolute
 *   path that the platform already resolved (e.g. via `Context.getDatabasePath`).
 *
 * ## Why wrappingDriver + applyOnceToFile?
 *
 * `journal_mode=WAL` is file-level (written to the header) — applying it once on a new
 * file is sufficient. All other PRAGMAs are per-connection, so they must be set on every
 * connection Room opens. [wrappingDriver] ensures this by intercepting `open()`.
 */
object AppDatabaseFactory {

    /**
     * Build the production [AppDatabase] bound to an absolute file path.
     *
     * @param driver the result of [createSqlDriver] — a [SQLiteDriver] that applies
     *   per-connection PRAGMAs on every [SQLiteDriver.open] call.
     * @param dbPath absolute file path. On Android must be inside the app's databases
     *   dir — Room 3 KMP treats a relative `name` as `/` which triggers EROFS.
     *
     * ## Migration discipline
     *
     * Every schema change (new table, new column, dropped column) must register a
     * corresponding [androidx.room3.AutoMigration] in [AppDatabase] and an
     * [androidx.room3.migration.AutoMigrationSpec] in [Migrations].
     *
     * During **active schema development** (adding a new migration), you may
     * temporarily re-add `.fallbackToDestructiveMigration(dropAllTables = true)`
     * to this builder to skip writing the migration by hand. **Remove it before
     * committing** — leaving it in production builds causes data loss on upgrade.
     */
    fun build(driver: SQLiteDriver, dbPath: String): AppDatabase {
        // File-level PRAGMAs (journal_mode=WAL) — once, before Room opens the file.
        // WAL is persistent in the header; applying it on every connection is unnecessary.
        PlatformPragmas.applyOnceToFile(driver, dbPath)

        return Room.databaseBuilder<AppDatabase>(name = dbPath)
            .setDriver(driver)
            .build()
    }
}
