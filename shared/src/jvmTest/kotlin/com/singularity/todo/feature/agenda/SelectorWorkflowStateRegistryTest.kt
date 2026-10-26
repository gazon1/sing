package com.singularity.todo.feature.agenda

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.model.SelectorSerializer
import com.singularity.todo.feature.agenda.domain.model.typeTag
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two selectors this change adds are *stored*: a saved view persists its
 * sections as JSON, and a variant missing from the serializer's registry throws
 * on save.
 *
 * The size assertion is the guard that keeps this file honest: a variant added
 * to the hierarchy and forgotten here would still pass the round-trip loop,
 * because the loop is written by hand. The expected count is spelled out, so
 * adding a selector without touching this file fails.
 */
@Tag("fast")
class SelectorWorkflowStateRegistryTest {

    private val expectedTypeTags = setOf(
        "DateBucket",
        "DateRange",
        "Tags",
        "Statuses",
        "Priorities",
        "Projects",
        "Pinned",
        "Completed",
        "Overdue",
        "Blocked",
        "WorkflowState",
        "FollowUp",
        "Stagnant",
        "Regexp",
        "AllOf",
        "AnyOf",
        "Not",
        "Anything",
        "TimeOfDay",
    )

    /** One instance per variant, the full hierarchy. */
    private val everyVariant = listOf(
        Selector.DateBucket(RelativeBucket.Overdue),
        Selector.DateRange(LocalDate(2025, 1, 1), LocalDate(2025, 1, 31)),
        Selector.Tags(setOf(TagId("t1"))),
        Selector.Statuses(setOf(TaskStatus.Active)),
        Selector.Priorities(setOf(TaskPriority.High)),
        Selector.Projects(setOf(ProjectId("p1"))),
        Selector.Pinned,
        Selector.Completed,
        Selector.Overdue,
        Selector.Blocked,
        Selector.WorkflowState(TaskWorkflowState.InProgress),
        Selector.FollowUp,
        Selector.Stagnant(),
        Selector.Regexp(".*"),
        Selector.AllOf(listOf(Selector.Pinned, Selector.Not(Selector.Completed))),
        Selector.AnyOf(listOf(Selector.Pinned, Selector.Overdue)),
        Selector.Not(Selector.Completed),
        Selector.Anything,
        Selector.TimeOfDay(540, 719),
    )

    @Test
    fun `the registry covers every variant, counted against a literal`() {
        assertEquals(expectedTypeTags.size, everyVariant.size)
        assertEquals(
            expectedTypeTags,
            everyVariant.map { it.typeTag }.toSet(),
            "a variant was added without being listed here, or listed twice",
        )
    }

    @Test
    fun `every variant survives encode and decode`() {
        for (variant in everyVariant) {
            val encoded = StableJson.encodeToString(SelectorSerializer, variant)
            assertEquals(
                variant,
                StableJson.decodeFromString(SelectorSerializer, encoded),
                "${variant.typeTag} did not survive the round-trip; encoded as $encoded",
            )
        }
    }

    @Test
    fun `a workflow-state selector carries its state through JSON`() {
        val encoded = StableJson.encodeToString(
            SelectorSerializer,
            Selector.WorkflowState(TaskWorkflowState.NextUp),
        )

        assertEquals(
            Selector.WorkflowState(TaskWorkflowState.NextUp),
            StableJson.decodeFromString(SelectorSerializer, encoded),
        )
    }
}
