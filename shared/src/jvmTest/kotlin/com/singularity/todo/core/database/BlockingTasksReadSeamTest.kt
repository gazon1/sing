@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.database

import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The user-scoped blocking-tasks read, next to the SQL it answered before its
 * rewrite (#3195).
 *
 * The old form resolved the owner of the blocker beside the user's task list
 * (`b.user_id = :userId` next to `d.task_id IN (SELECT id FROM tasks WHERE
 * user_id = :userId)`); the new one takes both task rows by primary key. The two
 * are not obviously the same question — the subquery form drops a blocker whose
 * task no longer exists, and so does the join — so the test runs the old SQL
 * next to the DAO and compares the rows.
 */
@Tag("slow")
@Tag("slow:data")
class BlockingTasksReadSeamTest {
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
    fun `blocking-tasks read answers as the old form did`() =
        runTest {
            val dao = db.taskDao()
            val now = System.currentTimeMillis()

            fun task(
                id: String,
                uid: String,
                completed: Boolean = false,
                archived: Boolean = false,
            ) = TaskEntity(
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
                completedAt = if (completed) now else null,
                someday = false,
                archivedAt = if (archived) now else null,
                isPinned = false,
                createdAt = now,
                updatedAt = now,
                userId = uid,
            )

            dao.upsert(task("t1", "u1"))
            dao.upsert(task("t2", "u1"))
            dao.upsert(task("done", "u1", completed = true))
            dao.upsert(task("trashed", "u1", archived = true))
            dao.upsert(task("theirs", "u2"))
            dao.upsert(task("theirs-blocker", "u2"))

            // The open blocker of u1's task.
            dao.upsertDependencyForUser("t1", "t2", DependencyVerb.BLOCKS.name, "u1")
            // A blocker that is finished, and one that is trashed: neither is in the way.
            dao.upsertDependencyForUser("t1", "done", DependencyVerb.BLOCKS.name, "u1")
            dao.upsertDependencyForUser("t1", "trashed", DependencyVerb.BLOCKS.name, "u1")
            // A blocker of another profile, hanging on u1's task: not in u1's way.
            dao.upsertDependencyForUser("t1", "theirs-blocker", DependencyVerb.BLOCKS.name, "u1")
            // Another kind of edge: the read asks about one kind.
            dao.upsertDependencyForUser("t1", "t2", DependencyVerb.FOLLOWS_UP.name, "u1")
            // The other profile's own blocked task.
            dao.upsertDependencyForUser("theirs", "theirs-blocker", DependencyVerb.BLOCKS.name, "u2")
            // An edge whose owning task does not exist: the backup-restore path
            // can write one, and neither form may show it.
            dao.upsertDependency(TaskDependencyCrossRef("ghost", "t2", DependencyVerb.BLOCKS.name))

            // The owner sees the open blocker of its own task — and only its own.
            assertEquals(
                listOf("t2"),
                dao.blockingTasksForUser("t1", "u1", DependencyVerb.BLOCKS.name).map { it.id },
            )
            // The old form answered the same.
            assertEquals(
                dao.blockingTasksForUser("t1", "u1", DependencyVerb.BLOCKS.name).map { it.id }.sorted(),
                rawRows(
                    """
                    SELECT b.id FROM task_dependencies d
                    JOIN tasks b ON b.id = d.depends_on_task_id AND b.user_id = ?3
                    WHERE d.task_id = ?1 AND d.verb = ?2
                      AND d.task_id IN (SELECT id FROM tasks WHERE user_id = ?3)
                      AND b.completed_at IS NULL AND b.archived_at IS NULL
                    """.trimIndent(),
                    listOf("t1", DependencyVerb.BLOCKS.name, "u1"),
                ).sorted(),
            )
            // Another profile sees none of them.
            assertEquals(emptyList(), dao.blockingTasksForUser("t1", "u2", DependencyVerb.BLOCKS.name))
            // The other profile sees its own blocking task — and only its own.
            assertEquals(
                listOf("theirs-blocker"),
                dao.blockingTasksForUser("theirs", "u2", DependencyVerb.BLOCKS.name).map { it.id },
            )
            assertEquals(emptyList(), dao.blockingTasksForUser("theirs", "u1", DependencyVerb.BLOCKS.name))
            // A task that does not exist has nothing to show.
            assertEquals(emptyList(), dao.blockingTasksForUser("nosuch", "u1", DependencyVerb.BLOCKS.name))
            // The orphan edge belongs to nobody.
            assertEquals(emptyList(), dao.blockingTasksForUser("ghost", "u1", DependencyVerb.BLOCKS.name))
        }

    /** The rows of a statement — the query the DAO ran before the rewrite. */
    private suspend fun rawRows(
        sql: String,
        params: List<String>,
    ): List<String> =
        db.useConnection(isReadOnly = true) { transactor ->
            transactor.usePrepared(sql) { statement ->
                params.forEachIndexed { index, value -> statement.bindText(index + 1, value) }
                buildList {
                    while (statement.step()) {
                        add(statement.getText(0))
                    }
                }
            }
        }
}
