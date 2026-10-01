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
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaScreenMode
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
 * @param contextMenuHost Optional context menu host passed to [AgendaScreen].
 *        Defaults to no-op. On Desktop/JVM this is the real desktop context menu.
 */
@Composable
fun AgendaNavContent(
    route: AgendaStartRoute,
    contextMenuHost: @Composable (
        taskUi: TaskUi,
        offset: DpOffset,
        onDismiss: () -> Unit,
        onIntent: (AgendaIntent) -> Unit,
    ) -> Unit = { _, _, _, _ -> },
) {
    when (route) {
        is AgendaStartRoute.Inbox -> AgendaScreen(
            definition = AgendaPresets.Inbox,
            contextMenuHost = contextMenuHost,
        )

        is AgendaStartRoute.Today -> AgendaScreen(
            definition = AgendaPresets.Today,
            contextMenuHost = contextMenuHost,
        )

        is AgendaStartRoute.Upcoming -> AgendaScreen(
            definition = AgendaPresets.Upcoming,
            contextMenuHost = contextMenuHost,
        )

        is AgendaStartRoute.Project -> AgendaScreen(
            definition = AgendaPresets.byProject(route.id),
            contextMenuHost = contextMenuHost,
        )

        is AgendaStartRoute.Tag -> AgendaScreen(
            definition = AgendaPresets.byTag(route.id),
            contextMenuHost = contextMenuHost,
        )

        is AgendaStartRoute.SavedAgendaList -> SavedAgendaListScreen()

        is AgendaStartRoute.SavedAgendaResults -> SavedAgendaScreen(
            mode = SavedAgendaScreenMode.View(SavedAgendaViewId.fromString(route.viewId)),
            modeHint = "Saved view",
        )

        is AgendaStartRoute.SavedAgendaEdit -> SavedAgendaScreen(
            mode = SavedAgendaScreenMode.Edit(SavedAgendaViewId.fromString(route.viewId)),
            modeHint = "Edit View",
        )

        AgendaStartRoute.SavedAgendaCreate -> SavedAgendaScreen(
            mode = SavedAgendaScreenMode.Create(AgendaPresets.Inbox),
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

    is AgendaStartRoute.SavedAgendaList -> AgendaPresets.Inbox

    // Not used — results are loaded by SavedAgendaViewModel and rendered directly
    is AgendaStartRoute.SavedAgendaResults -> AgendaPresets.Inbox

    // Not used
    is AgendaStartRoute.SavedAgendaEdit -> AgendaPresets.Inbox

    // Not used
    AgendaStartRoute.SavedAgendaCreate -> AgendaPresets.Inbox // Not used
}
