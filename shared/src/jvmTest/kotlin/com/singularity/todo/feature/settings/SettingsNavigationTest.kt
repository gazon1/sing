package com.singularity.todo.feature.settings

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings screen has to offer the same groups on a phone and on a wide
 * window.
 *
 * It does because both layouts read [SETTINGS_GROUPS] — the wide nav rail and
 * the narrow-screen menu. They used to carry their own lists: the rail grouped
 * the thirteen tabs under five headings, the narrow menu listed the same
 * thirteen tabs flat, in an order that matched neither the rail's grouping nor
 * the tab order. These assertions pin the shape of the one list both layouts
 * now read, so a tab cannot go missing from one of them.
 */
@Tag("fast")
class SettingsNavigationTest {
    @Test
    fun every_tab_belongs_to_exactly_one_group() {
        val grouped = SETTINGS_GROUPS.flatMap { it.tabs }

        assertEquals(
            SettingsTab.entries.toSet(),
            grouped.toSet(),
            "every settings tab must appear in a group",
        )
        assertEquals(
            grouped.size,
            grouped.toSet().size,
            "a settings tab must not appear in two groups",
        )
    }

    @Test
    fun the_groups_are_the_named_sections_in_order() {
        assertEquals(
            listOf("Appearance", "Productivity", "Notifications", "Data", "AI & Account"),
            SETTINGS_GROUPS.map { it.title },
        )
    }

    @Test
    fun no_group_is_empty() {
        assertTrue(
            SETTINGS_GROUPS.all { it.tabs.isNotEmpty() },
            "a heading with no section under it is noise",
        )
    }

    @Test
    fun a_tab_names_the_group_it_lives_in() {
        assertEquals("AI & Account", SettingsTab.Account.group.title)
        assertEquals("AI & Account › Account", SettingsTab.Account.groupedLabel)
    }
}
