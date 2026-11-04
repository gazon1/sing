package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.logic.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.model.resolvedSections
import com.singularity.todo.feature.agenda.domain.model.withUniqueIds
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A saved view's sections must carry an id unique within the view — the agenda
 * list keys its headers by that id, so two equal ids take the whole screen down
 * with `Key "header-…" was already used` (#2196).
 *
 * Both ways a collision can arise are covered here: a section without an id
 * (legacy saved views) derives one from its name, and an older editor could mint
 * `"section_$count"` twice after a removal. [resolvedSections] is the one owner of
 * "make them distinct" — the evaluator renders under its ids, and the `+` button
 * resolves a rendered id back through it.
 */
class AgendaSectionIdentityTest {
    @Test
    fun `adding a section after removing one mints an id no remaining section holds`() {
        // The bug: a view of three sections loses section_1, so its count is 2 and
        // the old `"section_$count"` minted section_2 — the id of the section that
        // is still there.
        val remaining = listOf(section("section_0"), section("section_2"))

        val fresh = Section.freshId(remaining)

        assertEquals("section_3", fresh)
        assertTrue(
            remaining.none { it.effectiveId == fresh },
            "'$fresh' must not be the id of a section that is still in the view",
        )
    }

    @Test
    fun `a fresh id also avoids a legacy section whose name derives the same id`() {
        // A legacy section named "Section 2" derives "section_2"; a fresh id must
        // not collide with it just because the count is 2.
        val remaining =
            listOf(
                section("section_0"),
                Section(name = "Section 2", order = 1, selector = Selector.Blocked),
            )

        val fresh = Section.freshId(remaining)

        assertEquals("section_3", fresh)
    }

    @Test
    fun `two legacy sections with the same name get distinct ids`() {
        val legacy =
            listOf(
                Section(name = "Project", order = 0, selector = Selector.Blocked),
                Section(name = "Project", order = 1, selector = Selector.Blocked),
            )

        val ids = legacy.withUniqueIds().map { it.effectiveId }

        assertEquals(listOf("project", "project_2"), ids)
    }

    @Test
    fun `two sections that already carry the same explicit id get distinct ids`() {
        val duplicated = listOf(section("section_2"), section("section_2"))

        val ids = duplicated.withUniqueIds().map { it.id }

        assertEquals(listOf("section_2", "section_2_2"), ids)
    }

    @Test
    fun `resolved ids are unique and follow render order`() {
        val definition =
            AgendaDefinition(
                title = "View",
                sections =
                    listOf(
                        Section(name = "Project", order = 1, selector = Selector.Blocked),
                        Section(name = "Project", order = 0, selector = Selector.Blocked),
                    ),
            )

        val ids = definition.resolvedSections().map { it.id }

        assertEquals(listOf("project", "project_2"), ids)
        assertEquals(ids.size, ids.toSet().size, "rendered ids must be distinct")
    }

    /**
     * The defect's real path: the evaluator is what produces the ids the list keys
     * on, so the assertion belongs after `evaluate`, not only on the resolver.
     */
    @Test
    fun `the evaluator renders two same-named legacy sections under distinct ids`() {
        val definition =
            AgendaDefinition(
                title = "View",
                sections =
                    listOf(
                        Section(
                            name = "Project",
                            order = 0,
                            selector = Selector.DateBucket(RelativeBucket.Today),
                        ),
                        Section(
                            name = "Project",
                            order = 1,
                            selector = Selector.DateBucket(RelativeBucket.Today),
                        ),
                    ),
            )

        val rendered =
            AgendaEvaluator.evaluate(
                tasks = listOf(taskDueToday("t1")),
                definition = definition,
                today = TODAY,
                now = Instant.fromEpochMilliseconds(0),
            )

        assertEquals(listOf("project", "project_2"), rendered.map { it.id })
    }

    @Test
    fun `the plus button of a second same-named section resolves back to that section`() {
        val first = Section(name = "Project", order = 0, selector = Selector.Blocked)
        val second = Section(name = "Project", order = 1, selector = Selector.Blocked)
        val definition = AgendaDefinition(title = "View", sections = listOf(first, second))

        val resolved = definition.resolvedSections()

        assertEquals(listOf("project", "project_2"), resolved.map { it.id })
        assertEquals(first, resolved[0].section)
        assertEquals(second, resolved[1].section)
    }

    @Test
    fun `two same-named sections are separately addressable by their tags`() {
        val definition =
            AgendaDefinition(
                title = "View",
                sections =
                    listOf(
                        Section(name = "Project", order = 0, selector = Selector.Blocked),
                        Section(name = "Project", order = 1, selector = Selector.Blocked),
                    ),
            )

        val tags = definition.resolvedSections().map { TestTags.agendaSection(it.id) }

        assertNotEquals(tags[0], tags[1])
        assertEquals(2, tags.toSet().size)
    }

    private fun section(id: String) = Section(id = id, name = "Section", selector = Selector.Blocked)

    private fun taskDueToday(id: String): Task =
        Task(
            id = TaskId(id),
            title = "Task $id",
            description = null,
            priority = TaskPriority.None,
            kind = TaskKind.Task,
            projectId = null,
            parentTaskId = null,
            tags = emptyList(),
            dueDate = TODAY,
            dueTime = null,
            startDate = null,
            startTime = null,
            endDate = null,
            endTime = null,
            accentColor = null,
            emoji = null,
            estimateMinutes = null,
            completedAt = null,
            someday = false,
            archivedAt = null,
            isPinned = false,
            blockedBy = emptySet(),
            aiSuppressedTagIds = emptySet(),
            recurrence = null,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
            userId = UserId("test-user"),
            serverVersion = 0,
            hlc = null,
        )

    private companion object {
        val TODAY: LocalDate = LocalDate(2026, 10, 15)
    }
}
