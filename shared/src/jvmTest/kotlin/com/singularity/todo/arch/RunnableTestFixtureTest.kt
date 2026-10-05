package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin half of the shared "runnable test member" fixture table.
 *
 * `config/test-fixtures/runnable-test-members.txt` is the single definition of
 * what makes a class runnable. This suite proves [RunnableTestMember] agrees
 * with it; `scripts/tests/test_runnable_test_members.py` proves the same for
 * `infra/kiwi/sync.py`.
 *
 * ## Why a data file and not shared constants
 *
 * The defect this closes was a divergence between two implementations of one
 * concept — a Kotlin gate matching `startsWith("@Test")` and a Python scanner
 * matching the whole JUnit set. Two implementations in two languages cannot be
 * compared by a test written in one of them: whichever runs, the other is taken
 * on faith. Putting the fixtures in a file both suites read turns "these two
 * agree" into a claim the build checks, and makes the next annotation form added
 * to one side a red test rather than a review comment.
 *
 * ## What this does NOT cover
 *
 * Whether the *file* produces a run — abstract bases, helper objects, the
 * `Test`-name rule that `sync.py` applies when it keys Kiwi cases by class.
 * Those are separate questions with their own fixtures in `test_kiwi_sync.py`.
 * [hasRunnableTest] here applies the same name rule as Python, so the two
 * implementations compare like with like; [TestTagCoverageTest] deliberately
 * does not, because a class named anything else with tests still needs a tag.
 */
@Tag("fast")
class RunnableTestFixtureTest {

    private val repoRoot: File = File(
        System.getProperty("commonMain.root")
            ?: error("commonMain.root is not set — see shared/build.gradle.kts"),
    ).parentFile.parentFile.parentFile.parentFile

    private val fixtureFile = File(repoRoot, "config/test-fixtures/runnable-test-members.txt")

    /** A record of the shared table: its id, the agreed verdict, and its source. */
    private data class Fixture(val id: String, val runnable: Boolean, val source: String) {
        val verdict: String get() = if (runnable) "runnable" else "not-runnable"
    }

    @Test
    fun every_shared_fixture_matches_its_agreed_verdict() {
        val failures = loadFixtures()
            .filter { RunnableTestMember.hasRunnableTest(it.source) != it.runnable }
            .joinToString("\n") {
                "  ${it.id}: expected ${it.verdict}, " +
                    "got ${if (RunnableTestMember.hasRunnableTest(it.source)) "runnable" else "not-runnable"}"
            }

        assertTrue(
            failures.isEmpty(),
            "the Kotlin runnable-test predicate disagrees with the shared fixture table " +
                "at config/test-fixtures/runnable-test-members.txt:\n$failures\n\n" +
                "If a new JUnit annotation form is genuinely runnable, add it to the " +
                "table AND to infra/kiwi/sync.py — a table row added to one side only " +
                "is how this gate stops meaning anything.",
        )
    }

    @Test
    fun the_table_is_not_empty_and_covers_both_verdicts() {
        val fixtures = loadFixtures()
        assertTrue(fixtures.isNotEmpty(), "no fixtures parsed from ${fixtureFile.path}")
        // Without a not-runnable record a predicate that matched everything would
        // pass this suite. This is the positive control, not a coverage wish.
        assertTrue(
            fixtures.any { !it.runnable },
            "the table has no not-runnable record, so a predicate matching " +
                "everything would pass",
        )
        assertTrue(
            fixtures.any { it.runnable },
            "the table has no runnable record, so it asserts nothing about the real case",
        )
    }

    @Test
    fun fixture_ids_are_unique() {
        val ids = loadFixtures().map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(emptySet(), duplicates, "duplicate fixture ids")
    }

    /**
     * A record header is `=== <id> | <runnable|not-runnable>`; the source is every
     * following line up to the next header.
     *
     * A malformed record raises rather than being skipped: a table that silently
     * loses a record is how a green suite starts asserting nothing.
     */
    private fun loadFixtures(): List<Fixture> {
        assertTrue(
            fixtureFile.isFile,
            "missing shared fixture table: ${fixtureFile.path} — it is the contract " +
                "between the Kotlin gate and infra/kiwi/sync.py, and both suites read it",
        )
        val records = mutableListOf<Fixture>()
        var id: String? = null
        var runnable: Boolean? = null
        val body = mutableListOf<String>()

        fun flush() {
            val currentId = id
            val currentRunnable = runnable
            if (currentId != null && currentRunnable != null) {
                records += Fixture(currentId, currentRunnable, body.joinToString("\n"))
            }
        }

        fixtureFile.readLines().forEach { line ->
            if (line.startsWith(HEADER)) {
                flush()
                val (parsedId, verdict) = line.removePrefix(HEADER)
                    .split("|")
                    .map { it.trim() }
                check(verdict == "runnable" || verdict == "not-runnable") {
                    "fixture $parsedId: bad verdict '$verdict'"
                }
                id = parsedId
                runnable = verdict == "runnable"
                body.clear()
            } else if (id != null) {
                // Every other line inside a record is Kotlin source, comments
                // and blank lines included — the two readers must hand the
                // predicates byte-identical input or their agreement means
                // nothing. Lines above the first record are the table's own
                // header comment and are dropped.
                body.add(line)
            }
        }
        flush()

        assertTrue(records.isNotEmpty(), "no fixtures parsed from ${fixtureFile.path}")
        return records
    }

    private companion object {
        private const val HEADER = "=== "
    }
}
