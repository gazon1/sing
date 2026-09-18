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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Tests for [SelectorSerializer] — the JSON migration shim that accepts both
 * MR1's `"Tag"` discriminator and MR2's `"Tags"` discriminator.
 *
 * Without [SelectorSerializer] wired on the [Selector] interface, kotlinx.serialization
 * would generate a standard polymorphic deserializer that only understands the MR2 format.
 */
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

    // ─── Round-trip tests — all 14 variants ───────────────────────────────────

    @Test
    fun `DateBucket round-trips`() {
        val selector = Selector.DateBucket(RelativeBucket.ThisWeek)
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.DateBucket>(decoded)
        assertEquals(RelativeBucket.ThisWeek, decoded.bucket)
    }

    @Test
    fun `DateRange round-trips`() {
        val selector = Selector.DateRange(LocalDate(2025, 3, 1), LocalDate(2025, 3, 31))
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.DateRange>(decoded)
        assertEquals(LocalDate(2025, 3, 1), decoded.from)
        assertEquals(LocalDate(2025, 3, 31), decoded.to)
    }

    @Test
    fun `Tags round-trips via SelectorSerializer`() {
        val selector = Selector.Tags(setOf(TagId("t1"), TagId("t2")), matchAll = true)
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.Tags>(decoded)
        assertEquals(setOf(TagId("t1"), TagId("t2")), decoded.ids)
        assertEquals(true, decoded.matchAll)
    }

    @Test
    fun `Tags with matchAll false round-trips`() {
        val selector = Selector.Tags(setOf(TagId("tag-x")), matchAll = false)
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.Tags>(decoded)
        assertEquals(setOf(TagId("tag-x")), decoded.ids)
        assertEquals(false, decoded.matchAll)
    }

    @Test
    fun `Statuses round-trips`() {
        val selector = Selector.Statuses(setOf(TaskStatus.Active, TaskStatus.Completed))
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.Statuses>(decoded)
        assertEquals(setOf(TaskStatus.Active, TaskStatus.Completed), decoded.statuses)
    }

    @Test
    fun `Priorities round-trips`() {
        val selector = Selector.Priorities(setOf(TaskPriority.High, TaskPriority.Low), atMost = false)
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.Priorities>(decoded)
        assertEquals(setOf(TaskPriority.High, TaskPriority.Low), decoded.priorities)
        assertEquals(false, decoded.atMost)
    }

    @Test
    fun `Projects round-trips`() {
        val selector = Selector.Projects(setOf(ProjectId("p1"), ProjectId("p2")))
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.Projects>(decoded)
        assertEquals(setOf(ProjectId("p1"), ProjectId("p2")), decoded.ids)
    }

    @Test
    fun `Pinned round-trips`() {
        val selector = Selector.Pinned
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertSame(Selector.Pinned, decoded)
    }

    @Test
    fun `Completed round-trips`() {
        val selector = Selector.Completed
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertSame(Selector.Completed, decoded)
    }

    @Test
    fun `Overdue round-trips`() {
        val selector = Selector.Overdue
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertSame(Selector.Overdue, decoded)
    }

    @Test
    fun `Regexp round-trips`() {
        val selector = Selector.Regexp("hello.*world")
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.Regexp>(decoded)
        assertEquals("hello.*world", decoded.query)
    }

    @Test
    fun `Anything round-trips`() {
        val selector = Selector.Anything
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertSame(Selector.Anything, decoded)
    }

    // ─── Composite selectors — nested round-trips ──────────────────────────────

    @Test
    fun `AllOf round-trips with nested selectors`() {
        val selector = Selector.AllOf(listOf(
            Selector.DateBucket(RelativeBucket.Today),
            Selector.Tags(setOf(TagId("tag-1"))),
        ))
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.AllOf>(decoded)
        assertEquals(2, decoded.children.size)
        assertIs<Selector.DateBucket>(decoded.children[0])
        assertIs<Selector.Tags>(decoded.children[1])
    }

    @Test
    fun `AllOf with deeply nested AllOf round-trips`() {
        val selector = Selector.AllOf(listOf(
            Selector.AllOf(listOf(
                Selector.Completed,
                Selector.Pinned,
            )),
            Selector.Not(Selector.Overdue),
        ))
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.AllOf>(decoded)
        val inner = decoded.children[0] as Selector.AllOf
        assertEquals(2, inner.children.size)
        assertSame(Selector.Completed, inner.children[0])
        assertSame(Selector.Pinned, inner.children[1])
        assertIs<Selector.Not>(decoded.children[1])
    }

    @Test
    fun `AnyOf round-trips with nested selectors`() {
        val selector = Selector.AnyOf(listOf(
            Selector.Priorities(setOf(TaskPriority.High)),
            Selector.DateBucket(RelativeBucket.Overdue),
        ))
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.AnyOf>(decoded)
        assertEquals(2, decoded.children.size)
        assertIs<Selector.Priorities>(decoded.children[0])
        assertIs<Selector.DateBucket>(decoded.children[1])
    }

    @Test
    fun `Not round-trips with nested selector`() {
        val selector = Selector.Not(Selector.AllOf(listOf(Selector.Pinned, Selector.Completed)))
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.Not>(decoded)
        assertIs<Selector.AllOf>(decoded.child)
    }

    @Test
    fun `Not with leaf selector round-trips`() {
        val selector = Selector.Not(Selector.Completed)
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)
        assertIs<Selector.Not>(decoded)
        assertSame(Selector.Completed, decoded.child)
    }
}
