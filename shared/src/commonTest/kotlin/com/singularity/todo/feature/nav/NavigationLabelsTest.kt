package com.singularity.todo.feature.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 * Pure tests for the desktop-drawer destination enum.
 *
 * The enum is now a flat list of 9 destinations (no `NavGroup` / no icons /
 * no `grouped` map). These tests assert the basic invariants: every entry
 * has a non-blank title, and every entry is unique by name.
 *
 * Behaviour tests for navigation itself live in `AppNavigatorTest` (widget).
 */
class NavigationLabelsTest {

    @Test
    fun `every destination has a non-blank title`() {
        NavDestination.entries.forEach { dest ->
            assertTrue(dest.title.isNotBlank(), "Destination $dest must have a non-blank title")
        }
    }

    @Test
    fun `all destination names are distinct`() {
        val names = NavDestination.entries.map { it.name }
        assertEquals(names.size, names.toSet().size, "Destination names must be distinct")
    }

    @Test
    fun `no two destinations share the same title`() {
        val titles = NavDestination.entries.map { it.title }
        assertEquals(titles.size, titles.toSet().size, "Destination titles must be distinct")
    }
}
