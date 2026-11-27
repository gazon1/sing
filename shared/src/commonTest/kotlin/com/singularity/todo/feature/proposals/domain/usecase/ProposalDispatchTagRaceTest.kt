@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.proposals.domain.usecase

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.TestUsers
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Covers the lock around [ProposalDispatch]'s additive tag plans.
 *
 * [ProposalPlan.AddTags] and [ProposalPlan.RemoveTags] read the task's tags and write the
 * whole set back. Two of them at once — a confirm racing the bulk confirm — both read the
 * set that existed before either wrote, and the second write drops the first one's tags
 * while reporting success. The dispatch serialises them, and these tests hold one write
 * open to prove the second waits.
 *
 * They fail if the lock is removed, and they use no sleeps: the order is set by a
 * [CompletableDeferred] gate.
 */
@Tag("fast")
@Epic("Proposals")
@Feature("Dispatch")
class ProposalDispatchTagRaceTest {
    private val clock = FakeClock()
    private val user: UserId = TestUsers.DEFAULT
    private val now: Instant = Instant.parse("2026-01-15T12:00:00Z")
    private val taskId = TaskId("t1")
    private val tagA = TagId.fromString("tag-a")
    private val tagB = TagId.fromString("tag-b")

    private fun seedTask(): FakeTaskRepository =
        FakeTaskRepository().apply {
            seed(
                Task(
                    id = taskId,
                    title = "A task",
                    userId = user,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

    private fun dispatch(tasks: FakeTaskRepository): ProposalDispatch {
        val projects = FakeProjectsRepository(FakeProfileAwareCurrentUser(user))
        return ProposalDispatch(
            tasks = tasks,
            tags = FakeTagsRepository(),
            checklist = FakeChecklistRepository(),
            timeTracking = FakeTimeTrackingRepository(clock),
            notes = FakeNotesRepository(),
            projects = projects,
            deleteProject = DeleteProjectUseCase(projects, tasks),
            clock = clock,
        )
    }

    /** A repository whose first tag write is held open, so a second can start behind it. */
    private class GatedTagRepository : FakeTaskRepository() {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var setTagsCalls = 0
            private set

        override suspend fun setTags(
            taskId: TaskId,
            tagIds: List<TagId>,
            actor: com.singularity.todo.feature.tags.domain.model.TagEditActor,
        ): Result<Unit> {
            val call = setTagsCalls++
            if (call == 0) {
                writeStarted.complete(Unit)
                releaseWrite.await()
            }
            return super.setTags(taskId, tagIds, actor)
        }
    }

    @Test
    fun `a second tag plan waits for the first, so neither change is lost`() =
        runTest {
            val tasks = GatedTagRepository()
            tasks.seed(
                Task(
                    id = taskId,
                    title = "A task",
                    userId = user,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val dispatch = dispatch(tasks)

            val addingA = launch { dispatch.dispatch(ProposalPlan.AddTags(taskId, setOf(tagA)), user) }
            // The first plan has read the tags and is held inside its write.
            tasks.writeStarted.await()

            // The second plan starts while the first is in flight.
            val addingB = launch { dispatch.dispatch(ProposalPlan.AddTags(taskId, setOf(tagB)), user) }
            runCurrent()

            assertEquals(
                1,
                tasks.setTagsCalls,
                "the second plan must wait for the first, not read the set it is about to replace",
            )

            tasks.releaseWrite.complete(Unit)
            runCurrent()
            addingA.join()
            addingB.join()

            assertEquals(
                setOf(tagA, tagB),
                tasks.tasks.value["t1"]
                    ?.tags
                    ?.toSet(),
                "both plans' tags are on the task: the later write read what the earlier one wrote",
            )
        }

    @Test
    fun `an add that starts during a remove keeps both decisions`() =
        runTest {
            val tasks = GatedTagRepository()
            tasks.seed(
                Task(
                    id = taskId,
                    title = "A task",
                    tags = listOf(tagA),
                    userId = user,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val dispatch = dispatch(tasks)

            val removingA = launch { dispatch.dispatch(ProposalPlan.RemoveTags(taskId, setOf(tagA)), user) }
            tasks.writeStarted.await()

            val addingB = launch { dispatch.dispatch(ProposalPlan.AddTags(taskId, setOf(tagB)), user) }
            runCurrent()

            assertEquals(1, tasks.setTagsCalls, "the add must wait for the remove")

            tasks.releaseWrite.complete(Unit)
            runCurrent()
            removingA.join()
            addingB.join()

            assertEquals(
                setOf(tagB),
                tasks.tasks.value["t1"]
                    ?.tags
                    ?.toSet(),
                "the removal stands, and the tag added afterwards is there",
            )
        }

    /**
     * The rollback contract is untouched by the lock.
     *
     * A tag write has nothing to take back — it sets a whole set, so a retry sets the same
     * one — and the dispatch reports the failure rather than swallowing it. The failure has
     * to leave the lock free, or every later plan on this instance would wait forever.
     */
    @Test
    fun `a refused tag plan reports the failure and does not hold the lock`() =
        runTest {
            val tasks = FakeTaskRepository()
            tasks.seed(
                Task(
                    id = taskId,
                    title = "A task",
                    tags = listOf(tagA),
                    userId = user,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            tasks.setTagsOverride = Result.failure(IllegalStateException("refused"))
            val dispatch = dispatch(tasks)

            val failure =
                assertFailsWith<IllegalStateException> {
                    runCatchingCancellable {
                        dispatch.dispatch(ProposalPlan.AddTags(taskId, setOf(tagB)), user)
                    }.getOrThrow()
                }
            assertEquals("refused", failure.message, "the dispatch reports the real cause")

            assertEquals(
                listOf(tagA),
                tasks.tasks.value["t1"]?.tags,
                "the refused write changed nothing",
            )

            // The lock is free: a later plan on the same instance still runs.
            tasks.setTagsOverride = null
            dispatch.dispatch(ProposalPlan.AddTags(taskId, setOf(tagB)), user)
            assertTrue(
                tasks.tasks.value["t1"]
                    ?.tags
                    ?.contains(tagB) == true,
                "a plan after a failure is not blocked by it",
            )
        }
}
