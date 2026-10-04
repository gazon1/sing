package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.model.SelectorSerializer
import com.singularity.todo.feature.agenda.domain.model.typeTag
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for [SelectorSerializer] — the JSON migration shim that accepts both
 * MR1's `"Tag"` discriminator and MR2's `"Tags"` discriminator.
 *
 * Without [SelectorSerializer] wired on the [Selector] interface, kotlinx.serialization
 * would generate a standard polymorphic deserializer that only understands the MR2 format.
 */
@Tag("fast")
class SelectorSerializerTest {

    // Json.decodeFromString is a member function — use as json.decodeFromString<T>(string)
    private val json get() = StableJson

    // ─── typeTag ────────────────────────────────────────────────────────────────

    @Test
    fun `typeTag returns @SerialName value for all 14 variants`() {
        assertEquals("DateBucket", Selector.DateBucket(RelativeBucket.Today).typeTag)
        assertEquals("DateRange", Selector.DateRange(LocalDate(2025, 1, 1), LocalDate(2025, 1, 31)).typeTag)
        assertEquals("Tags", Selector.Tags(setOf()).typeTag)
        assertEquals("Statuses", Selector.Statuses(setOf(TaskStatus.Active)).typeTag)
        assertEquals("Priorities", Selector.Priorities(setOf(TaskPriority.High)).typeTag)
        assertEquals("Projects", Selector.Projects(setOf(ProjectId("p1"))).typeTag)
        assertEquals("Pinned", Selector.Pinned.typeTag)
        assertEquals("Completed", Selector.Completed.typeTag)
        assertEquals("Overdue", Selector.Overdue.typeTag)
        assertEquals("Regexp", Selector.Regexp(".*").typeTag)
        assertEquals("AllOf", Selector.AllOf(emptyList()).typeTag)
        assertEquals("AnyOf", Selector.AnyOf(emptyList()).typeTag)
        assertEquals("Not", Selector.Not(Selector.Completed).typeTag)
        assertEquals("Anything", Selector.Anything.typeTag)
    }

    // ─── MR1 legacy — Selector.Tag → Selector.Tags upgrade ─────────────────────

    @Test
    fun `MR1 Tag JSON deserializes as SelectorTags`() {
        val mr1Json = """{"_type":"Tag","id":"tag-123"}"""

        val selector = json.decodeFromString(SelectorSerializer, mr1Json)

        assertIs<Selector.Tags>(selector)
        assertEquals(setOf(TagId("tag-123")), selector.ids)
    }
}
