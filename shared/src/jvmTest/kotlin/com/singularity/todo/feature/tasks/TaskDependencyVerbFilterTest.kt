@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dependency set a task carries is the set of the edges that **block** it.
 *
 * The cross-reference table records the kind of every relationship. A task that
 * "follows up" on another is not waiting for it, and reporting it as waiting is
 * what this filter fixes.
 *
 * The filter lives in the repository rather than in the SQL because the same table
 * is read by the cycle analysis, the backup export and the sync payload, and all
 * three need *every* edge. So the narrowed read is asserted here — against the real
 * database, where the join table actually is.
 *
 * The fake cannot answer this: it stores the task's dependency set as a field of the
 * task rather than resolving it from the join table, so there is no kind to narrow.
 */
@Tag("slow")
@Tag("slow:data")
@Epic("Tasks")
@Feature("Tasks")
class TaskDependencyVerbFilterTest {
    private val alice = UserId("alice")

    private val fixtureLazy = lazy { DatabaseTestFixture.inMemory() }
    private val fixture: DatabaseTestFixture by fixtureLazy

    private val dbLazy =
        lazy {
            fixture.database()
        }
    private val db: AppDatabase by dbLazy

    @AfterTest
    fun tearDown() {
        if (fixtureLazy.isInitialized()) fixture.close()
    }

    private fun task(
        id: String,
        title: String = "Task $id",
    ) = Task(
        id = TaskId(id),
        title = title,
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        userId = alice,
    )

    private fun repository() =
        run {
            val auth = FakeAuthRepository(Session.Anonymous(alice))
            val currentUser = FakeProfileAwareCurrentUser(auth)
            TaskRepositoryImpl(
                taskDao = db.taskDao(),
                textIndex = db.textIndex(currentUser),
                clock = kotlin.time.Clock.System,
                currentUser = currentUser,
                syncRepository = FakeSyncRepository(),
                dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
                unitOfWork = FakeUnitOfWork(),
            )
        }

    @Test
    fun `a blocking edge is in the task's dependency set`() =
        runTest {
            val repo = repository()
            repo.create(task("dep", "Dependency"))
            repo.create(task("t1", "Blocked"))
            repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

            val loaded = repo.observe(TaskId("t1")).first()

            assertEquals(setOf(TaskId("dep")), loaded?.blockedBy)
        }

    @Test
    fun `an edge of another kind is not in the task's dependency set`() =
        runTest {
            val repo = repository()
            repo.create(task("dep", "Dependency"))
            repo.create(task("t1", "Follower"))
            repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.FOLLOWS_UP, enabled = true)

            val loaded = repo.observe(TaskId("t1")).first()

            assertTrue(
                loaded?.blockedBy.isNullOrEmpty(),
                "a task that follows up on another is not waiting for it: ${loaded?.blockedBy}",
            )
        }

    @Test
    fun `the list read narrows the same way as the single-task read`() =
        runTest {
            val repo = repository()
            repo.create(task("dep", "Dependency"))
            repo.create(task("t1", "Follower"))
            repo.create(task("t2", "Blocked"))
            repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.FOLLOWS_UP, enabled = true)
            repo.setDependency(TaskId("t2"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

            val listed = repo.observeByFilter(TaskFilter.All).first().associateBy { it.id }

            assertTrue(listed[TaskId("t1")]?.blockedBy.isNullOrEmpty())
            assertEquals(setOf(TaskId("dep")), listed[TaskId("t2")]?.blockedBy)
        }

    @Test
    fun `a subtask list carries the tags and blockers of its own rows only`() =
        runTest {
            val repo = repository()
            val tagged = TagId.fromString("tag-1")
            repo.create(task("parent", "Parent"))
            repo
                .create(
                    task("child", "Child").copy(
                        parentTaskId = TaskId("parent"),
                        tags = listOf(tagged),
                    ),
                ).getOrThrow()
            // An unrelated row with an edge of its own: the profile-wide read
            // materialised it, the scoped read must not need it.
            repo.create(task("elsewhere", "Elsewhere")).getOrThrow()
            repo.create(task("foreign-dep", "Foreign dep")).getOrThrow()
            repo.setDependency(TaskId("elsewhere"), TaskId("foreign-dep"), DependencyVerb.BLOCKS, enabled = true)

            val child = repo.observeSubtasks(TaskId("parent")).first().single()

            assertEquals(listOf(tagged), child.tags)
            assertTrue(
                child.blockedBy.isEmpty(),
                "another row's blocker is not this subtask's: ${child.blockedBy}",
            )
        }

    /**
     * The guard reads one task's prerequisites, not the profile.
     *
     * The task-detail screen used to collect every task of the profile to answer
     * this question, which is what exhausted a 192 MiB heap on a 50k dataset before
     * the screen rendered.
     */
    @Test
    fun `the detail guard reads only the task it is asked about`() =
        runTest {
            val repo = repository()
            repo.create(task("open", "Open prerequisite"))
            repo
                .create(
                    task("done", "Finished").copy(
                        completedAt = kotlin.time.Instant.fromEpochMilliseconds(1_000),
                    ),
                ).getOrThrow()
            repo.create(task("t1", "Blocked"))
            repo.create(task("unrelated", "Unrelated"))
            repo.setDependency(TaskId("t1"), TaskId("open"), DependencyVerb.BLOCKS, enabled = true)
            repo.setDependency(TaskId("t1"), TaskId("done"), DependencyVerb.BLOCKS, enabled = true)
            repo.setDependency(TaskId("unrelated"), TaskId("open"), DependencyVerb.BLOCKS, enabled = true)

            assertEquals(
                listOf("open"),
                repo.openBlockersOf(TaskId("t1")).map { it.id.value },
                "the answer names the open prerequisite of this task and nothing else",
            )
            assertTrue(
                repo.openBlockersOf(TaskId("unrelated")).size == 1,
                "the read is per task, not a cached profile-wide answer",
            )
        }

    @Test
    fun `the list read carries both projections of the same edge table`() =
        runTest {
            // The blocking set and the follow-up set are built from one read of
            // `task_dependencies`. Splitting that read into two passes is how the
            // two projections drift apart — a task would carry one edge in the
            // blocked list and a different one in the followed-up list, from the
            // same rows.
            val repo = repository()
            repo.create(task("dep", "Dependency"))
            repo.create(task("t1", "Both ways"))
            repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)
            repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.FOLLOWS_UP, enabled = true)

            val listed = repo.observeByFilter(TaskFilter.All).first().single { it.id == TaskId("t1") }

            assertEquals(setOf(TaskId("dep")), listed.blockedBy, "the blocking projection")
            assertEquals(setOf(TaskId("dep")), listed.followsUp, "the follow-up projection")
        }

    @Test
    fun `the typed read still sees every edge`() =
        runTest {
            val repo = repository()
            repo.create(task("dep", "Dependency"))
            repo.create(task("t1", "Follower"))
            repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.FOLLOWS_UP, enabled = true)

            // The typed read is the graph's own read: the repository no longer offers
            // a typed observer (nothing outside the graph needed one), so the test asks
            // the table that holds the edges.
            val typed = db.taskDao().observeTypedDependenciesForUser("t1", alice.value).first()

            assertEquals(1, typed.size, "the graph keeps every edge: ${typed.map { it.verb }}")
            assertEquals(DependencyVerb.FOLLOWS_UP.name, typed.single().verb)
        }
}
