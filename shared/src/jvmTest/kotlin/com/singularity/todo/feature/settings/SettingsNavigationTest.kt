package com.singularity.todo.feature.settings

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The settings groups are the one list both layouts read: the wide nav rail and
 * the narrow sheet iterate [SETTINGS_GROUPS] and nothing else. These tests pin
 * the two properties the layouts rely on — every tab has a group, and every
 * tab is grouped exactly once — so a new tab cannot be added to the enum and
 * silently disappear from both menus, and `SettingsTab.groupedLabel` cannot
 * throw on the narrow trigger.
 */
@Tag("fast")
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
    fun `the narrow trigger names the group and the tab`() {
        // The narrow screen's trigger renders `selectedTab.groupedLabel` and
        // nothing else, so this is the text a person on a phone reads. It is
        // asserted against the issue's own example rather than against a list
        // of headings: a mapping that dropped the group would read "Account",
        // and one that reordered the heading would read "Account › AI & Account".
        assertEquals("AI & Account › Account", SettingsTab.Account.groupedLabel)

        // And for every tab, so a tab added later cannot quietly fall back to
        // a bare label on the trigger.
        SettingsTab.entries.forEach { tab ->
            assertEquals(
                "${tab.group.title} › ${tab.label}",
                tab.groupedLabel,
                "'${tab.name}' is labelled outside its group",
            )
            assertNotEquals(
                tab.label,
                tab.groupedLabel,
                "'${tab.name}' does not name its group on the narrow trigger",
            )
        }
    }
}
