package com.singularity.todo.feature.calendar.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import com.singularity.todo.feature.calendar.presentation.screen.CalendarScreen
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.CalendarRoute

/**
 * Creates a nested navigation graph for the Calendar feature.
 *
 * Provides its own [androidx.navigation3.runtime.NavBackStack] with [CalendarRoute] keys,
 * independent of the outer app back stack. Screens inside use [LocalCalendarNavigator]
 * to navigate without needing manual callbacks.
 *
 * ## Architecture
 *
 * - [LocalCalendarNavigator], [CalendarNavigator], [CalendarRoute] — commonMain (platform-agnostic)
 * - This function — platform-specific implementations
 *   - Android: includes [rememberViewModelStoreNavEntryDecorator] for per-entry VM scoping
 *     and [BackHandler] for system back gesture
 *   - JVM: no decorators needed (desktop has no ComponentActivity scoping issue)
 *
 * @param start The initial [CalendarRoute] to show.
 * @param onExitGraph Called when the nested graph should close.
 *                    The [AppDestination] argument, if non-null, is the destination
 *                    to navigate to in the outer graph.
 * @param modifier Compose modifier for the inner [NavDisplay][androidx.navigation3.ui.NavDisplay].
 * @param backStack Optional pre-created stack. When provided, the graph uses this stack
 *                  instead of creating a new one. Used by JVM Desktop to pass a stable
 *                  stack created via [rememberInMemoryNavBackStack] in the entry block.
 */
@Composable
expect fun CalendarNavGraph(
    start: CalendarRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<CalendarRoute>? = null,
)

/**
 * Builds the [entryProvider][androidx.navigation3.runtime.entryProvider] for the Calendar
 * nested graph — the map from each [CalendarRoute] variant to the screen that renders it.
 *
 * Extracted to commonMain to eliminate the duplication between the inner `entryProvider {}`
 * inside [NavDisplay][androidx.navigation3.ui.NavDisplay] and the outer
 * [calendarEntryProvider()][calendarEntryProvider]. Both call this function; the two
 * call sites are identical for Calendar (unlike Agenda, where the inner one passes
 * `contextMenuHost` on Android — a deliberate platform difference).
 */
@Composable
internal fun calendarScreenEntryProvider() = entryProvider {
    entry<CalendarRoute.Month> { route -> CalendarScreen(route.date) }
    entry<CalendarRoute.Day> { route -> CalendarScreen(route.date) }
}

/**
 * Returns a lambda that provides a [NavEntry] for each [CalendarRoute] route type.
 *
 * Used by the outer app's [entryProvider] when it needs to delegate an
 * [AppDestination.CalendarGraph] entry to the inner nested graph.
 */
@Composable
expect fun calendarEntryProvider(): (CalendarRoute) -> NavEntry<CalendarRoute>
