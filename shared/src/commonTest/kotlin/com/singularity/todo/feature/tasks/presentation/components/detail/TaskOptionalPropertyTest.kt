package com.singularity.todo.feature.tasks.presentation.components.detail

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one rule that decides which of the task's optional properties are on the
 * screen.
 *
 * A section with nothing in it is not drawn, and the `+ Ещё` sheet offers exactly
 * the ones that are not drawn. The checklist and the subtasks are never among them:
 * their header is the way to write into them, so a hidden header would take the
 * way in with it.
 */
@Tag("fast")
class TaskOptionalPropertyTest {
    @Test
    fun aPropertyWithContentIsNotOfferedAgain() {
        val hidden =
            hiddenOptionalProperties(
                hasContent = setOf(TaskOptionalProperty.Attachments),
                revealed = emptySet(),
            )

        assertTrue(TaskOptionalProperty.Attachments !in hidden)
    }

    @Test
    fun anEmptyHideablePropertyIsOffered() {
        val hidden =
            hiddenOptionalProperties(
                hasContent = emptySet(),
                revealed = emptySet(),
            )

        assertTrue(TaskOptionalProperty.Attachments in hidden)
        assertTrue(TaskOptionalProperty.Logbook in hidden)
        assertTrue(TaskOptionalProperty.RelatedTasks in hidden)
        assertTrue(TaskOptionalProperty.TimeTracking in hidden)
        assertTrue(TaskOptionalProperty.WorkflowState in hidden)
    }

    @Test
    fun theChecklistAndTheSubtasksAreNeverHidden() {
        val hidden =
            hiddenOptionalProperties(
                hasContent = emptySet(),
                revealed = emptySet(),
            )

        assertTrue(TaskOptionalProperty.Checklist !in hidden)
        assertTrue(TaskOptionalProperty.Subtasks !in hidden)
    }

    @Test
    fun aRevealedPropertyStaysOnTheScreenWhileItIsStillEmpty() {
        val hidden =
            hiddenOptionalProperties(
                hasContent = emptySet(),
                revealed = setOf(TaskOptionalProperty.Logbook),
            )

        assertTrue(TaskOptionalProperty.Logbook !in hidden)
    }

    @Test
    fun theRevealedPropertiesSurviveAConfigurationChange() {
        val revealed = setOf(TaskOptionalProperty.Logbook, TaskOptionalProperty.Attachments)

        val saved = revealed.savedNames()
        val restored = TaskOptionalPropertySetSaver.restore(saved)

        assertEquals(listOf("Logbook", "Attachments"), saved)
        assertEquals(revealed, restored)
    }

    @Test
    fun aPropertyThisBuildDoesNotKnowIsDropped() {
        val restored = TaskOptionalPropertySetSaver.restore(listOf("Logbook", "SomethingNew"))

        assertEquals(setOf(TaskOptionalProperty.Logbook), restored)
    }
}
