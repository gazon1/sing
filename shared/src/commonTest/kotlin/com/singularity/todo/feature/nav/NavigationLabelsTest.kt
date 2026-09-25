package com.singularity.todo.feature.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure tests for [AppDestination] title uniqueness.
 *
 * [AppDestination] is a sealed interface, so we test via [DestinationKind.tabs]
 * and [DestinationKind.menuEntries] which enumerate all top-level destinations.
 */
class NavigationLabelsTest {

    @Test
    fun everyDestinationHasNonBlankTitle() {
        val allDestinations = DestinationKind.tabs + DestinationKind.menuEntries
        allDestinations.forEach { dest ->
            assertTrue(dest.title.isNotBlank(), "Destination $dest must have a non-blank title")
        }
        // Regression: verify we actually enumerated tabs and menu entries (not empty lists)
        assertTrue(DestinationKind.tabs.isNotEmpty())
        assertTrue(DestinationKind.menuEntries.isNotEmpty())
    }

    @Test
    fun noTwoDestinationsShareSameTitle() {
        val allDestinations = DestinationKind.tabs + DestinationKind.menuEntries
        val titles = allDestinations.map { it.title }
        assertEquals(titles.size, titles.toSet().size, "Destination titles must be distinct")
    }
}
