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
     * The one exception is [Migration31To32] (dropped `ai_proposal.task_id` +
     * backfill): it needs data movement BEFORE the schema rebuild, which
     * `AutoMigrationSpec.onPostMigrate` cannot express — it is registered as a
     * manual [androidx.room3.migration.Migration] via `addMigrations` below.
     *
     * [Migration42To43] is the other exception: it creates the full-text
     * indexes, and an index is a virtual table that no schema differ describes.
     *
     * [Migration45To46] is the third: the derived auto-migration rewrites the
     * whole `tasks` table, and dropping the table fires the foreign key that
     * points the notes at a task. A column can be added without that rewrite.
     *
     * During **active schema development** (adding a new migration), you may
     * temporarily re-add `.fallbackToDestructiveMigration(dropAllTables = true)`
     * to this builder to skip writing the migration by hand. **Remove it before
     * committing** — leaving it in production builds causes data loss on upgrade.
     *
     * ## No ":memory:" — use a temp file
     *
     * [dbPath] must be a real file path. Room rejects the literal name `":memory:"`
     * in `databaseBuilder` and only accepts in-memory databases through
     * `Room.inMemoryDatabaseBuilder<T>()`, which is declared in Room's **jvmMain**
     * source set and therefore unreachable from this commonMain seam. Tests that want
     * a throwaway database should create a temp directory and pass a file path inside
     * it — see [com.singularity.todo.feature.tasks.RoomTaskRepositoryContractTest],
     * [com.singularity.todo.core.database.AppDatabaseFactoryJvmTest] and
     * [com.singularity.todo.core.database.DesktopRestartSmokeTest].
     */
    fun build(driver: SQLiteDriver, dbPath: String): AppDatabase {
        // File-level PRAGMAs (journal_mode=WAL) — once, before Room opens the file.
        // WAL is persistent in the header; applying it on every connection is unnecessary.
        PlatformPragmas.applyOnceToFile(driver, dbPath)

        return Room.databaseBuilder<AppDatabase>(name = dbPath)
            .setDriver(driver)
            .addMigrations(
                Migration31To32(),
                // Clears the queue tables, so it cannot be an AutoMigrationSpec — that
                // can only describe a schema change. See Migration37To38.
                Migration37To38(),
                Migration38To39(),
                // Rewrites the stored links so the one grammar reads them, and
                // backfills the note-to-task relation that was never filled.
                Migration44To45(),
                // Adds tasks.series_id without rewriting the `tasks` table: the
                // derived auto-migration drops the table, and the notes that point
                // at a task lose that point. See Migration45To46.
                Migration45To46(),
                // Adds tasks.workflow_state for the same reason: a column on
                // `tasks` cannot be added by the derived auto-migration without
                // rewriting the table. See Migration46To47.
                Migration46To47(),
                // Tag name uniqueness: normalized column, duplicate merge,
                // re-pointed task_tags, partial unique index. See Migration47To48.
                Migration47To48(),
                // Adds profile_id to sync_outbox so a queued patch keeps the profile
                // it was edited under. See Migration48To49.
                Migration48To49(),
                // Adds claimed_at to sync_outbox. See Migration49To50.
                Migration49To50(),
                // Adds claim_token to sync_outbox. See Migration51To52.
                Migration51To52(),
                // Drops claimed_at from sync_outbox. See Migration52To53.
                Migration52To53(),
                // Creates the full-text indexes — virtual tables, which an
                // AutoMigrationSpec cannot describe. See Migration42To43.
                Migration42To43(),
            )
            .build()
    }
}
