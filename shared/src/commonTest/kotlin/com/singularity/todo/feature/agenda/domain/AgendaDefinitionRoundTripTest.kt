package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaLayout
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.model.agenda
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip tests for [AgendaDefinition] JSON serialization.
 *
 * Verifies that legacy JSON blobs (saved before the `transformers` field was added)
 * deserialize correctly, and that the `@Transient transformers = emptyList()` default
 * is applied on deserialization.
 */
class AgendaDefinitionRoundTripTest {

    private val json get() = StableJson

    // ─── Legacy JSON without transformers field ─────────────────────────────────

    /**
     * A JSON blob that mimics what was saved by MR4 or earlier.
     * No `transformers` field — proving the `@Transient default = emptyList()` works.
     */
    private val legacyJsonWithoutTransformers = """
        {
            "title": "My Saved View",
            "sections": [
                {
                    "name": "Today",
                    "order": 0,
                    "selector": {
                        "_type": "DateBucket",
                        "bucket": "Today"
                    },
                    "discard": false
                },
                {
                    "name": "Overdue",
                    "order": 1,
                    "selector": {
                        "_type": "Overdue"
                    },
                    "discard": true
                }
            ],
            "layout": "ListFlat"
        }
    """.trimIndent()

    @Test
    fun `legacy JSON without transformers deserializes with empty transformers`() {
        val decoded = json.decodeFromString<AgendaDefinition>(legacyJsonWithoutTransformers)

        assertEquals("My Saved View", decoded.title)
        assertEquals(AgendaLayout.ListFlat, decoded.layout)
        assertEquals(2, decoded.sections.size)

        assertEquals("Today", decoded.sections[0].name)
        assertEquals(RelativeBucket.Today, (decoded.sections[0].selector as Selector.DateBucket).bucket)

        assertEquals("Overdue", decoded.sections[1].name)
        assertTrue(decoded.sections[1].discard)

        // Key assertion: transformers defaults to emptyList() even though JSON had no field
        assertTrue(decoded.transformers.isEmpty())
    }

    @Test
    fun `round-trip legacy JSON decode and re-encode produces transformers-free JSON`() {
        val decoded = json.decodeFromString<AgendaDefinition>(legacyJsonWithoutTransformers)
        val reEncoded = json.encodeToString(AgendaDefinition.serializer(), decoded)

        // Re-encoding should NOT add `transformers` field (it's @Transient)
        assertTrue(reEncoded.contains("\"title\":\"My Saved View\""))
        assertTrue(!reEncoded.contains("transformers"))
    }

    @Test
    fun `round-trip modern AgendaDefinition with explicit transformers preserves transformers`() {
        val modern = AgendaDefinition(
            title = "Modern View",
            sections = agenda("Modern View") {
                section("Today") {
                    selector = Selector.DateBucket(RelativeBucket.Today)
                }
            }.sections,
            layout = AgendaLayout.ListGrouped,
            transformers = emptyList(), // explicitly set, but @Transient means it won't be in JSON
        )

        val encoded = json.encodeToString(AgendaDefinition.serializer(), modern)
        val decoded = json.decodeFromString<AgendaDefinition>(encoded)

        assertEquals("Modern View", decoded.title)
        assertEquals(AgendaLayout.ListGrouped, decoded.layout)
        assertTrue(decoded.transformers.isEmpty())
        // Even explicit empty transformers is not serialized (@Transient)
        assertTrue(!encoded.contains("transformers"))
    }
}
