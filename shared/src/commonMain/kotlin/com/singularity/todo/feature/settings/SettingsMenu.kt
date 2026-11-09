package com.singularity.todo.feature.settings

/**
 * One row the settings menu offers, in the order it offers it.
 *
 * The menu is not a list of tabs: it is a list of *rows*, and a row is either a
 * section heading or a tab. Both layouts — the wide nav rail and the narrow
 * sheet — iterate this one list, so "the phone shows the same groups, with the
 * same tabs under them, in the same order as the wide window" is a property of
 * the data rather than a coincidence of two `forEach` loops that happen to read
 * the same source.
 *
 * Before this existed, each layout walked `SETTINGS_GROUPS` itself and nested
 * its own two loops. Nothing failed when a layout reordered, filtered or
 * flattened them: the two screens drifted apart and no test could see it,
 * because there was nothing to assert on but the headings' text.
 */
internal sealed interface SettingsMenuRow {
    data class Group(val group: SettingsGroup) : SettingsMenuRow

    data class Tab(val tab: SettingsTab) : SettingsMenuRow
}

/**
 * Flattens [groups] into the rows a menu renders, in order.
 *
 * The argument is not decoration: [SETTINGS_MENU_ROWS] is this function applied
 * to the real list, and `SettingsMenuTest` calls it with a synthetic one to
 * prove the headings come from the groups rather than from a second, parallel
 * copy of the section list. A menu that read its own list would ignore the
 * argument and fail that test.
 */
internal fun settingsMenuRows(groups: List<SettingsGroup>): List<SettingsMenuRow> =
    groups.flatMap { group ->
        buildList {
            add(SettingsMenuRow.Group(group))
            group.tabs.forEach { tab -> add(SettingsMenuRow.Tab(tab)) }
        }
    }

/**
 * The settings menu, row by row — the one list both layouts read.
 *
 * Every tab reachable from the narrow screen is reachable because it is a row
 * here: the sheet has a single scroller over this list, so a row that is in the
 * list is composed and can be scrolled to, and a tab that is missing here is
 * missing from both layouts at once (which `SettingsMenuTest` forbids).
 */
internal val SETTINGS_MENU_ROWS: List<SettingsMenuRow> = settingsMenuRows(SETTINGS_GROUPS)
