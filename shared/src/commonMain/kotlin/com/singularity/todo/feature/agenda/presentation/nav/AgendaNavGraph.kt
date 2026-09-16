package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination

/**
 * Creates a nested navigation graph for the Agenda feature.
 *
 * Provides its own [androidx.navigation3.runtime.NavBackStack] with [AgendaRoute] keys,
 * independent of the outer app back stack. Screens inside use [LocalAgendaNavigator]
 * to navigate without needing manual callbacks.
 *
 * ## Architecture
 *
 * - [LocalAgendaNavigator], [AgendaNavigator], [AgendaRoute] — commonMain (platform-agnostic)
 * - This function — platform-specific implementations
 *   - Android: includes SavedState for process death survival
 *   - JVM: in-memory back stack, no SavedState needed
 *
 * @param start The initial [AgendaStartRoute] to show.
 * @param onExitGraph Called when the nested graph should close.
 *                    The [AppDestination] argument, if non-null, is the destination
 *                    to navigate to in the outer graph.
 * @param modifier Compose modifier for the inner [NavDisplay][androidx.navigation3.ui.NavDisplay].
 */
@Composable
expect fun AgendaNavGraph(
    start: AgendaStartRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier = Modifier,
)

/**
 * Returns a lambda that provides a [NavEntry] for each [AgendaStartRoute] route type.
 *
 * Used by the outer app's [entryProvider] when it needs to delegate an
 * [AppDestination.AgendaGraph] entry to the inner nested graph.
 */
@Composable
expect fun agendaEntryProvider(): (AgendaStartRoute) -> NavEntry<AgendaStartRoute>
