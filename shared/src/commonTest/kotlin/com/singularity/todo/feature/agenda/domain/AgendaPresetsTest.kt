package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgendaPresetsTest {

    private val json: Json = StableJson

    // ─── Serialisation round-trip ─────────────────────────────────────────────

    @Test
    fun `Inbox serialises and deserialises`() {
        val encoded = json.encodeToString(AgendaPresets.Inbox)
        val decoded = json.decodeFromString<AgendaDefinition>(encoded)
        assertEquals("Inbox", decoded.title)
        assertEquals(AgendaPresets.Inbox.sections.size, decoded.sections.size)
    }

    @Test
    fun `Today serialises and deserialises`() {
        val encoded = json.encodeToString(AgendaPresets.Today)
        val decoded = json.decodeFromString<AgendaDefinition>(encoded)
        assertEquals("Today", decoded.title)
        assertEquals(AgendaPresets.Today.sections.size, decoded.sections.size)
    }

    @Test
    fun `Upcoming serialises and deserialises`() {
        val encoded = json.encodeToString(AgendaPresets.Upcoming)
        val decoded = json.decodeFromString<AgendaDefinition>(encoded)
        assertEquals("Upcoming", decoded.title)
    }

    @Test
    fun `byProject serialises and deserialises`() {
        val id = ProjectId.fromString("proj-123")
        val preset = AgendaPresets.byProject(id)
        val encoded = json.encodeToString(preset)
        val decoded = json.decodeFromString<AgendaDefinition>(encoded)
        assertEquals("proj-123", decoded.title)
    }

    @Test
    fun `byTag serialises and deserialises`() {
        val id = TagId.fromString("tag-456")
        val preset = AgendaPresets.byTag(id)
        val encoded = json.encodeToString(preset)
        val decoded = json.decodeFromString<AgendaDefinition>(encoded)
        assertEquals("Tagged", decoded.title)
    }

    @Test
    fun `byDateRange serialises and deserialises`() {
        val from = LocalDate(2025, 1, 1)
        val to = LocalDate(2025, 1, 31)
        val preset = AgendaPresets.byDateRange(from, to)
        val encoded = json.encodeToString(preset)
        val decoded = json.decodeFromString<AgendaDefinition>(encoded)
        assertEquals("Date Range", decoded.title)
    }

    // ─── Preset invariants ─────────────────────────────────────────────────────

    @Test
    fun `Inbox has at least Today and Overdue sections`() {
        val sectionNames = AgendaPresets.Inbox.sections.map { it.name }
        assertNotNull(sectionNames.find { it == "Today" })
        assertNotNull(sectionNames.find { it == "Overdue" })
    }

    @Test
    fun `Upcoming sections are ordered`() {
        val orders = AgendaPresets.Upcoming.sections.map { it.order }
        assertEquals(orders.sorted(), orders)
    }

    @Test
    fun `byProject has exactly one section`() {
        val id = ProjectId.fromString("proj-1")
        val preset = AgendaPresets.byProject(id)
        assertEquals(1, preset.sections.size)
    }

    @Test
    fun `byTag has exactly one section`() {
        val id = TagId.fromString("tag-1")
        val preset = AgendaPresets.byTag(id)
        assertEquals(1, preset.sections.size)
    }
}
