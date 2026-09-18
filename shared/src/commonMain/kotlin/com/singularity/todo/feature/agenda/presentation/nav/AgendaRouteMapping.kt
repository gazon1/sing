package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpOffset
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.presentation.screen.AgendaScreen
import com.singularity.todo.feature.agenda.presentation.screen.SavedAgendaListScreen
import com.singularity.todo.feature.agenda.presentation.screen.SavedAgendaScreen
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.tasks.presentation.model.TaskUi

/**
 * Builds the screen content for a given [AgendaStartRoute].
 *
 * Use inside an `entry { }` block:
 * ```
 * entryProvider {
 *     entry<AgendaStartRoute.Inbox> { AgendaNavContent.build(this) }
 *     entry<AgendaStartRoute.Today> { AgendaNavContent.build(this) }
 *     // ...
 * }
 * ```
 *
 * @param route        The route to build content for. Must be the receiver of the `entry { }` lambda.
 * @param desktopContextMenuHost Optional context menu host passed to [AgendaScreen].
 *        Defaults to no-op. On Desktop/JVM this is the real desktop context menu.
 */
@Composable
fun AgendaNavContent(
    route: AgendaStartRoute,
    desktopContextMenuHost: @Composable (
        taskUi: TaskUi,
        offset: DpOffset,
        onDismiss: () -> Unit,
        onIntent: (AgendaIntent) -> Unit,
    ) -> Unit = { _, _, _, _ -> },
) {
    when (route) {
        is AgendaStartRoute.Inbox -> AgendaScreen(
            definition = AgendaPresets.Inbox,
            desktopContextMenuHost = desktopContextMenuHost,
        )

        is AgendaStartRoute.Today -> AgendaScreen(
            definition = AgendaPresets.Today,
            desktopContextMenuHost = desktopContextMenuHost,
        )

        is AgendaStartRoute.Upcoming -> AgendaScreen(
            definition = AgendaPresets.Upcoming,
            desktopContextMenuHost = desktopContextMenuHost,
        )

        is AgendaStartRoute.Project -> AgendaScreen(
            definition = AgendaPresets.byProject(route.id),
            desktopContextMenuHost = desktopContextMenuHost,
        )

        is AgendaStartRoute.Tag -> AgendaScreen(
            definition = AgendaPresets.byTag(route.id),
            desktopContextMenuHost = desktopContextMenuHost,
        )

        is AgendaStartRoute.SavedAgendaList -> SavedAgendaListScreen()

        is AgendaStartRoute.SavedAgendaEdit -> SavedAgendaScreen(
            viewId = SavedAgendaViewId.fromString(route.viewId),
            seed = null,
            modeHint = "Edit View",
        )

        AgendaStartRoute.SavedAgendaCreate -> SavedAgendaScreen(
            viewId = null,
            seed = AgendaPresets.Inbox,
            modeHint = "Create View",
        )
    }
}

/**
 * Maps this route to an [AgendaDefinition].
 *
 * Use when you need the definition without the screen wrapper.
 */
fun AgendaStartRoute.toDefinition(): AgendaDefinition = when (this) {
    is AgendaStartRoute.Inbox -> AgendaPresets.Inbox
    is AgendaStartRoute.Today -> AgendaPresets.Today
    is AgendaStartRoute.Upcoming -> AgendaPresets.Upcoming
    is AgendaStartRoute.Project -> AgendaPresets.byProject(id)
    is AgendaStartRoute.Tag -> AgendaPresets.byTag(id)
    is AgendaStartRoute.SavedAgendaList -> AgendaPresets.Inbox // Not used
    is AgendaStartRoute.SavedAgendaEdit -> AgendaPresets.Inbox // Not used
    AgendaStartRoute.SavedAgendaCreate -> AgendaPresets.Inbox // Not used
}
