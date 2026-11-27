@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import com.singularity.todo.test.fakes.FakeTaskRepository
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the ordering of the entity slot's two read-modify-write tag paths.
 *
 * [TaskDetailIntent.Domain.RemoveTag] and [TaskDetailIntent.Domain.RestoreTag] both read the
 * task's tags and write the whole list back. Run at once they read the set as it was before
 * either wrote, and an edit is silently lost — so the slot serialises them. These tests hold
 * the removal open to prove the undo waits for it, and they fail if the lock is removed.
 *
 * `SetTags` is deliberately *not* covered: it states the user's complete selection, so it has
 * nothing to lose to a concurrent read, and it is not locked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
@Epic("Tasks")
@Feature("Presentation")
class TaskEntitySlotTagRaceTest {
    private val tagA = TagId.fromString("tag-a")
    private val tagB = TagId.fromString("tag-b")

    /** A repository whose first tag write is held open, so a second one can start behind it. */
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
    fun `an undo that starts during a removal waits, and the tag comes back`() =
        runTest {
            val fakes = SlotFakes()
            val taskRepo = GatedTagRepository()
            taskRepo.seed(task("t1").copy(tags = listOf(tagA)))
            fakes.tagsRepo.seed(tag(tagA, "A"))
            val source = TaskSource(task("t1").copy(tags = listOf(tagA)))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core().copy(taskRepo = taskRepo),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                    onError = {},
                )
            runCurrent()

            // The removal is held open mid-write: the tag is still on the task in storage.
            slot.onIntent(TaskDetailIntent.Domain.RemoveTag(tagA))
            taskRepo.writeStarted.await()

            // The undo starts while the removal is in flight.
            slot.onIntent(TaskDetailIntent.Domain.RestoreTag(tagA))
            runCurrent()

            assertEquals(
                1,
                taskRepo.setTagsCalls,
                "the undo must wait for the removal, not read the tags it is replacing",
            )

            taskRepo.releaseWrite.complete(Unit)
            runCurrent()

            assertEquals(
                listOf(tagA),
                taskRepo.tasks.value["t1"]?.tags,
                "the undo is the last write, so the tag the user put back is there",
            )
        }

    @Test
    fun `removing a tag and undoing it leaves exactly that tag`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1").copy(tags = listOf(tagA)))
            fakes.tagsRepo.seed(tag(tagA, "A"))
            val source = TaskSource(task("t1").copy(tags = listOf(tagA)))
            val slot =
                TaskEntitySlot(
                    taskId = TaskId("t1"),
                    core = fakes.core(),
                    children = fakes.children(),
                    scope = testSlotScope(backgroundScope),
                    taskFlow = source.state,
                    onError = {},
                )
            runCurrent()

            slot.onIntent(TaskDetailIntent.Domain.RemoveTag(tagA))
            runCurrent()
            assertTrue(
                fakes.taskRepo.tasks.value["t1"]
                    ?.tags
                    ?.isEmpty() == true,
                "the tag must be off the task",
            )

            slot.onIntent(TaskDetailIntent.Domain.RestoreTag(tagA))
            runCurrent()

            assertEquals(
                listOf(tagA),
                fakes.taskRepo.tasks.value["t1"]
                    ?.tags,
                "the undo puts the tag back exactly once",
            )
        }

    // ── A replacement and a delta share one boundary ────────────────────────────
    //
    // A replacement states the whole set, which does not make it immune: a delta writes the
    // result of a read taken *before* the replacement, so it erases it. These two hold the
    // delta open to prove the replacement is ordered against it in both directions.

    @Test
    fun `a replacement that starts during a removal is not erased by it`() =
        runTest {
            val taskRepo = GatedTagRepository()
            val slot = slotFor(taskRepo, listOf(tagA), backgroundScope)
            runCurrent()

            // The removal reads {A} and is held inside its write.
            slot.onIntent(TaskDetailIntent.Domain.RemoveTag(tagA))
            taskRepo.writeStarted.await()

            // The replacement starts while the removal is in flight.
            slot.onIntent(TaskDetailIntent.Domain.SetTags(listOf(tagB)))
            runCurrent()

            assertEquals(
                1,
                taskRepo.setTagsCalls,
                "the replacement must wait for the delta, not be overwritten by its stale read",
            )

            taskRepo.releaseWrite.complete(Unit)
            runCurrent()

            assertEquals(
                listOf(tagB),
                taskRepo.tasks.value["t1"]
                    ?.tags,
                "the replacement is the later decision, and the removal's stale write cannot undo it",
            )
        }

    @Test
    fun `a removal that starts during a replacement is ordered after it`() =
        runTest {
            val taskRepo = GatedTagRepository()
            val slot = slotFor(taskRepo, listOf(tagA), backgroundScope)
            runCurrent()

            // The replacement is held open mid-write.
            slot.onIntent(TaskDetailIntent.Domain.SetTags(listOf(tagA, tagB)))
            taskRepo.writeStarted.await()

            // The removal is issued while the replacement is in flight.
            slot.onIntent(TaskDetailIntent.Domain.RemoveTag(tagA))
            runCurrent()

            assertEquals(
                1,
                taskRepo.setTagsCalls,
                "the removal must wait for the replacement to land",
            )

            taskRepo.releaseWrite.complete(Unit)
            runCurrent()

            assertEquals(
                listOf(tagB),
                taskRepo.tasks.value["t1"]
                    ?.tags,
                "the removal reads the set the replacement wrote, so B survives it",
            )
        }

    @Test
    fun `an undo that starts during a replacement is ordered after it`() =
        runTest {
            val taskRepo = GatedTagRepository()
            val slot = slotFor(taskRepo, listOf(tagA), backgroundScope)
            runCurrent()

            // The replacement drops A and adds B, and is held open mid-write.
            slot.onIntent(TaskDetailIntent.Domain.SetTags(listOf(tagB)))
            taskRepo.writeStarted.await()

            slot.onIntent(TaskDetailIntent.Domain.RestoreTag(tagA))
            runCurrent()

            assertEquals(1, taskRepo.setTagsCalls, "the undo must wait for the replacement")

            taskRepo.releaseWrite.complete(Unit)
            runCurrent()

            assertEquals(
                setOf(tagA, tagB),
                taskRepo.tasks.value["t1"]
                    ?.tags
                    ?.toSet(),
                "the undo adds the tag back onto the set the replacement wrote",
            )
        }

    /**
     * A slot over [taskRepo], whose screen shows [tags] on the task.
     *
     * The repository is seeded to agree with the screen, so the first write the test
     * triggers is the one the gate holds.
     */
    private fun slotFor(
        taskRepo: FakeTaskRepository,
        tags: List<TagId>,
        scope: CoroutineScope,
    ): TaskEntitySlot {
        val fakes = SlotFakes()
        val seeded = task("t1").copy(tags = tags)
        taskRepo.seed(seeded)
        fakes.tagsRepo.seed(tag(tagA, "A"), tag(tagB, "B"))
        return TaskEntitySlot(
            taskId = TaskId("t1"),
            core = fakes.core().copy(taskRepo = taskRepo),
            children = fakes.children(),
            scope = testSlotScope(scope),
            taskFlow = TaskSource(seeded).state,
            onError = {},
        )
    }
}
