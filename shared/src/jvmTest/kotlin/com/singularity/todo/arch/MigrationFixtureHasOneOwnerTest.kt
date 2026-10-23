package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * The exported schema has one owner.
 *
 * ## The defect this catches
 *
 * Twelve hand-written migration tests each carried their own copy of the
 * function that applies an exported Room schema to an empty database: the same
 * thirty lines, counting the same three `parentFile`s to find the export, and
 * reading the same `setupQueries` the export carries. The copies were not equal
 * — one of them seeded a row inside the fixture function, three of them wrote
 * the path differently, and one of them was the copy that the next migration
 * was copied from.
 *
 * A copy like that is a second answer to the question "what does the database
 * of version N look like". When the export changes, one copy is updated and the
 * others keep building the old shape, and the test that keeps the old shape
 * fails for a reason that has nothing to do with the migration it is about.
 *
 * The fixture now has one owner —
 * `MigrationRoundTripSupport.applyExportedSchema` — and this test says so.
 *
 * ## What it does not do
 *
 * It does not check that the shared fixture is *right*: the round-trip test
 * does that, against every exported version. This test only checks that there
 * is one fixture, so that "the fixture is wrong" and "the fixture is a copy of
 * something else" stay two different failures.
 */
@Tag("fast")
class MigrationFixtureHasOneOwnerTest {

    /** The file that owns the fixture. */
    private val owner = "MigrationRoundTripSupport.kt"

    /** The migration tests that build a database of an older version. */
    private val migrationTests: List<File> = databaseTests()
        .filter { it.name.startsWith("Migration") && it.name.endsWith("Test.kt") }
        .filterNot { it.name == "MigrationRoundTripTest.kt" }

    @Test
    fun `only the owner reads the exported schema`() {
        val offenders = databaseTests()
            .filterNot { it.name == owner }
            .filter { it.readText().contains("setupQueries") }
            .map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "the exported schema is read in more than one place: $offenders. " +
                "$owner owns it — a copy is a second answer to what the database of a " +
                "version looks like, and only one of them gets updated when the export changes",
        )
    }

    @Test
    fun `every migration test builds its database through the shared fixture`() {
        val offenders = migrationTests
            .filterNot { it.readText().contains("applyExportedSchema(") }
            .map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "these migration tests do not use the shared fixture: $offenders. " +
                "A migration test that writes its own fixture is a test whose database is " +
                "not the database a user of that version would have had",
        )
    }

    /** The Kotlin files of the database tests. */
    private fun databaseTests(): List<File> =
        SourceScan.jvmTestSourcesRoot()
            .resolve("com/singularity/todo/core/database")
            .listFiles { file -> file.isFile && file.name.endsWith(".kt") }
            ?.toList()
            .orEmpty()
}
