@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import com.singularity.todo.test.fakes.FakeTaskRepository
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.CompletableDeferred
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
}
