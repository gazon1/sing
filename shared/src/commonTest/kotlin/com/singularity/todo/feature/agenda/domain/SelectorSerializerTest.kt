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
import kotlin.test.assertTrue

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
    fun `typeTag returns @SerialName value for all 17 variants`() {
        assertEquals("DateBucket", Selector.DateBucket(RelativeBucket.Today).typeTag)
        assertEquals("DateRange", Selector.DateRange(LocalDate(2025, 1, 1), LocalDate(2025, 1, 31)).typeTag)
        assertEquals("Tags", Selector.Tags(setOf()).typeTag)
        assertEquals("Statuses", Selector.Statuses(setOf(TaskStatus.Active)).typeTag)
        assertEquals("Priorities", Selector.Priorities(setOf(TaskPriority.High)).typeTag)
        assertEquals("Projects", Selector.Projects(setOf(ProjectId("p1"))).typeTag)
        assertEquals("Pinned", Selector.Pinned.typeTag)
        assertEquals("Completed", Selector.Completed.typeTag)
        assertEquals("Overdue", Selector.Overdue.typeTag)
        assertEquals("Blocked", Selector.Blocked.typeTag)
        assertEquals("Stagnant", Selector.Stagnant().typeTag)
        assertEquals("Regexp", Selector.Regexp(".*").typeTag)
        assertEquals("AllOf", Selector.AllOf(emptyList()).typeTag)
        assertEquals("AnyOf", Selector.AnyOf(emptyList()).typeTag)
        assertEquals("Not", Selector.Not(Selector.Completed).typeTag)
        assertEquals("Anything", Selector.Anything.typeTag)
        assertEquals("TimeOfDay", Selector.TimeOfDay(0, 0).typeTag)
    }

    /**
     * Every variant survives encode → decode.
     *
     * A variant that is missing from the serializer's registry cannot be encoded at
     * all: `getValue` throws `NoSuchElementException: Key <name> is missing in the
     * map`. That is what `Selector.TimeOfDay` did — it was in the hierarchy and in
     * `typeTag`, but not in the registry, so saving any agenda that used a work-day
     * block threw and the failure was silent.
     *
     * The list below is deliberately the full hierarchy. A shorter list — the one
     * this test carried while `TimeOfDay` was missing — hides exactly the case it
     * exists to catch.
     */
    @Test
    fun `every variant round-trips through the serializer`() {
        val variants = listOf(
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
            Selector.Stagnant(),
            Selector.Regexp(".*"),
            Selector.AllOf(listOf(Selector.Pinned, Selector.Not(Selector.Completed))),
            Selector.AnyOf(listOf(Selector.Pinned, Selector.Overdue)),
            Selector.Not(Selector.Completed),
            Selector.Anything,
            Selector.TimeOfDay(540, 719),
        )

        for (variant in variants) {
            val encoded = json.encodeToString(SelectorSerializer, variant)
            val decoded = json.decodeFromString(SelectorSerializer, encoded)
            assertEquals(
                variant,
                decoded,
                "${variant.typeTag} did not survive the round-trip; encoded as $encoded",
            )
        }
    }

    /**
     * A work-day block encodes to the shape the registry decodes it from.
     *
     * Pinned separately from the round-trip above because the fields are the point:
     * a registry entry that encoded the wrong field names would still round-trip
     * through itself while producing JSON no other reader understands.
     */
    @Test
    fun `TimeOfDay encodes its window and decodes it back`() {
        val encoded = json.encodeToString(SelectorSerializer, Selector.TimeOfDay(540, 719))

        assertTrue(
            encoded.contains("\"minStartMinutes\":540") && encoded.contains("\"maxEndMinutes\":719"),
            "TimeOfDay must carry both bounds of its window, encoded as $encoded",
        )
        assertEquals(
            Selector.TimeOfDay(540, 719),
            json.decodeFromString(SelectorSerializer, encoded),
        )
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
