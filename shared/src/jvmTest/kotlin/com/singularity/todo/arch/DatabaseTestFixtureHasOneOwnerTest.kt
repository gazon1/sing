package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The test database has one owner.
 *
 * ## What the rule is
 *
 * Every JVM test that needs a real database takes it from `DatabaseTestFixture`:
 * the fixture decides whether the database lives in memory or in a file, it owns
 * the temporary directory, and it closes what it opened. A test file that opens
 * a database on its own is a second answer to "what does a test database look
 * like" — and those answers drifted: some used a temp file and some did not,
 * some closed the database and some did not, and one file left a directory
 * behind on every run.
 *
 * So the rule is not "the tests must be tidy". It is "there is exactly one
 * place where a test database is created, and it is the fixture".
 *
 * ## How the rule is checked
 *
 * Lexically, over the sources of `jvmTest`. A check that cannot fail reports
 * success having checked nothing, so the check has a positive control: the
 * fixture itself does everything the rule forbids, and it is the one file
 * allowed to.
 *
 * The scan reads the sources rather than the compiled classes on purpose — the
 * rule is about where a decision is written down, and a decision that is
 * written down in a test file is the thing being looked for.
 */
@Tag("fast")
class DatabaseTestFixtureHasOneOwnerTest {
    /** The one file allowed to open a database and to own a temporary directory. */
    private val owner = "DatabaseTestFixture.kt"

    /** This file: it names what it forbids, so it cannot be held to it. */
    private val guard = "DatabaseTestFixtureHasOneOwnerTest.kt"

    /** What opens a database. A test file has no business doing this. */
    private val databaseOpenings =
        listOf(
            "AppDatabaseFactory.build",
            "Room.databaseBuilder",
            "Room.inMemoryDatabaseBuilder",
            "createSqlDriver",
        )

    /** What manages a temporary directory. */
    private val temporaryDirectoryWork =
        listOf(
            "createTempDirectory",
            "createTempFile",
            "deleteRecursively",
        )

    /** What a temporary directory is being used for when it holds a database. */
    private val databasePaths =
        listOf(
            "dbPath",
            "dbFile",
            "databaseFile",
            ".db\"",
            ".db'",
        )

    @Test
    fun `no test file opens a database outside the fixture`() {
        val offenders =
            jvmTestSources()
                .filterNot { it.name == owner || it.name == guard }
                .filter { source -> databaseOpenings.any { source.contains(it) } }
                .map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "These files open a database themselves. Every test database comes from " +
                "DatabaseTestFixture — the fixture decides whether the database is in " +
                "memory or on disk, owns the temporary directory and closes what it " +
                "opened: $offenders",
        )
    }

    @Test
    fun `no test file manages a temporary database directory outside the fixture`() {
        val offenders =
            jvmTestSources()
                .filterNot { it.name == owner || it.name == guard }
                .filter { source ->
                    temporaryDirectoryWork.any { source.contains(it) } &&
                        databasePaths.any { source.contains(it) }
                }.map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "These files create or delete a temporary directory that holds a database. " +
                "The fixture owns that directory — a file that manages it is a file " +
                "that can leave it behind: $offenders",
        )
    }

    @Test
    fun `the fixture itself does what the rule forbids elsewhere`() {
        // The positive control. Without it the two rules above could pass over
        // an empty tree, and a rule that cannot fail is not a rule.
        val fixture =
            jvmTestSources().firstOrNull { it.name == owner }
                ?: error("DatabaseTestFixture.kt is missing — the tests have no owner")

        assertTrue(
            fixture.readText().contains("AppDatabaseFactory.build"),
            "the fixture must be the file that opens the database",
        )
    }

    /** Every Kotlin file of this module's `jvmTest` sources, comments stripped. */
    private fun jvmTestSources(): List<Pair<File, String>> =
        SourceScan
            .jvmTestSourcesRoot()
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { it to KotlinText.stripComments(it.readText()) }
            .toList()
}
