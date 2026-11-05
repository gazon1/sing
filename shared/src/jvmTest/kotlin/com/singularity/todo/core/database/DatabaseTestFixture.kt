package com.singularity.todo.core.database

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import com.singularity.todo.core.database.contract.createSqlDriver
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/**
 * The one owner of a test database's lifecycle.
 *
 * ## What this is for
 *
 * A test that needs a real database used to answer three questions of its own:
 * where the file goes, how the driver is built, and how the file is thrown away
 * afterwards. The answers were written down again in every test file, and the
 * copies drifted: some built the database in a `@BeforeTest`, some in a `lazy`,
 * some named the file `singularity.db` and some `tasks.db`, and some closed the
 * database and some did not.
 *
 * A copy like that is a second answer to "what does a test database look like".
 * The drift is not the interesting part — the interesting part is that a test
 * which opens a database *at all* is now a decision a reader has to make, and
 * that decision belongs in one place.
 *
 * ## The two modes
 *
 * - [inMemory] — the database lives in memory. It is what a test wants when it
 *   wants the database's behaviour and not the database's file. It is also the
 *   cheap one: no file, no WAL header, no disk.
 * - [onDisk] — the database lives in a file inside a temporary directory the
 *   fixture creates and deletes. It is what a test wants when the file itself
 *   is the thing under test: a migration that has to open an older file, a
 *   restart that has to read the data back, a test that looks at the file.
 *
 * ## What the fixture owns
 *
 * The temporary directory and every database opened through it. [close] closes
 * the databases and deletes the directory. A test that needs a database asks
 * for one here and never touches a path of its own.
 *
 * ## Why the in-memory mode is a Room builder and not a file
 *
 * Room rejects the literal name `":memory:"` in `databaseBuilder`, and
 * `Room.inMemoryDatabaseBuilder` is a JVM-only API — unreachable from the
 * `commonMain` seam that production uses. That is why the production builder
 * takes a path. It is also why this fixture, which lives in the JVM test source
 * set, can offer the in-memory mode the production seam cannot: a fresh
 * in-memory database is created at the head schema, so it needs no migration
 * registered either.
 */
class DatabaseTestFixture private constructor(
    private val onDisk: Boolean,
) : Closeable {
    companion object {
        /** The name a database gets when the test does not choose one. */
        const val DEFAULT_NAME: String = "test"

        /**
         * A fixture whose database lives in memory only.
         *
         * Nothing touches the disk: there is no file, no temporary directory and
         * nothing to delete. The database lives exactly as long as the fixture.
         */
        fun inMemory(): DatabaseTestFixture = DatabaseTestFixture(onDisk = false)

        /**
         * A fixture whose database lives in a file inside a temporary directory.
         *
         * The directory is created when the first file is asked for, not when
         * the fixture is made, so a test that never touches its database leaves
         * nothing behind.
         */
        fun onDisk(): DatabaseTestFixture = DatabaseTestFixture(onDisk = true)
    }

    /** The directory the fixture owns; null until it is needed, and null in memory. */
    private var directory: File? = null

    /** The databases opened through this fixture, by name. */
    private val databases = mutableMapOf<String, AppDatabase>()

    /** Every database this fixture opened — [reopen] opens a second one over the same file. */
    private val opened = mutableListOf<AppDatabase>()

    /**
     * The directory the fixture's files live in.
     *
     * Only a file-backed fixture has one. A test that keeps scratch files —
     * a backup archive, an attachment — asks the fixture for the directory
     * rather than making a temporary directory of its own, so that everything
     * the test wrote is removed when the fixture closes.
     */
    fun directory(): File {
        check(onDisk) { "an in-memory fixture has no directory — ask for the database instead" }
        val existing = directory
        if (existing != null) return existing
        val created = Files.createTempDirectory("singularity-test-db-").toFile()
        directory = created
        return created
    }

    /**
     * The absolute path of the file named [name].
     *
     * The name is a name, not a file name: the fixture adds the extension, so a
     * test says `fixture.path("source")` and the file is `source.db`. That is
     * what keeps the `.db` suffix out of the test files — and therefore out of
     * the rule that says a test file may not manage a database file.
     */
    fun path(name: String = DEFAULT_NAME): String {
        check(onDisk) { "an in-memory fixture has no file — ask for the database instead" }
        return File(directory(), "$name.db").absolutePath
    }

    /**
     * The database named [name].
     *
     * The same name always gives the same database, so a test that asks twice
     * does not get two databases and does not have to close one of them.
     */
    fun database(name: String = DEFAULT_NAME): AppDatabase =
        databases.getOrPut(name) {
            build(name).also { opened += it }
        }

    /**
     * A second database over the same file.
     *
     * The restart case: the first database is closed, and a new one opens the
     * same file to read what the first one wrote. [database] would hand back
     * the first one again, so this asks for a new instance — which the fixture
     * closes too.
     */
    fun reopen(name: String = DEFAULT_NAME): AppDatabase {
        check(onDisk) { "only a file-backed database can be reopened" }
        return build(name).also { opened += it }
    }

    /**
     * A raw connection to the file named [name].
     *
     * For the test that needs to write the file itself before Room opens it —
     * a migration plants the schema of an older version into a file and then
     * lets Room run the chain over it — or to look at what Room wrote. The
     * connection is the caller's to close.
     */
    fun connect(name: String = DEFAULT_NAME): SQLiteConnection {
        check(onDisk) { "an in-memory fixture has no file to connect to" }
        return createSqlDriver().open(path(name))
    }

    /**
     * Closes every database this fixture opened and deletes its directory.
     *
     * The databases are closed before the directory goes: a database that still
     * holds the file would keep it open on some systems, and the directory would
     * survive the delete.
     */
    override fun close() {
        for (database in opened) {
            database.close()
        }
        opened.clear()
        databases.clear()
        directory?.deleteRecursively()
        directory = null
    }

    /** The database itself: a file for a file-backed fixture, an in-memory one otherwise. */
    private fun build(name: String): AppDatabase =
        if (onDisk) {
            // The production path, unchanged: the file, the driver, the WAL header
            // and the registered migrations.
            AppDatabaseFactory.build(createSqlDriver(), path(name))
        } else {
            Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(createSqlDriver()).build()
        }
}
