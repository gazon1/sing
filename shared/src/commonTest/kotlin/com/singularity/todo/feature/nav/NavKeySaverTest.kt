package com.singularity.todo.feature.nav

import androidx.compose.runtime.saveable.SaverScope
import androidx.navigation3.runtime.NavKey
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The selected tab is saved, so a rotation — a configuration change — does not throw
 * the app back to the start route with the editor gone.
 *
 * The route is written with the same polymorphic registration the back stacks use,
 * which is why it round-trips through the saved-state codec instead of a hand-written
 * serializer. The second test is the other half: a route that is not a top-level
 * route must not restore, or the shell would point at a route it has no stack for.
 *
 * @see navKeySaver
 */
@Tag("fast")
class NavKeySaverTest {
    private val topLevelRoutes: Set<NavKey> =
        DestinationKind.tabs.toSet() + DestinationKind.menuEntries.toSet()

    private fun roundTrip(route: NavKey): NavKey? {
        val saver = navKeySaver(topLevelRoutes)
        val saved = with(saver) { SaverScope { true }.save(route) }
        return saver.restore(checkNotNull(saved))
    }

    @Test
    fun `the selected tab survives a save and a restore`() {
        val route: NavKey = AppDestination.AgendaGraph(AgendaStartRoute.Inbox)
        assertEquals(route, roundTrip(route))
    }

    @Test
    fun `a route that is not a top-level route does not restore`() {
        val route: NavKey = AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create())
        assertNull(roundTrip(route))
    }
}
