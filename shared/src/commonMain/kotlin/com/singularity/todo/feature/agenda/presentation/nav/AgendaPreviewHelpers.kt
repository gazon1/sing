package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.nav.AgendaStartRoute

/**
 * An [AgendaNavigator] subclass with all navigation methods as no-ops.
 * Used in @Preview composables to avoid needing a real [NavBackStack].
 */
class PreviewAgendaNavigator :
    AgendaNavigator(
        backStack = NavBackStack(AgendaStartRoute.SavedAgendaList, AgendaStartRoute.SavedAgendaList),
        onExitGraph = {},
    ) {
    override fun openSavedAgendaList() { /* no-op for preview */ }
    override fun openSavedAgendaResults(viewId: SavedAgendaViewId) { /* no-op for preview */ }
    override fun openSavedAgendaEdit(viewId: SavedAgendaViewId) { /* no-op for preview */ }
    override fun openSavedAgendaCreate(seed: AgendaDefinition) { /* no-op for preview */ }
    override fun back() { /* no-op for preview */ }
}

/**
 * Provides [PreviewAgendaNavigator] to descendant @Preview composables.
 *
 * Usage:
 * ```
 * @Preview
 * @Composable
 * private fun MyScreenPreview() = PreviewAgendaNavigator {
 *     MyScreenContent(state = ..., onNavigate = {})
 * }
 * ```
 */
@Composable
fun PreviewAgendaNavigator(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAgendaNavigator provides PreviewAgendaNavigator(),
        content = content,
    )
}
