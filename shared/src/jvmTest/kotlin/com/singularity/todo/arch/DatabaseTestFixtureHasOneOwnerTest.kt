package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * The exemption is pinned to the owner's path, not its name: a second copy of
 * `DatabaseTestFixture.kt` in another package is a copy, which is exactly what
 * this rule must report. Neither rule depends on how the code spells a path —
 * the database reference is what makes a temporary directory a *database*
 * directory, so renaming the file inside it does not hide it.
 *
 * The scan reads the sources rather than the compiled classes on purpose — the
 * rule is about where a decision is written down, and a decision that is
 * written down in a test file is the thing being looked for.
 */
@Tag("fast")
class DatabaseTestFixtureHasOneOwnerTest {
    /** The one file allowed to open a database and to own a temporary directory. */
    private val ownerPath = "core/database/DatabaseTestFixture.kt"

    /** This file: it names what it forbids, so it cannot be held to it. */
    private val guardPath = "arch/DatabaseTestFixtureHasOneOwnerTest.kt"

    /** The factory's own test carries the name in its class name; it is not an owner. */
    private val factoryTestPath = "core/database/AppDatabaseFactoryJvmTest.kt"

    /** What opens a database. */
    private val databaseOpenings =
        listOf(
            Regex("""\bAppDatabaseFactory\b"""),
            Regex("""\bdatabaseBuilder\b"""),
            Regex("""\binMemoryDatabaseBuilder\b"""),
            Regex("""\bbuildInMemoryAppDatabase\b"""),
            Regex("""\bcreateSqlDriver\b"""),
        )

    /** What manages a temporary directory. */
    private val temporaryDirectoryWork =
        Regex("""\b(createTempDirectory|createTempFile|deleteRecursively)\b""")

    /** What makes a temporary directory a database directory, whatever it is named. */
    private val databaseReference =
        Regex("""\b(AppDatabase|DatabaseTestFixture|AppDatabaseFactory|databaseBuilder|createSqlDriver)\b""")

    @Test
    fun `there is exactly one DatabaseTestFixture, at the owner path`() {
        val copies =
            jvmTestSources
                .map { it.first }
                .filter { it.name == "DatabaseTestFixture.kt" }

        assertEquals(
            1,
            copies.size,
            "a copy of the fixture is a second owner — only $ownerPath may exist: ${copies.map { it.path }}",
        )
        assertTrue(copies.single().isAt(ownerPath), "the fixture moved away from $ownerPath")
    }

    @Test
    fun `no test file opens a database outside the fixture`() {
        val offenders =
            exemptableSources()
                .filter { source -> databaseOpenings.any { it.containsMatchIn(source.second) } }
                .map { it.first.name }

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
            exemptableSources()
                .filter { source ->
                    temporaryDirectoryWork.containsMatchIn(source.second) &&
                        databaseReference.containsMatchIn(source.second)
                }.map { it.first.name }

        assertTrue(
            offenders.isEmpty(),
            "These files create or delete a temporary directory that holds a database. " +
                "The fixture owns that directory — a file that manages it is a file " +
                "that can leave it behind: $offenders",
        )
    }

    @Test
    fun `the fixture itself does what the rule forbids elsewhere`() {
        // The positive control. Without it the rules above could pass over an
        // empty tree, or over matchers that no longer match anything — a gate
        // that cannot fail is not a gate.
        val fixture =
            jvmTestSources.firstOrNull { it.first.isAt(ownerPath) }
                ?: error("$ownerPath is missing — the tests have no owner")

        assertTrue(
            databaseOpenings.any { it.containsMatchIn(fixture.second) },
            "no opening matcher matched the fixture — the rule cannot fail",
        )
        assertTrue(
            temporaryDirectoryWork.containsMatchIn(fixture.second) &&
                databaseReference.containsMatchIn(fixture.second),
            "the temporary-directory rule cannot match the fixture — it cannot fail there either",
        )
    }

    /** Every Kotlin file of this module's `jvmTest` sources, comments stripped. */
    private val jvmTestSources: List<Pair<File, String>> by lazy {
        SourceScan
            .jvmTestSourcesRoot()
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { it to KotlinText.stripComments(it.readText()) }
            .toList()
    }

    private fun exemptableSources(): List<Pair<File, String>> =
        jvmTestSources.filterNot {
            it.first.isAt(ownerPath) || it.first.isAt(guardPath) || it.first.isAt(factoryTestPath)
        }

    private fun File.isAt(relativePath: String): Boolean =
        path.replace(File.separatorChar, '/').endsWith("/$relativePath")
}
