package com.singularity.todo.feature.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The menu is a list of rows, and both layouts render that one list.
 *
 * These tests stand behind the two claims the settings change is about:
 *
 * 1. **The phone offers what the wide window offers.** The rail and the narrow
 *    sheet both iterate [SETTINGS_MENU_ROWS], so "the same five headings, with
 *    the same tabs under each, in the same order" is a property of one list
 *    rather than of two loops that happen to read the same source. These tests
 *    pin the list, and [settingsMenuRows] being the only producer of it is what
 *    makes that pin reach both layouts.
 * 2. **Every tab is reachable from the narrow screen.** The sheet is a single
 *    scroller over this list, and `verticalScroll` composes every row, so a tab
 *    that is in the list is a row that can be scrolled to. A tab missing here is
 *    missing from both layouts, and the first test fails on it.
 *
 * What no test here says is that a *rendered* column is tall enough to be
 * scrolled: that needs a device, and `Maestro/helpers/settings-tab.yaml` is the
 * helper every flow uses to open a tab by name.
 */
class SettingsMenuTest {
    @Test
    fun `every tab is a row of the menu, exactly once and in the declared order`() {
        val tabRows = SETTINGS_MENU_ROWS.filterIsInstance<SettingsMenuRow.Tab>().map { it.tab }

        // Equality with `entries` is the whole reachability claim: no tab is
        // missing (unreachable on a phone), none appears twice (two rows that
        // answer to one tab), and the order is the one the enum declares.
        assertEquals(
            SettingsTab.entries,
            tabRows,
            "the menu must offer every settings tab once, in tab order",
        )
    }

    @Test
    fun `each heading is followed by the tabs that live under it`() {
        var heading: SettingsMenuRow.Group? = null
        SETTINGS_MENU_ROWS.forEach { row ->
            when (row) {
                is SettingsMenuRow.Group -> {
                    assertTrue(
                        row.group.tabs.isNotEmpty(),
                        "'${row.group.title}' is rendered as a heading with nothing under it",
                    )
                    heading = row
                }

                is SettingsMenuRow.Tab -> {
                    val owner = heading ?: error("'${row.tab.name}' is rendered under no heading")
                    assertEquals(
                        owner.group,
                        row.tab.group,
                        "'${row.tab.name}' is listed under '${owner.group.title}' but belongs elsewhere",
                    )
                }
            }
        }
    }

    @Test
    fun `the rows are derived from the groups they are given`() {
        // The synthetic list proves the flattening reads its argument instead
        // of a second copy of the sections: a menu that carried its own list
        // would return the real rows here and this assertion would fail.
        val only = SettingsGroup("Only section", listOf(SettingsTab.Backup))
        assertEquals(
            listOf<SettingsMenuRow>(
                SettingsMenuRow.Group(only),
                SettingsMenuRow.Tab(SettingsTab.Backup),
            ),
            settingsMenuRows(listOf(only)),
        )

        // And the real list is that same flattening, counted: one heading per
        // group and one row per tab, nothing else.
        assertEquals(SETTINGS_GROUPS.size, SETTINGS_MENU_ROWS.count { it is SettingsMenuRow.Group })
        assertEquals(
            SETTINGS_GROUPS.size + SettingsTab.entries.size,
            SETTINGS_MENU_ROWS.size,
            "the menu holds headings and tab rows and nothing else",
        )
    }
}
