// The harness measures the forms of a read; this test proves that the forms
// are the forms of *the same read*. The wait for the observer is a real wait:
// the observer of Room answers on its own clock, and a virtual clock would
// call it silent long before it had a chance to speak.
@file:Suppress("NoRunBlocking")

package com.singularity.todo.feature.tasks.performance

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The four forms of a read, on a real Room database.
 *
 * The harness ([ReadOverheadPerformanceHarness]) compares the forms as it
 * measures them, and a drift of the SQL fails the run — but only in the run
 * that measured it, on a dataset that lives outside the test suite. This test
 * makes the same two claims on a database the suite owns, so they are checked
 * on every run:
 *
 * 1. **the forms answer the same question.** The statement, the one-shot call,
 *    the Flow without the observer and the Flow are asked for the same task,
 *    and their answers are compared row by row. The SQL of a read is a copy of
 *    the DAO's; a copy that drifted would compare a statement with a Flow that
 *    answers a different question, and every number the harness published for
 *    that read would be a number about two different reads.
 * 2. **the observer sees the write.** The invalidation observer is what the
 *    "invalidation source" part of the overhead is priced on, and a pipeline
 *    whose observer never fires would price a part that does not exist. A
 *    collector is alive, the row is written again, and the observer has to
 *    answer — the wait is bounded, and a wait that runs out fails the test.
 * 3. **the control has no observer of its own.** The "invalidation source" is
 *    the difference between the DAO's Flow and the control — Room's own chain
 *    with the source of the emissions replaced. A control that had an observer
 *    would answer again on a write, and the difference would be pricing
 *    something that is not there. So the control is checked against the real
 *    thing: it answers once and ends, where the DAO's Flow stays alive and
 *    answers again after the write.
 *
 * The database is a file, not a memory: the statement form takes a **reader**
 * connection from the pool, and that is a different connection from the one
 * the writes take — a memory database has one.
 */
@Tag("slow")
@Tag("slow:data")
class ReadOverheadFormsIntegrationTest {
    private lateinit var fixture: DatabaseTestFixture
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        fixture = DatabaseTestFixture.onDisk()
        db = fixture.database()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `the four forms of a read answer the same rows`() =
        runBlocking {
            seed()

            for (read in Read.entries) {
                val statement = statementRows(db, read, TASK_ID, OWNER)
                val oneShot = oneShotRows(db, read, TASK_ID, OWNER)
                val plumbing = plumbingRows(db, read, TASK_ID, OWNER)
                val flow = flowRows(db, read, TASK_ID, OWNER)

                assertEquals(statement, oneShot, "the one-shot form of ${read.label} answers another question")
                assertEquals(statement, plumbing, "the Flow without the observer of ${read.label} answers another")
                assertEquals(statement, flow, "the Flow form of ${read.label} answers another question")
                assertTrue(
                    statement.answer().isNotEmpty(),
                    "the probe task carries no row of ${read.relation} — there is nothing to compare",
                )
            }
        }

    @Test
    fun `the observer of every read sees the write that invalidates it`() =
        runBlocking {
            seed()

            for (read in Read.entries) {
                val emissions = Channel<Unit>(Channel.UNLIMITED)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val collector =
                    scope.launch {
                        rowsFlow(db, read, TASK_ID, OWNER).collect { emissions.send(Unit) }
                    }
                try {
                    // The collector has to open the Flow before the write, or the
                    // re-read the write triggers would have no observer to reach.
                    assertNotNull(
                        withTimeoutOrNull(OBSERVER_TIMEOUT) { emissions.receive() },
                        "the observer of ${read.label} did not answer its first read",
                    )
                    val write = changeWriter(db, read, TASK_ID, OWNER)
                    write()
                    assertNotNull(
                        withTimeoutOrNull(OBSERVER_TIMEOUT) { emissions.receive() },
                        "the observer of ${read.label} did not see the write — the invalidation part " +
                            "of the overhead is priced on a reaction that does not happen",
                    )
                } finally {
                    collector.cancel()
                    scope.cancel()
                    emissions.close()
                }
            }
        }

    /**
     * The flow with one emission has no observer of its own.
     *
     * The "invalidation source" part of the overhead is the difference between
     * the DAO's Flow and the control — Room's own chain with the source of the
     * emissions replaced. The control is only that control if it really has no
     * observer: a control with one would answer again on a write, and the
     * difference would be pricing something that is not there.
     *
     * So the property is checked directly, and against the real thing:
     *
     * - the control's collection **ends** after its first answer — its source
     *   answers once and completes;
     * - the DAO's Flow, collected at the same time, **stays alive** and answers
     *   again after the write that invalidates it.
     *
     * A control built on Room's invalidation tracker would never end, and the
     * bounded wait below would run out — the test fails on a control that has
     * an observer of its own, and that is the difference the "invalidation
     * source" is priced on.
     */
    @Test
    fun `the flow with one emission has no observer of its own`() =
        runBlocking {
            seed()

            for (read in Read.entries) {
                val emissions = Channel<Unit>(Channel.UNLIMITED)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val collector =
                    scope.launch {
                        rowsFlow(db, read, TASK_ID, OWNER).collect { emissions.send(Unit) }
                    }
                try {
                    assertNotNull(
                        withTimeoutOrNull(OBSERVER_TIMEOUT) { emissions.receive() },
                        "the observer of ${read.label} did not answer its first read",
                    )

                    val answers =
                        withTimeoutOrNull(OBSERVER_TIMEOUT) {
                            plumbingFlow(db, read, TASK_ID, OWNER).toList()
                        }
                    assertNotNull(
                        answers,
                        "the flow with one emission of ${read.label} did not end — it has an observer " +
                            "of its own, and the invalidation source is priced on a control that has one",
                    )
                    assertEquals(
                        1,
                        answers.size,
                        "the flow with one emission of ${read.label} answered more than once",
                    )
                    assertTrue(
                        answers.first().answer().isNotEmpty(),
                        "the probe task carries no row of ${read.relation} — there is nothing to compare",
                    )

                    assertTrue(
                        collector.isActive,
                        "the DAO's Flow of ${read.label} ended — it has no observer to keep it alive",
                    )
                    val write = changeWriter(db, read, TASK_ID, OWNER)
                    write()
                    assertNotNull(
                        withTimeoutOrNull(OBSERVER_TIMEOUT) { emissions.receive() },
                        "the observer of ${read.label} did not see the write — the invalidation source " +
                            "is priced on a reaction that does not happen",
                    )
                } finally {
                    collector.cancel()
                    scope.cancel()
                    emissions.close()
                }
            }
        }

    /**
     * A read scoped to its owner does not answer a foreign task.
     *
     * The harness measures a **copy** of the DAO's SQL, and the copy is checked
     * against the DAO by comparing the rows of the forms. That comparison only
     * notices a drifted copy if the database holds a case where the two
     * disagree — and a database that holds only the owner's rows does not: a
     * copy that dropped the `user_id` predicate would answer the same rows as
     * the DAO on every task the owner owns.
     *
     * So the fixture carries a second user with a task of their own, an edge
     * and a tag. Asking for that task **as the owner** must answer nothing:
     * the DAO scopes the read through the task's own row, and so must the
     * copy. A copy that lost the predicate would answer the foreign rows, and
     * the forms would disagree.
     */
    @Test
    fun `a read scoped to its owner does not answer a foreign task`() =
        runBlocking {
            seed()

            for (read in Read.entries) {
                val statement = statementRows(db, read, FOREIGN_TASK_ID, OWNER)
                val oneShot = oneShotRows(db, read, FOREIGN_TASK_ID, OWNER)
                val plumbing = plumbingRows(db, read, FOREIGN_TASK_ID, OWNER)
                val flow = flowRows(db, read, FOREIGN_TASK_ID, OWNER)

                assertTrue(
                    statement.answer().isEmpty(),
                    "the statement form of ${read.label} answered the rows of a task owned by another user — " +
                        "the copy of the DAO's SQL has lost its user scope",
                )
                assertEquals(statement, oneShot, "the one-shot form of ${read.label} answers another question")
                assertEquals(statement, plumbing, "the Flow without the observer of ${read.label} answers another")
                assertEquals(statement, flow, "the Flow form of ${read.label} answers another question")
            }
        }

    /** The rows of a read, in the shape the DAO answers in. */
    private fun ReadRows.answer(): List<Any> =
        when (this) {
            is ReadRows.Texts -> values
            is ReadRows.Dependencies -> values
        }

    /** The DAO's Flow of a read — the flow the observer lives in. */
    private fun rowsFlow(
        db: AppDatabase,
        read: Read,
        taskId: String,
        owner: UserId,
    ): Flow<*> {
        val dao = db.taskDao()
        return when (read) {
            Read.BlockingEdges -> dao.getBlockingEdgeIdsForUser(taskId, owner.value, BLOCKING_VERB)
            Read.TypedDependencies -> dao.observeTypedDependenciesForUser(taskId, owner.value)
            Read.TagIds -> dao.getTagIdsForUser(taskId, owner.value)
        }
    }

    /**
     * The rows the forms are asked about.
     *
     * Two users: the owner of the probe task, with an edge and a tag of their
     * own, and a second user with a task, an edge and a tag of **theirs**. The
     * second user's rows are what makes the user scope of the reads visible:
     * without them a copy of the DAO's SQL that lost the `user_id` predicate
     * would answer the same rows as the DAO, and the comparison would not
     * notice.
     */
    private suspend fun seed() {
        val dao = db.taskDao()
        val now = System.currentTimeMillis()
        dao.upsert(task(TASK_ID, now, OWNER))
        dao.upsert(task(BLOCKED_BY, now, OWNER))
        dao.upsertDependencyForUser(TASK_ID, BLOCKED_BY, BLOCKING_VERB, OWNER.value)
        dao.upsertTagCrossRefForUser(TASK_ID, TAG_ID, OWNER.value)

        dao.upsert(task(FOREIGN_TASK_ID, now, FOREIGN_OWNER))
        dao.upsert(task(FOREIGN_BLOCKED_BY, now, FOREIGN_OWNER))
        dao.upsertDependencyForUser(FOREIGN_TASK_ID, FOREIGN_BLOCKED_BY, BLOCKING_VERB, FOREIGN_OWNER.value)
        dao.upsertTagCrossRefForUser(FOREIGN_TASK_ID, FOREIGN_TAG_ID, FOREIGN_OWNER.value)
    }

    private fun task(
        id: String,
        now: Long,
        owner: UserId,
    ): TaskEntity =
        TaskEntity(
            id = id,
            title = "Task $id",
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
            someday = false,
            archivedAt = null,
            isPinned = false,
            createdAt = now,
            updatedAt = now,
            userId = owner.value,
        )

    private companion object {
        /** The verb the blocking read asks for. */
        private const val BLOCKING_VERB = "BLOCKS"

        private const val TASK_ID = "task-1"
        private const val BLOCKED_BY = "task-2"
        private const val TAG_ID = "tag-1"

        private const val FOREIGN_TASK_ID = "task-3"
        private const val FOREIGN_BLOCKED_BY = "task-4"
        private const val FOREIGN_TAG_ID = "tag-2"

        private val OWNER = UserId("owner-1")

        /** A second user, whose rows the reads must not answer. */
        private val FOREIGN_OWNER = UserId("owner-2")

        /** How long the observer gets to answer before the test is broken. */
        private val OBSERVER_TIMEOUT = 30.seconds
    }
}
