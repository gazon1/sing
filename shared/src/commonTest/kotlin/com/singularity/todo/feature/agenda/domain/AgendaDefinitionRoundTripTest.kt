package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaLayout
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.model.agenda
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip tests for [AgendaDefinition] JSON serialization.
 *
 * The saved views on a user's device are JSON blobs written by an older build,
 * so the shape has to keep deserializing after the definition changes. The
 * definition used to carry a `transformers` field — a second, never-read copy
 * of the badge rules — and it is gone now; a blob that carries it must still
 * deserialize, because a field nobody reads must never cost a user their
 * views.
 */
@Tag("fast")
class AgendaDefinitionRoundTripTest {

    private val json get() = StableJson

    // ─── Legacy JSON ────────────────────────────────────────────────────────────

    /**
     * A JSON blob of the shape an older build wrote.
     *
     * `id` IS present: it became a required [Section] field when section prefill
     * landed, so blobs predating that change cannot deserialize at all (see the
     * `legacy JSON without an id field fails to deserialize` test below).
     */
    private val legacyJson = """
        {
            "title": "My Saved View",
            "sections": [
                {
                    "id": "today",
                    "name": "Today",
                    "order": 0,
                    "selector": {
                        "_type": "DateBucket",
                        "bucket": "Today"
                    },
                    "discard": false
                },
                {
                    "id": "overdue",
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
    fun `legacy JSON deserializes`() {
        val decoded = json.decodeFromString<AgendaDefinition>(legacyJson)

        assertEquals("My Saved View", decoded.title)
        assertEquals(AgendaLayout.ListFlat, decoded.layout)
        assertEquals(2, decoded.sections.size)

        assertEquals("today", decoded.sections[0].id)
        assertEquals("Today", decoded.sections[0].name)
        assertEquals(RelativeBucket.Today, (decoded.sections[0].selector as Selector.DateBucket).bucket)

        assertEquals("overdue", decoded.sections[1].id)
        assertEquals("Overdue", decoded.sections[1].name)
        assertTrue(decoded.sections[1].discard)
    }

    /**
     * `id` is nullable so that agenda JSON persisted before section prefill shipped
     * can still deserialize. The `+` button prefill uses [Section.effectiveId] which
     * derives a stable id from the section name when `id` is null.
     */
    @Test
    fun `legacy JSON without id deserializes and derives effectiveId from name`() {
        val jsonWithoutIds = legacyJson
            .replace("\"id\": \"today\",", "")
            .replace("\"id\": \"overdue\",", "")

        val decoded = json.decodeFromString<AgendaDefinition>(jsonWithoutIds)

        // id is null in memory (was not in JSON)
        assertEquals(null, decoded.sections[0].id)
        assertEquals(null, decoded.sections[1].id)
        // but effectiveId is derived from the section name
        assertEquals("today", decoded.sections[0].effectiveId)
        assertEquals("overdue", decoded.sections[1].effectiveId)
    }

    @Test
    fun `re-encoding a legacy blob adds no field that was not there`() {
        val decoded = json.decodeFromString<AgendaDefinition>(legacyJson)
        val reEncoded = json.encodeToString(AgendaDefinition.serializer(), decoded)

        assertTrue(reEncoded.contains("\"title\":\"My Saved View\""))
        // The `transformers` field is gone from the model. A blob that still
        // carries it must deserialize, and re-encoding must not bring it back.
        assertTrue(!reEncoded.contains("transformers"))
    }

    @Test
    fun `a blob that still carries the retired transformers field deserializes`() {
        val withTransformers = legacyJson.replace(
            "\"layout\": \"ListFlat\"",
            "\"layout\": \"ListFlat\", \"transformers\": []",
        )

        val decoded = json.decodeFromString<AgendaDefinition>(withTransformers)

        assertEquals("My Saved View", decoded.title)
        assertEquals(2, decoded.sections.size)
    }

    @Test
    fun `a modern definition round-trips`() {
        val modern = AgendaDefinition(
            title = "Modern View",
            sections = agenda("Modern View") {
                section(id = "today", name = "Today") {
                    selector = Selector.DateBucket(RelativeBucket.Today)
                }
            }.sections,
            layout = AgendaLayout.ListGrouped,
        )

        val encoded = json.encodeToString(AgendaDefinition.serializer(), modern)
        val decoded = json.decodeFromString<AgendaDefinition>(encoded)

        assertEquals("Modern View", decoded.title)
        assertEquals(AgendaLayout.ListGrouped, decoded.layout)
        assertTrue(!encoded.contains("transformers"))
    }
}
