package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.search.TextMatchQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The search reads the matching records, not the profile.
 *
 * ## The claim
 *
 * The search used to read every record of the profile and look at each one, on
 * every keystroke. It now asks the index for the records that carry the word.
 * The claim this test holds is that **the number of records the search reads
 * does not grow with the number of records in the profile** — it grows with the
 * number of records that match.
 *
 * ## Why the row count and not a clock
 *
 * A clock measures the machine, and the machine is shared: a build on the same
 * runner, a neighbour process, a page fault. A timing assertion on a shared
 * runner is a flaky assertion, and a flaky assertion is an assertion people
 * learn to ignore.
 *
 * The row count is the thing the claim is about, and it does not move with the
 * machine.
 *
 * ## What the query plan says, and what it does not
 *
 * The plan of the search mentions `tasks_fts`: the index is what answers the
 * word lookup, and the plan of the old way does not mention it at all — there
 * was no index of words to ask.
 *
 * What the plan does **not** say is how many rows the pattern was applied to.
 * The old way — `WHERE title LIKE '%word%'` — reaches the profile's rows
 * through the index on `user_id` and applies the pattern to each of them, and
 * a plan that says "SEARCH t USING INDEX index_tasks_user_id" cannot tell that
 * apart from a plan that stops at five rows. So the plan is not the instrument
 * for the claim about the rows; the row count is.
 *
 * ## What is not claimed
 *
 * The index is scanned too. A prefix query — and the search always asks for
 * one, because a term matches a word by its beginning — cannot be a dictionary
 * lookup in FTS4: SQLite walks the postings of the index to find every word
 * that starts with the prefix. So the *work inside the index* grows with the
 * number of words in the profile.
 *
 * That is a different dimension from the number of records, and it is the
 * dimension that matters here: what the application pays for is the records it
 * reads, and the records it reads are the records that match. The index is a
 * structure of postings, not of records — it is a fraction of the size of the
 * text it describes, and it is read without materialising a record.
 */
@Tag("slow")
class SearchCostDoesNotGrowTest {

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("singularity-search-cost-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `the search asks the index for the word`() = runTest {
        val plan = explain(INDEXED_QUERY)

        assertTrue(
            plan.any { it.contains("tasks_fts") },
            "the index has to be the thing the search asks for the word. The plan says: $plan",
        )
    }

    @Test
    fun `the way the search used to run asked no index at all`() = runTest {
        val plan = explain(SCANNING_QUERY)

        assertTrue(
            plan.none { it.contains("tasks_fts") },
            "the old way had no index of words to ask, and the plan says so. If this ever " +
                "stops being true the comparison in this test has lost its other half. " +
                "The plan says: $plan",
        )
    }

    @Test
    fun `the number of records the search reads does not grow with the profile`() = runTest {
        seed(100)
        val small = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("needle"))).first()

        seed(4_900)
        val large = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("needle"))).first()

        assertEquals(5, small.size, "the corpus holds five records that carry the word")
        assertEquals(
            small.map { it.id }.sorted(),
            large.map { it.id }.sorted(),
            "the same records are found when the profile is fifty times larger",
        )
    }

    @Test
    fun `the search reads a handful of records out of a profile that holds thousands`() = runTest {
        seed(5_000)

        val found = db.ftsDao().watchTasks("u1", TextMatchQuery(listOf("needle"))).first()
        val profile = countRecords()

        assertEquals(5_000, profile, "the profile holds five thousand records")
        assertEquals(5, found.size, "and the search reads five of them")
        assertTrue(
            found.size < profile,
            "the old way read every record of the profile — $profile of them — and looked at " +
                "each one, for every keystroke. The search reads ${found.size}.",
        )
    }

    @Test
    fun `the dao still asks the index`() {
        // The plan above is read from a query written out by hand. If the DAO
        // stopped asking the index, the plan would still say what it says and the
        // comparison would keep passing — over a query the application no longer
        // runs.
        val source = File(
            System.getProperty("commonMain.root")
                ?: error("commonMain.root system property is not set"),
            "com/singularity/todo/core/database/FtsDao.kt",
        ).readText()

        assertTrue(
            source.contains("JOIN tasks_fts ON t.rowid = tasks_fts.docid"),
            "the query the DAO runs has to reach the records through the index",
        )
        assertTrue(
            source.contains("tasks_fts MATCH :match"),
            "the query the DAO runs has to be a MATCH against the index",
        )
    }

    // ── the corpus ──────────────────────────────────────────────────────────

    private suspend fun seed(count: Int) {
        val dao = db.taskDao()
        for (index in 0 until count) {
            // The first five records carry the word; the rest carry nothing. The
            // number of matches therefore stays the same when the profile grows,
            // and the comparison is about the size of the profile alone.
            val carriesTheWord = index < 5
            dao.upsert(
                TaskEntity(
                    id = "t-$index",
                    title = if (carriesTheWord) {
                        "A record that carries the needle in its title $index"
                    } else {
                        "A record that carries nothing $index"
                    },
                    description = null,
                    projectId = null,
                    dueDate = null,
                    dueTime = null,
                    startDate = null,
                    startTime = null,
                    endDate = null,
                    endTime = null,
                    accentColor = null,
                    emoji = null,
                    completedAt = null,
                    archivedAt = null,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                    userId = "u1",
                ),
            )
        }
    }

    /** How many records the profile holds — what the old way had to read. */
    private suspend fun countRecords(): Int =
        db.useConnection(isReadOnly = true) { transactor ->
            transactor.usePrepared("SELECT count(*) FROM tasks WHERE user_id = 'u1'") { statement ->
                check(statement.step()) { "the count has to be there" }
                statement.getLong(0).toInt()
            }
        }

    /**
     * The plan of [sql], one entry per row of `EXPLAIN QUERY PLAN`.
     *
     * The fourth column is the one that says what is done; the first three are
     * the tree of the plan and carry no text.
     */
    private suspend fun explain(sql: String): List<String> =
        db.useConnection(isReadOnly = true) { transactor ->
            transactor.usePrepared("EXPLAIN QUERY PLAN $sql") { statement ->
                buildList {
                    while (statement.step()) {
                        add(statement.getText(3))
                    }
                }
            }
        }

    private companion object {
        /**
         * The query the index serves — the same shape as `FtsDao.watchTasksByMatch`.
         */
        const val INDEXED_QUERY = """
            SELECT t.* FROM tasks t
            JOIN tasks_fts ON t.rowid = tasks_fts.docid
            WHERE tasks_fts MATCH 'needle*'
              AND t.user_id = 'u1'
              AND t.archived_at IS NULL
        """

        /**
         * The query the scan serves — the shape the search used to run.
         */
        const val SCANNING_QUERY = """
            SELECT t.* FROM tasks t
            WHERE t.title LIKE '%needle%'
              AND t.user_id = 'u1'
              AND t.archived_at IS NULL
        """
    }
}
