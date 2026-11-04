package com.singularity.todo.feature.agenda.presentation.nav

import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.nav.AgendaStartRoute
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bottom bar's three tabs open the three disjoint presets.
 *
 * The disjointness itself is pinned in the domain — see `AgendaTabDisjointnessTest`.
 * This pins the other half: that the route named Inbox opens the narrow Inbox
 * preset and not the wide All view. Pointing one tab at the wide view is exactly
 * how the three tabs came to render the same screen, and nothing in the preset
 * definitions would have said so.
 */
@Tag("fast")
class AgendaTabRouteMappingTest {
    @Test
    fun `the Inbox tab opens the Inbox preset`() {
        assertEquals(AgendaPresets.Inbox, AgendaStartRoute.Inbox.toDefinition())
    }

    @Test
    fun `the Today tab opens the Today preset`() {
        assertEquals(AgendaPresets.Today, AgendaStartRoute.Today.toDefinition())
    }

    @Test
    fun `the Upcoming tab opens the Upcoming preset`() {
        assertEquals(AgendaPresets.Upcoming, AgendaStartRoute.Upcoming.toDefinition())
    }

    /**
     * The wide view stays reachable — it is the seed a saved view starts from —
     * but it is reached through the saved-view routes, never through a tab.
     */
    @Test
    fun `the saved-view routes open the wide All view`() {
        assertEquals(AgendaPresets.All, AgendaStartRoute.SavedAgendaList.toDefinition())
        assertEquals(AgendaPresets.All, AgendaStartRoute.SavedAgendaCreate.toDefinition())
    }
}
