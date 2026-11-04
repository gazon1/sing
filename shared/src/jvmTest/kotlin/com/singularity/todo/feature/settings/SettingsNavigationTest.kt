package com.singularity.todo.feature.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings groups are the one list both layouts read: the wide nav rail and
 * the narrow sheet iterate [SETTINGS_GROUPS] and nothing else. These tests pin
 * the two properties the layouts rely on — every tab has a group, and every
 * tab is grouped exactly once — so a new tab cannot be added to the enum and
 * silently disappear from both menus, and `SettingsTab.groupedLabel` cannot
 * throw on the narrow trigger.
 */
class SettingsNavigationTest {
    @Test
    fun `every settings tab belongs to exactly one group`() {
        val groupedTabs = SETTINGS_GROUPS.flatMap { it.tabs }

        assertEquals(
            SettingsTab.entries.toSet(),
            groupedTabs.toSet(),
            "the groups must cover every SettingsTab",
        )
        assertEquals(
            groupedTabs.size,
            groupedTabs.toSet().size,
            "a tab must not be listed in two groups",
        )
    }

    @Test
    fun `every group is non-empty and named`() {
        SETTINGS_GROUPS.forEach { group ->
            assertTrue(group.title.isNotBlank(), "a group needs a heading")
            assertTrue(group.tabs.isNotEmpty(), "'${group.title}' lists no tabs")
        }
    }

    @Test
    fun `the group order is pinned`() {
        // The rail and the narrow sheet both render SETTINGS_GROUPS in list
        // order; this pins that order so a layout cannot sort or regroup it.
        // It does not prove either layout renders the list — see the layout
        // parity test for that.
        assertEquals(
            listOf("Appearance", "Productivity", "Notifications", "Data", "AI & Account"),
            SETTINGS_GROUPS.map { it.title },
        )
    }

    @Test
    fun `the narrow trigger can label every tab`() {
        SettingsTab.entries.forEach { tab ->
            assertTrue(
                tab.groupedLabel.startsWith(tab.group.title),
                "'${tab.name}' is labelled outside its group",
            )
            assertTrue(tab.groupedLabel.endsWith(tab.label))
        }
    }
}
