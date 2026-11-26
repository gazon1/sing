package com.singularity.todo.core.database

import com.singularity.todo.core.search.TextMatchQuery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The title door: what it answers, and in what order.
 *
 * ## The change this test holds
 *
 * The door that offers a title for a link used to be one statement. It joined the
 * index to the table, selected the whole task row *and* the position of the match
 * inside it, and sorted all of that by `updated_at`.
 *
 * It is now two statements: the first sorts the ids of the matched tasks, the
 * second reads the rows of the survivors. The sorter carries an id instead of a
 * row and a position — and the measurement that decided it is in
 * `docs/architecture/fts-ordering-performance.md`.
 *
 * ## What is asserted
 *
 * 1. the two-phase answer is the answer of the one-statement form — the same
 *    tasks, in the same order. The split is an implementation detail and must
 *    not be visible from the outside.
 * 2. the order among tasks updated at the same moment is a rule and not an
 *    accident: the greater `rowid` comes first, and it comes first every time
 *    the door is asked. The rule is local to the database as it is — `rowid` is
 *    SQLite's own row number, and a table whose primary key is a text column
 *    has no durable insertion chronology to promise — so the test asserts the
 *    rule, not a creation date.
 * 3. the rows of the survivors are read by their id and not by a scan of the
 *    table — that is what makes the second read cheap. The rows have to be
 *    found by their primary key: the ownership filter may not become the way
 *    they are found, or the planner walks the rows of the user instead.
 * 4. the statement that does the ordering selects the id alone: neither the
 *    whole row nor the position of the match comes back into it.
 * 5. the ownership and the archive filters are stated in both phases, so a task
 *    that belongs to somebody else or is archived is never offered — and the
 *    second phase keeps the check of its own, so a foreign id handed to it
 *    reads nothing.
 * 6. the two phases are one transaction, so they are one answer: the rows on
 *    offer are the rows the order was decided over.
 *
 * ## Why there is no clock here
 *
 * A clock measures the machine, and the machine is shared: a build on the same
 * runner, a neighbour process, a page fault. A timing assertion on a shared
 * runner is a flaky assertion, and a flaky assertion is one people learn to
 * ignore.
 *
 * The claim of the change is not "this is faster" — that is what the
 * measurement harness is for, and it is measured on datasets of ten, fifty and a
 * hundred thousand tasks. The claim of *this* test is narrower: the answer did
 * not change, and the statement that does the ordering carries less. Both are
 * claims about shape, and shape does not move with the machine.
 */
@Tag("slow")
@Tag("slow:data")
class FtsTaskTitleOrderingTest {
    private lateinit var fixture: DatabaseTestFixture
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        fixture = DatabaseTestFixture.inMemory()
        db = fixture.database()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `the two-phase door answers exactly what the one-statement form answers`() =
        runTest {
            seed(count = 40, distinctTimestamps = true)

            val throughTheDoor = db.ftsDao().searchTaskTitles("u1", QUERY, limit = 10)
            val throughOneStatement = oneStatementIds(limit = 10)

            assertEquals(
                throughOneStatement,
                throughTheDoor.map { it.id },
                "the door has to answer the same tasks, in the same order, as the single " +
                    "statement it replaced",
            )
            assertEquals(
                "A record that carries the needle 39",
                throughTheDoor.first().title,
                "and the rows it answers with have to be the rows of those tasks",
            )
        }

    @Test
    fun `among tasks updated at the same moment the greater rowid comes first`() =
        runTest {
            // Three tasks, one moment, three rows. Before the change the order
            // among them was the order SQLite's sorter happened to give them —
            // the order of the bytes of the rows. Now it is a rule: the greater
            // rowid first, and the same rule every time.
            listOf("t1", "t2", "t3").forEach { id ->
                db.taskDao().upsert(task(id = id, title = "A record that carries the needle", updatedAt = 1_000L))
            }

            val ids = db.ftsDao().searchTaskTitles("u1", QUERY, limit = 10).map { it.id }

            assertEquals(
                listOf("t3", "t2", "t1"),
                ids,
                "the task with the greater rowid comes first among the tasks updated at the " +
                    "same moment",
            )
        }

    @Test
    fun `the order among tasks updated at the same moment is the same every time`() =
        runTest {
            listOf("t1", "t2", "t3", "t4").forEach { id ->
                db.taskDao().upsert(task(id = id, title = "A record that carries the needle", updatedAt = 1_000L))
            }

            val answers =
                (1..5).map {
                    db.ftsDao().searchTaskTitles("u1", QUERY, limit = 10).map { task -> task.id }
                }

            assertEquals(1, answers.distinct().size, "the door has to answer the same order every time")
        }

    @Test
    fun `the rows of the survivors are read by their id and not by a scan of the table`() =
        runTest {
            seed(count = 500, distinctTimestamps = true)

            val plan = explain(SECOND_PHASE_SQL)

            assertTrue(
                plan.any { it.contains("sqlite_autoindex") },
                "the rows of the survivors have to be looked up by their primary key. The plan says: $plan",
            )
            assertFalse(
                plan.any { it.startsWith("SCAN t") },
                "a scan of the table would read every task of the profile to answer a question " +
                    "about ten of them. The plan says: $plan",
            )
            assertFalse(
                plan.any { "index_tasks_user_id" in it },
                "the ownership filter must not become the way the rows are found: the planner " +
                    "would walk the rows of the user and check the id of every one of them, and " +
                    "reading ten rows would become a scan of the tasks of the profile. " +
                    "The plan says: $plan",
            )
        }

    @Test
    fun `a row of another user is not read by its id`() =
        runTest {
            // The second phase reads rows by their id, and the ids it is given
            // come from the first phase — but the second phase has to keep the
            // ownership check of its own, so that a caller that hands it a
            // foreign id gets nothing.
            db.taskDao().upsert(task(id = "theirs", title = "A record that carries the needle", userId = "u2"))

            val rows = db.ftsDao().searchTasksByIds("u1", listOf("theirs"))

            assertTrue(
                rows.isEmpty(),
                "a task of another user must not be readable by its id. The rows are: $rows",
            )
        }

    @Test
    fun `an archived row is not read by its id`() =
        runTest {
            db.taskDao().upsert(
                task(id = "archived", title = "A record that carries the needle")
                    .copy(archivedAt = 1_000L),
            )

            val rows = db.ftsDao().searchTasksByIds("u1", listOf("archived"))

            assertTrue(
                rows.isEmpty(),
                "an archived task must not be readable by its id. The rows are: $rows",
            )
        }

    @Test
    fun `the statement that orders carries the id and not the row`() {
        // The plan of the statement cannot say what the sorter carries — SQLite
        // does not tell that. The source can: the statement is written down
        // there, and this is the thing the change was about.
        val source = daoSource().replace(WHITESPACE, " ")

        assertTrue(
            source.contains(
                "SELECT t.id FROM tasks t JOIN tasks_fts ON t.rowid = tasks_fts.docid",
            ),
            "the ordering statement has to select the id alone. The source says: $source",
        )
        assertFalse(
            source.contains(OLD_TITLE_STATEMENT),
            "the statement that selected the whole row and the position of the match must not " +
                "come back into the title door — the sorter would carry both again. " +
                "The source says: $source",
        )
    }

    @Test
    fun `the two phases of the door are one transaction`() {
        // The snapshot is not observable from the outside — it is a property of
        // the transaction the two reads share — so the source is what says it.
        val source = daoSource().replace(WHITESPACE, " ")

        assertTrue(
            source.contains("@Transaction suspend fun searchTaskTitles("),
            "the two phases have to run in one transaction. Without it they are two answers: a " +
                "task whose title was edited between the phases could be offered even though it no " +
                "longer matches, and a row could be read in a state the order no longer describes. " +
                "The source says: $source",
        )
    }

    @Test
    fun `a task of another user and an archived task are never offered`() =
        runTest {
            db.taskDao().upsert(task(id = "mine", title = "A record that carries the needle"))
            db.taskDao().upsert(task(id = "theirs", title = "A record that carries the needle", userId = "u2"))
            db.taskDao().upsert(
                task(id = "archived", title = "A record that carries the needle")
                    .copy(archivedAt = 1_000L),
            )

            val ids = db.ftsDao().searchTaskTitles("u1", QUERY, limit = 10).map { it.id }

            assertEquals(listOf("mine"), ids)
        }

    // ── the corpus ──────────────────────────────────────────────────────────

    private suspend fun seed(
        count: Int,
        distinctTimestamps: Boolean,
    ) {
        val dao = db.taskDao()
        for (index in 0 until count) {
            dao.upsert(
                task(
                    id = "t-$index",
                    title = "A record that carries the needle $index",
                    updatedAt = if (distinctTimestamps) 1_000L + index else 1_000L,
                ),
            )
        }
    }

    private fun task(
        id: String,
        title: String,
        updatedAt: Long = 1_000L,
        userId: String = "u1",
    ) = TaskEntity(
        id = id,
        title = title,
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
        updatedAt = updatedAt,
        userId = userId,
    )

    // ── the one statement the door replaced ──────────────────────────────────

    /**
     * The ids the single statement answers with, in its order.
     *
     * The statement is the one the door used to be: the whole row, joined to the
     * index, sorted by `updated_at` and by the tiebreaker. The door answers
     * with the same order — the difference is what the sorter carried, not what
     * came out.
     */
    private suspend fun oneStatementIds(limit: Int): List<String> =
        db.useConnection(isReadOnly = true) { transactor ->
            transactor.usePrepared(ONE_STATEMENT_SQL) { statement ->
                statement.bindText(1, MATCH)
                statement.bindText(2, "u1")
                statement.bindLong(3, limit.toLong())
                buildList {
                    while (statement.step()) {
                        add(statement.getText(0))
                    }
                }
            }
        }

    /** The plan of a statement, one line per step. */
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

    /** The source of the DAO, read from the tree the tests are given the path of. */
    private fun daoSource(): String =
        File(
            System.getProperty("coreDatabase.root")
                ?: error("coreDatabase.root system property is not set"),
            "com/singularity/todo/core/database/FtsDao.kt",
        ).readText()

    private companion object {
        /** The query the caller means: the word "needle". */
        val QUERY: TextMatchQuery = TextMatchQuery(listOf("needle"))

        /** The expression the production code builds for that word. */
        val MATCH: String =
            requireNotNull(FtsMatchQuery.expression(QUERY)) {
                "the expression of a word with a letter in it is never empty"
            }

        /** Whitespace runs, collapsed — the source is compared as one line. */
        val WHITESPACE = Regex("\\s+")

        /**
         * The statement the door used to be, in one line.
         *
         * It is quoted here so that the test fails when the statement comes
         * back, and says what it is when it does.
         */
        const val OLD_TITLE_STATEMENT =
            "SELECT t.*, offsets(tasks_fts) AS match_offsets FROM tasks t " +
                "JOIN tasks_fts ON t.rowid = tasks_fts.docid " +
                "WHERE tasks_fts MATCH :match AND t.user_id = :userId " +
                "AND t.archived_at IS NULL ORDER BY t.updated_at DESC LIMIT :limit"

        /** The one statement: the whole row, the position of the match, the order. */
        const val ONE_STATEMENT_SQL =
            """
            SELECT t.*, offsets(tasks_fts) AS match_offsets FROM tasks t
            JOIN tasks_fts ON t.rowid = tasks_fts.docid
            WHERE tasks_fts MATCH :match
              AND t.user_id = :userId
              AND t.archived_at IS NULL
            ORDER BY t.updated_at DESC, t.rowid DESC
            LIMIT :limit
            """

        /**
         * The second phase: the rows of the survivors, read by their id.
         *
         * The ownership and the archive check are written as a correlated check
         * of the row by its id — the form the production statement uses, and the
         * form that lets the planner find the rows by their primary key.
         */
        const val SECOND_PHASE_SQL =
            """
            SELECT t.* FROM tasks t
            WHERE t.id IN ('t-1', 't-2')
              AND EXISTS (
                SELECT 1 FROM tasks o
                WHERE o.id = t.id
                  AND o.user_id = 'u1'
                  AND o.archived_at IS NULL
              )
            """
    }
}
