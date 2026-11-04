package com.singularity.todo.feature.tasks.domain.state

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The title rule, tested where it lives — beside [TaskDraft] in `shared`.
 *
 * The three cases are the rule's whole surface: a title that is absent, a title that is present but
 * says nothing, and a title that can be saved. They are the same questions the create editor's
 * `validate` and the detail editor's inline message ask, so the message shown under the field and
 * the reason the save is refused cannot drift apart.
 */
@Tag("fast")
class TaskTitleValidationTest {
    @Test
    fun `an empty title is missing`() {
        assertEquals(TaskTitleValidation.REQUIRED_MESSAGE, TaskTitleValidation.error(""))
    }

    @Test
    fun `a whitespace-only title is missing`() {
        // A task called "   " would render as an empty row in every list, which is
        // indistinguishable from a task with no name at all.
        assertEquals(TaskTitleValidation.REQUIRED_MESSAGE, TaskTitleValidation.error("   "))
        assertEquals(TaskTitleValidation.REQUIRED_MESSAGE, TaskTitleValidation.error("\n\t "))
    }

    @Test
    fun `a named title is fine`() {
        assertNull(TaskTitleValidation.error("Купить молоко"))
        // Surrounding whitespace is not what decides the answer — the text itself is.
        assertNull(TaskTitleValidation.error("  Купить молоко  "))
    }
}
