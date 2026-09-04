package com.singularity.todo.feature.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NavigationLabelsTest {

    @Test
    fun `top bar title matches destination title`() {
        NavDestination.entries.forEach { dest ->
            assertEquals(dest.title, dest.topBarTitle())
        }
    }

    @Test
    fun `all groups have non-blank labels`() {
        NavGroup.entries.forEach { group ->
            val label = group.label()
            assertTrue(label.isNotBlank(), "Group $group label must not be blank")
        }
    }

    @Test
    fun `grouped map covers every destination exactly once`() {
        val grouped = NavDestination.grouped
        assertEquals(NavDestination.entries.size, grouped.values.sumOf { it.size })
        NavGroup.entries.forEach { group ->
            assertTrue(grouped.containsKey(group), "Group $group must be present as key")
        }
    }

    @Test
    fun `at returns destination by ordinal`() {
        NavDestination.entries.forEachIndexed { index, dest ->
            assertEquals(dest, NavDestination.at(index))
        }
    }

    @Test
    fun `all destinations belong to exactly one group`() {
        val grouped = NavDestination.grouped
        NavDestination.entries.forEach { dest ->
            val groups = grouped.entries.filter { (_, dests) -> dest in dests }
            assertEquals(1, groups.size, "Destination ${dest.name} must belong to exactly one group")
        }
    }

    @Test
    fun `group labels are distinct`() {
        val labels = NavGroup.entries.map { it.label() }
        assertEquals(labels.size, labels.toSet().size, "Group labels must be distinct")
    }
}
