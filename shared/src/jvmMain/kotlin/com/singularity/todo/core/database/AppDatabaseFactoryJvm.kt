package com.singularity.todo.core.database

import androidx.room3.Room
import androidx.sqlite.SQLiteDriver

/**
 * JVM-only sibling of [AppDatabaseFactory.build] that opens an in-memory [AppDatabase].
 *
 * `Room.inMemoryDatabaseBuilder` is declared in Room's JVM source set, so the commonMain
 * seam cannot express an in-memory database — and `Room.databaseBuilder` refuses the
 * literal `":memory:"` name. This is the smallest way to close that gap: the entry point
 * lives beside the seam, in the same package, and hands Room's in-memory builder to the
 * seam's one migration registration ([applyAppMigrations]). [AppDatabaseFactory] stays
 * the production entry point; `internal` keeps this out of the module's public JVM API,
 * and only the module's tests call it.
 *
 * There is no file to tune before opening, so the file-level PRAGMA
 * (`journal_mode = WAL`) does not apply here: an in-memory database uses the `memory`
 * journal and creates no `-wal` sidecar. Per-connection PRAGMAs still reach every
 * connection through the driver (the result of
 * [com.singularity.todo.core.database.contract.createSqlDriver]).
 *
 * JVM tests reach this through `DatabaseTestFixture.inMemory()` in `shared/src/jvmTest`,
 * which also owns closing the database.
 */
internal fun buildInMemoryAppDatabase(driver: SQLiteDriver): AppDatabase =
    Room
        .inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(driver)
        .applyAppMigrations()
        .build()
