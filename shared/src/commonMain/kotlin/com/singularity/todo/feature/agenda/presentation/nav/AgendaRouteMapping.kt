package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.schedule.WorkScheduleSettingsRepository
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
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

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

        is AgendaStartRoute.Today -> {
            val workScheduleSettings: WorkScheduleSettingsRepository = koinInject()
            // Collect work-schedule values as Compose state so recomposition triggers
            // when DataStore emits the persisted values.
            val dayStart by workScheduleSettings.dayStartMinutes.collectAsStateWithLifecycle(-1)
            val dayEnd by workScheduleSettings.dayEndMinutes.collectAsStateWithLifecycle(-1)
            val lunchStart by workScheduleSettings.lunchStartMinutes.collectAsStateWithLifecycle(-1)
            val lunchEnd by workScheduleSettings.lunchEndMinutes.collectAsStateWithLifecycle(-1)

            var workDefinition by remember { mutableStateOf<AgendaDefinition?>(null) }

            // Compute the work-day agenda once all four values have been loaded.
            // Uses LaunchedEffect (key = all four values) so recomposition does not
            // re-run the block; it fires exactly once when the keys change.
            LaunchedEffect(dayStart, dayEnd, lunchStart, lunchEnd) {
                workDefinition = if (dayStart >= 0 && dayEnd >= 0 && lunchStart >= 0 && lunchEnd >= 0) {
                    AgendaPresets.workDayPreset(dayStart, dayEnd, lunchStart, lunchEnd)
                } else {
                    AgendaPresets.Today
                }
            }

            // Show nothing until the preset is computed; DataStore is fast and this
            // window is typically a single frame.
            if (workDefinition != null) {
                AgendaScreen(
                    definition = workDefinition!!,
                    contextMenuHost = contextMenuHost,
                )
            }
        }

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

        is AgendaStartRoute.Tags -> AgendaScreen(
            definition = AgendaPresets.byTags(route.ids),
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

        AgendaStartRoute.Blocked -> AgendaScreen(
            definition = AgendaPresets.byBlocked(),
            contextMenuHost = contextMenuHost,
        )

        AgendaStartRoute.Stagnant -> AgendaScreen(
            definition = AgendaPresets.byStagnant(),
            contextMenuHost = contextMenuHost,
        )

        AgendaStartRoute.Overdue -> AgendaScreen(
            definition = AgendaPresets.byOverdue(),
            contextMenuHost = contextMenuHost,
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

    is AgendaStartRoute.Tags -> AgendaPresets.byTags(ids)

    is AgendaStartRoute.SavedAgendaList -> AgendaPresets.Inbox

    // Not used — results are loaded by SavedAgendaViewModel and rendered directly
    is AgendaStartRoute.SavedAgendaResults -> AgendaPresets.Inbox

    // Not used
    is AgendaStartRoute.SavedAgendaEdit -> AgendaPresets.Inbox

    // Not used
    AgendaStartRoute.SavedAgendaCreate -> AgendaPresets.Inbox

    AgendaStartRoute.Blocked -> AgendaPresets.byBlocked()

    AgendaStartRoute.Stagnant -> AgendaPresets.byStagnant()

    AgendaStartRoute.Overdue -> AgendaPresets.byOverdue()
}
