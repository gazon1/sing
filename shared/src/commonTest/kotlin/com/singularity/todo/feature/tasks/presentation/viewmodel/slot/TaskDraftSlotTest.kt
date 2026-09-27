package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Covers the draft slot: seeding, immediate echo of a keystroke, and debounced persistence.
 *
 * The debounce cases replace the two `delay()` calls the previous god-VM test needed, and
 * assert on the repository rather than on a captured flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDraftSlotTest {

    @Test
    fun `draft is empty before seeding`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1"))
        val slot = TaskDraftSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}

        assertEquals("", slot.state.value.title)
    }

    @Test
    fun `seed initialises title and description`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1", title = "Original"))
        val slot = TaskDraftSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}

        slot.seed("Original", "Body")

        assertEquals("Original", slot.state.value.title)
        assertEquals("Body", slot.state.value.description)
    }

    @Test
    fun `seed is idempotent so a second call cannot clobber an in-progress edit`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1", title = "Original"))
        val slot = TaskDraftSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}

        slot.seed("Original", "Body")
        slot.onIntent(TaskDetailIntent.Domain.TitleChanged("User edit"))
        // A remote task update arriving mid-edit must not reset the text field.
        slot.seed("Changed remotely", "Changed remotely")

        assertEquals("User edit", slot.state.value.title)
    }

    @Test
    fun `title change updates the draft immediately`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1"))
        val slot = TaskDraftSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}
        slot.seed("Original", "")
        delay(SETTLE)

        slot.onIntent(TaskDetailIntent.Domain.TitleChanged("Edited"))

        assertEquals("Edited", slot.state.value.title)
    }

    @Test
    fun `title change persists after the debounce window`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1", title = "Original"))
        val slot = TaskDraftSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}
        slot.seed("Original", "")
        delay(SETTLE)

        slot.onIntent(TaskDetailIntent.Domain.TitleChanged("Edited"))
        // Real time, past the 300ms debounce configured in TaskDetailDeps.
        delay(DEBOUNCE_PLUS_SETTLE)

        assertEquals("Edited", fakes.taskRepo.tasks.value["t1"]?.title)
    }

    @Test
    fun `description change persists and blank is stored as null`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1").copy(description = "Old"))
        val slot = TaskDraftSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}
        slot.seed("Original", "Old")
        delay(SETTLE)

        slot.onIntent(TaskDetailIntent.Domain.DescriptionChanged("  "))
        delay(DEBOUNCE_PLUS_SETTLE)

        assertEquals(null, fakes.taskRepo.tasks.value["t1"]?.description)
    }

    @Test
    fun `debounced title write does not feed back into further writes`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1", title = "Original"))
        val slot = TaskDraftSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}
        slot.seed("Original", "")
        delay(SETTLE)

        slot.onIntent(TaskDetailIntent.Domain.TitleChanged("Edited title"))
        delay(DEBOUNCE_PLUS_SETTLE)
        val afterFirstWrite = fakes.taskRepo.tasks.value["t1"]?.updatedAt
        assertNotNull(afterFirstWrite, "the debounced write should have landed")

        // A write stamps a new updatedAt, which re-emits the repository observation. If
        // that fed back into the collector, the debounce loop would rewrite forever.
        delay(5_000)
        val afterQuietPeriod = fakes.taskRepo.tasks.value["t1"]?.updatedAt
        assertEquals(afterFirstWrite, afterQuietPeriod, "the debounce must not loop on its own write")
        assertEquals("Edited title", fakes.taskRepo.tasks.value["t1"]?.title)
    }

    @Test
    fun `the persisted title keeps fields the user did not touch`() = runTest {
        val fakes = SlotFakes()
        val source = TaskSource(task("t1", title = "Original").copy(emoji = "🎯"))
        val slot = TaskDraftSlot(fakes.deps(), testSlotScope(backgroundScope), source.state) {}
        slot.seed("Original", "")
        delay(SETTLE)

        slot.onIntent(TaskDetailIntent.Domain.TitleChanged("Edited"))
        delay(DEBOUNCE_PLUS_SETTLE)

        val saved = fakes.taskRepo.tasks.value["t1"]
        assertNotNull(saved)
        assertEquals("🎯", saved.emoji, "an unrelated field must survive a debounced title write")
    }
}

private const val DEBOUNCE_PLUS_SETTLE = 400L
