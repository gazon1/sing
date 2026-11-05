package com.singularity.todo.test.helpers

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.buildInMemoryAppDatabase
import com.singularity.todo.core.database.contract.createSqlDriver
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/**
 * The one owner of "open a test database / close it" in `shared/src/jvmTest`.
 *
 * A JVM test that wants a database otherwise repeats the same setup — a temp directory,
 * a path inside it, `AppDatabaseFactory.build` — and the same teardown, and the copies
 * drift apart. This class is that shape in one place:
 *
 * ```kotlin
 * DatabaseTestFixture.fileBacked().use { fixture ->
 *     fixture.database.noteDao() // ...
 * }
 * ```
 *
 * The existing tests that still hand-roll it are migrated in follow-up issues; this
 * class is the shape they move to.
 *
 * There are two modes:
 *
 * - [fileBacked] — the database is a real file inside a temp directory the fixture creates.
 *   [databasePath] is the absolute path to it. The optional `prepareFile` hook runs with
 *   that path **before Room opens the file**, which is what a migration test needs to seed
 *   an older schema into the very file Room is about to migrate.
 * - [inMemory] — the database is not on disk at all: Room's in-memory builder, no file, no
 *   `-wal` sidecar. [databasePath] and [directory] are `null`. It still carries the same
 *   DAOs, the same current schema, and the same migration set as [fileBacked], because
 *   both entry points register migrations through the seam's one
 *   [com.singularity.todo.core.database.applyAppMigrations].
 *
 * The fixture owns the whole lifecycle: opening in the factory methods, closing the
 * database and deleting the temp directory in [close]. [close] deletes the directory even
 * when closing the database throws, so a failing test still cleans up after itself. Use
 * `use { }` (or a `finally { close() }`) so a failing assertion cleans up too.
 */
class DatabaseTestFixture private constructor(
    /** The open database. */
    val database: AppDatabase,
    /** The absolute database file path, or `null` for [inMemory]. */
    val databasePath: String?,
    /** The temp directory this fixture created and will delete, or `null` for [inMemory]. */
    val directory: File?,
) : Closeable {
    override fun close() {
        try {
            database.close()
        } finally {
            directory?.deleteRecursively()
        }
    }

    companion object {
        /**
         * Opens a file-backed database inside a fresh temp directory.
         *
         * @param prepareFile runs with the absolute database path before Room opens the
         *   file — the same path [databasePath] reports afterwards.
         */
        fun fileBacked(prepareFile: (databasePath: String) -> Unit = {}): DatabaseTestFixture {
            val directory = Files.createTempDirectory("singularity-test-").toFile()
            val databasePath = File(directory, "singularity.db").absolutePath
            try {
                prepareFile(databasePath)
                return DatabaseTestFixture(
                    database = AppDatabaseFactory.build(createSqlDriver(), databasePath),
                    databasePath = databasePath,
                    directory = directory,
                )
            } catch (failure: Throwable) {
                // The fixture never existed, so its owner cannot close it.
                directory.deleteRecursively()
                throw failure
            }
        }

        /** Opens an in-memory database: no file, no temp directory, nothing to clean up. */
        fun inMemory(): DatabaseTestFixture =
            DatabaseTestFixture(
                database = buildInMemoryAppDatabase(createSqlDriver()),
                databasePath = null,
                directory = null,
            )
    }
}
