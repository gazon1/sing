package com.singularity.todo.core.database

import androidx.room3.Room
import androidx.sqlite.SQLiteDriver
import com.singularity.todo.core.database.contract.PlatformPragmas

/**
 * Single seam where [androidx.room3.Room] is referenced.
 *
 * - Encapsulates Room so feature code never imports `androidx.room3.*`.
 * - Applies [PlatformPragmas] (WAL, NORMAL synchronous, cache tuning) on the file path
 *   before Room initialises the schema.
 * - Keeps the Android-side EROFS workaround centralised — callers pass an absolute
 *   path that the platform already resolved (e.g. via `Context.getDatabasePath`).
 */
object AppDatabaseFactory {

    /**
     * Build the production [AppDatabase] bound to an absolute file path.
     *
     * @param driver platform SQLite driver (Android: BundledSQLiteDriver, JVM: same).
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
        PlatformPragmas.applyTo(driver, dbPath)
        return Room.databaseBuilder<AppDatabase>(name = dbPath)
            .setDriver(driver)
            // .fallbackToDestructiveMigration(dropAllTables = true) // TEMP: re-add while writing migration, REMOVE before commit
            .build()
    }
}
