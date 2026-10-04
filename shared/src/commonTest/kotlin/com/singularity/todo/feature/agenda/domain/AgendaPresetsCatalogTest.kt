package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * Catalog test for all [AgendaPresets] and factory functions.
 *
 * Tests:
 * - All presets produce valid AgendaDefinitions
 * - Inbox has the correct 8-section structure (narrowest-first, discard on all but last two)
 * - Today has 2 sections
 * - Upcoming has 5 sections
 * - byProject / byTag / byTags / byDateRange produce single-section definitions
 * - No duplicate section IDs within any preset
 * - StableJson round-trip preserves preset structure
 * - Each preset sections are sorted by order
 */
@Tag("fast")
class AgendaPresetsCatalogTest {

    private val epoch0 = kotlin.time.Instant.fromEpochMilliseconds(0)
    private val today = LocalDate(2026, 10, 14) // reference "today" = Wednesday

    // ─── Structure ────────────────────────────────────────────────────────

    @Test
    fun `Inbox preset has 8 sections`() {
        val def = AgendaPresets.Inbox
        assertEquals(8, def.sections.size)
    }

    @Test
    fun `Inbox preset sections are ordered narrowest-first`() {
        val def = AgendaPresets.Inbox
        val orders = def.sections.map { it.order }
        assertEquals(orders.sorted(), orders, "sections must be sorted by order")
    }

    @Test
    fun `Inbox first section is Overdue`() {
        val def = AgendaPresets.Inbox
        val overdue = def.sections.firstOrNull()
        assertNotNull(overdue)
        assertTrue(overdue.name == "Overdue" || overdue.selector is Selector.DateBucket)
    }

    @Test
    fun `Inbox last two sections have discard false`() {
        val def = AgendaPresets.Inbox
        val lastTwo = def.sections.takeLast(2)
        assertTrue(lastTwo.none { it.discard }, "Last two sections (ThisMonth/NoDate) must have discard=false")
    }

    @Test
    fun `Inbox sections 0-5 have discard true`() {
        val def = AgendaPresets.Inbox
        def.sections.take(6).forEach { section ->
            assertTrue(
                section.discard,
                "Section '${section.name}' (index ${def.sections.indexOf(section)}) should have discard=true",
            )
        }
    }

    @Test
    fun `Today preset has 2 sections`() {
        val def = AgendaPresets.Today
        assertEquals(2, def.sections.size)
    }

    @Test
    fun `Upcoming preset has 5 sections`() {
        val def = AgendaPresets.Upcoming
        assertEquals(5, def.sections.size)
    }

    // ─── No duplicate IDs ────────────────────────────────────────────────

    @Test
    fun `Inbox has no duplicate section IDs`() {
        val def = AgendaPresets.Inbox
        val ids = def.sections.map { it.effectiveId }
        assertEquals(ids.toSet().size, ids.size, "Inbox preset must not have duplicate section IDs")
    }

    @Test
    fun `Today has no duplicate section IDs`() {
        val def = AgendaPresets.Today
        val ids = def.sections.map { it.effectiveId }
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun `Upcoming has no duplicate section IDs`() {
        val def = AgendaPresets.Upcoming
        val ids = def.sections.map { it.effectiveId }
        assertEquals(ids.toSet().size, ids.size)
    }

    // ─── Factory functions ──────────────────────────────────────────────

    @Test
    fun `byProject creates single-section definition`() {
        val projId = ProjectId("my-project")
        val def = AgendaPresets.byProject(projId)
        assertEquals(1, def.sections.size)
        val section = def.sections.first()
        assertTrue(section.selector is Selector.Projects)
    }

    @Test
    fun `byTag creates single-section definition`() {
        val tagId = TagId("my-tag")
        val def = AgendaPresets.byTag(tagId)
        assertEquals(1, def.sections.size)
        assertTrue(def.sections.first().selector is Selector.Tags)
    }

    @Test
    fun `byTags creates single-section definition`() {
        val tags = setOf(TagId("t1"), TagId("t2"))
        val def = AgendaPresets.byTags(tags)
        assertEquals(1, def.sections.size)
        assertTrue(def.sections.first().selector is Selector.Tags)
    }

    @Test
    fun `byDateRange creates single-section definition`() {
        val from = today
        val to = today.plus(7, DateTimeUnit.DAY)
        val def = AgendaPresets.byDateRange(from, to)
        assertEquals(1, def.sections.size)
        val section = def.sections.first()
        assertTrue(section.selector is Selector.DateRange)
    }

    @Test
    fun `byDateRange name reflects date range`() {
        val from = today
        val to = today.plus(7, DateTimeUnit.DAY)
        val def = AgendaPresets.byDateRange(from, to)
        assertTrue(def.title.contains("Date Range") || def.title.contains("Range"))
    }

    // ─── StableJson round-trip ───────────────────────────────────────────

    @Test
    fun `Inbox round-trips through StableJson`() {
        val def = AgendaPresets.Inbox
        val json = StableJson.encodeToString(AgendaDefinition.serializer(), def)
        val restored = StableJson.decodeFromString(AgendaDefinition.serializer(), json)
        assertEquals(def.title, restored.title)
        assertEquals(def.sections.size, restored.sections.size)
        assertEquals(def.sections.map { it.effectiveId }, restored.sections.map { it.effectiveId })
    }

    @Test
    fun `Today round-trips through StableJson`() {
        val def = AgendaPresets.Today
        val json = StableJson.encodeToString(AgendaDefinition.serializer(), def)
        val restored = StableJson.decodeFromString(AgendaDefinition.serializer(), json)
        assertEquals(def.title, restored.title)
        assertEquals(def.sections.size, restored.sections.size)
    }

    @Test
    fun `byProject round-trips through StableJson`() {
        val projId = ProjectId("my-project")
        val def = AgendaPresets.byProject(projId)
        val json = StableJson.encodeToString(AgendaDefinition.serializer(), def)
        val restored = StableJson.decodeFromString(AgendaDefinition.serializer(), json)
        assertEquals(def.title, restored.title)
    }

    // ─── Discard invariants ───────────────────────────────────────────────

    @Test
    fun `discard=false sections do not consume tasks`() {
        // Without discard, the same task can appear in both NoDate sections.
        val def = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section(
                    id = "nodate1",
                    name = "No Date 1",
                    order = 0,
                    selector = Selector.DateBucket(RelativeBucket.NoDate),
                    discard = false,
                ),
                Section(
                    id = "nodate2",
                    name = "No Date 2",
                    order = 1,
                    selector = Selector.DateBucket(RelativeBucket.NoDate),
                    discard = false,
                ),
            ),
        )
        val undated = Task(
            id = TaskId("u1"),
            title = "Undated",
            createdAt = epoch0,
            updatedAt = epoch0,
            userId = UserId("test"),
            kind = TaskKind.Task,
            priority = TaskPriority.None,
            dueDate = null,
        )
        val result = AgendaEvaluator.evaluate(listOf(undated), def, today)
        val section1Count = result.find { it.name == "No Date 1" }?.tasks?.size ?: 0
        val section2Count = result.find { it.name == "No Date 2" }?.tasks?.size ?: 0
        assertTrue(section1Count >= 1, "task should be in first NoDate section")
        assertTrue(section2Count >= 1, "task should also be in second NoDate section (no discard)")
    }

    @Test
    fun `adjacent overlapping buckets with discard consume tasks once`() {
        val task = Task(
            id = TaskId("t1"),
            title = "Today Task",
            createdAt = epoch0,
            updatedAt = epoch0,
            userId = UserId("test"),
            kind = TaskKind.Task,
            priority = TaskPriority.None,
            dueDate = today, // today → matches both Today and ThisWeek
        )
        val def = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section(
                    id = "today",
                    name = "Today",
                    order = 0,
                    selector = Selector.DateBucket(RelativeBucket.Today),
                    discard = true,
                ),
                Section(
                    id = "thisweek",
                    name = "This Week",
                    order = 1,
                    selector = Selector.DateBucket(RelativeBucket.ThisWeek),
                    discard = true,
                ),
            ),
        )
        val result = AgendaEvaluator.evaluate(listOf(task), def, today)
        val todayCount = result.find { it.name == "Today" }?.tasks?.size ?: 0
        val weekCount = result.find { it.name == "This Week" }?.tasks?.size ?: 0
        assertEquals(1, todayCount, "task should be in Today section")
        assertEquals(0, weekCount, "task should NOT leak to ThisWeek after discard")
    }
}
