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
import com.singularity.todo.core.schedule.WorkingDays
import com.singularity.todo.core.tasks.TasksSettingsRepository
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.presentation.screen.AgendaScreen
import com.singularity.todo.feature.agenda.presentation.screen.SavedAgendaListScreen
import com.singularity.todo.feature.agenda.presentation.screen.SavedAgendaScreen
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaScreenMode
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.tasks.domain.logic.stagnationWindowOf
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import kotlin.time.Clock

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
    contextMenuHost: AgendaContextMenuHost = { _, _, _, _ -> },
) {
    when (route) {
        is AgendaStartRoute.Inbox -> {
            AgendaScreen(
                definition = AgendaPresets.Inbox,
                contextMenuHost = contextMenuHost,
            )
        }

        is AgendaStartRoute.Today -> {
            TodayAgendaContent(contextMenuHost = contextMenuHost)
        }

        is AgendaStartRoute.Upcoming -> {
            AgendaScreen(
                definition = AgendaPresets.Upcoming,
                contextMenuHost = contextMenuHost,
            )
        }

        is AgendaStartRoute.Project -> {
            AgendaScreen(
                definition = AgendaPresets.byProject(route.projectId),
                contextMenuHost = contextMenuHost,
            )
        }

        is AgendaStartRoute.Tag -> {
            AgendaScreen(
                definition = AgendaPresets.byTag(route.tagId),
                contextMenuHost = contextMenuHost,
            )
        }

        is AgendaStartRoute.Tags -> {
            AgendaScreen(
                definition = AgendaPresets.byTags(route.tagIds),
                contextMenuHost = contextMenuHost,
            )
        }

        is AgendaStartRoute.SavedAgendaList -> {
            SavedAgendaListScreen()
        }

        is AgendaStartRoute.SavedAgendaResults -> {
            SavedAgendaScreen(
                mode = SavedAgendaScreenMode.View(route.viewId),
                modeHint = "Saved view",
            )
        }

        is AgendaStartRoute.SavedAgendaEdit -> {
            SavedAgendaScreen(
                mode = SavedAgendaScreenMode.Edit(route.viewId),
                modeHint = "Edit View",
            )
        }

        AgendaStartRoute.SavedAgendaCreate -> {
            SavedAgendaScreen(
                mode = SavedAgendaScreenMode.Create(AgendaPresets.All),
                modeHint = "Create View",
            )
        }

        AgendaStartRoute.Blocked -> {
            AgendaScreen(
                definition = AgendaPresets.byBlocked(),
                contextMenuHost = contextMenuHost,
            )
        }

        AgendaStartRoute.Stagnant -> {
            StagnantAgendaContent(contextMenuHost = contextMenuHost)
        }

        AgendaStartRoute.Overdue -> {
            AgendaScreen(
                definition = AgendaPresets.byOverdue(),
                contextMenuHost = contextMenuHost,
            )
        }
    }
}

/**
 * The Today tab: the work-day agenda on a working day, the plain date-based
 * agenda on a day the user marked as not a working day.
 *
 * The two are decided by [AgendaPresets.forToday] — the "caller's
 * responsibility" the work-day preset documents. This function's job is to
 * supply the inputs: the time-of-day bounds, the two working-day switches, and
 * the day of week.
 */
@Composable
private fun TodayAgendaContent(
    contextMenuHost: @Composable (
        taskUi: TaskUi,
        offset: DpOffset,
        onDismiss: () -> Unit,
        onIntent: (AgendaIntent) -> Unit,
    ) -> Unit = { _, _, _, _ -> },
) {
    val workScheduleSettings: WorkScheduleSettingsRepository = koinInject()
    // Collect work-schedule values as Compose state so recomposition triggers
    // when DataStore emits the persisted values.
    val dayStart by workScheduleSettings.dayStartMinutes.collectAsStateWithLifecycle(-1)
    val dayEnd by workScheduleSettings.dayEndMinutes.collectAsStateWithLifecycle(-1)
    val lunchStart by workScheduleSettings.lunchStartMinutes.collectAsStateWithLifecycle(-1)
    val lunchEnd by workScheduleSettings.lunchEndMinutes.collectAsStateWithLifecycle(-1)
    // The days the user works on (the Settings section is headed "Working
    // Days"), and they decide whether the work-day preset applies today.
    val workingDays by workScheduleSettings.workingDays
        .collectAsStateWithLifecycle(WorkingDays.DEFAULT)

    var workDefinition by remember { mutableStateOf<AgendaDefinition?>(null) }

    // Compute the agenda once all six inputs have been loaded. One block,
    // one source of truth: recomputing the work-day preset from the bounds
    // on every change is what lets the two working-day switches flip the
    // agenda back and forth — a second effect that transformed the result
    // in place would destroy the work-day preset the first time a switch
    // turned the weekend day into a working day.
    // LaunchedEffect (keys = the six values) so recomposition does not
    // re-run the block; it fires exactly once when the keys change.
    LaunchedEffect(
        dayStart,
        dayEnd,
        lunchStart,
        lunchEnd,
        workingDays,
    ) {
        val allBoundsPositive = dayStart >= 0 && dayEnd >= 0 && lunchStart >= 0 && lunchEnd >= 0
        val workDay =
            if (allBoundsPositive) {
                AgendaPresets.workDayPreset(dayStart, dayEnd, lunchStart, lunchEnd)
            } else {
                AgendaPresets.Today
            }
        // The work-day preset is a work-day preset: on a day the user marked
        // as not a working day it must not apply, whatever the time-of-day
        // bounds say. `AgendaPresets.forToday` owns that decision; this block
        // only supplies the day of week and the user's working days.
        workDefinition =
            AgendaPresets.forToday(
                dayOfWeek =
                    Clock.System
                        .now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .dayOfWeek,
                workingDays = workingDays,
                workDay = workDay,
            )
    }

    // Show nothing until the preset is computed; DataStore is fast and this
    // window is typically a single frame.
    if (workDefinition != null) {
        AgendaScreen(
            definition = workDefinition!!,
            isTodayTab = true,
            contextMenuHost = contextMenuHost,
        )
    }
}

/**
 * The Stagnant drill-down: the tasks the user's own staleness threshold calls
 * stagnant.
 *
 * The Statistics card that opens this list counts with the stored threshold, and
 * `AgendaPresets.byStagnant()` defaults to 21 days. Those are the same number on a
 * fresh install and different on any install where the user moved the slider — so
 * the card said "12" and the list it opened held a different set. The count and the
 * list have to answer the same question, so the threshold is read here, exactly as
 * the Today tab reads its work hours. [stagnantDefinition] is the decision.
 *
 * The stored `0` goes through [stagnationWindowOf] for the same reason: it means
 * "the default", not "every undated task is stagnant".
 *
 * ## Why it waits for the stored value instead of starting from the default
 *
 * `AgendaScreen` builds its ViewModel from the definition it is given, and
 * `koinViewModel` returns that same instance for as long as the entry lives. So the
 * *first* definition the screen is composed with is the one it keeps. Starting from
 * the default 21 days and swapping the definition once DataStore answers would leave
 * the ViewModel evaluating the default while the card above counted the stored
 * threshold — the mismatch this function exists to remove, re-introduced one layer
 * down. The sentinel is the same one the Today tab uses for its work hours: render
 * nothing until the value is real, then build the definition once.
 */
@Composable
private fun StagnantAgendaContent(
    contextMenuHost: @Composable (
        taskUi: TaskUi,
        offset: DpOffset,
        onDismiss: () -> Unit,
        onIntent: (AgendaIntent) -> Unit,
    ) -> Unit = { _, _, _, _ -> },
) {
    val tasksSettings: TasksSettingsRepository = koinInject()
    // -1 is "not read yet"; 0 is a real setting that means "use the default".
    val stalenessDays by tasksSettings.stalenessThresholdDays.collectAsStateWithLifecycle(-1)

    if (stalenessDays < 0) return

    val definition = remember(stalenessDays) { stagnantDefinition(stalenessDays) }

    AgendaScreen(
        definition = definition,
        contextMenuHost = contextMenuHost,
    )
}

/**
 * The definition the Stagnant drill-down evaluates, from the stored threshold.
 *
 * A pure function, so the decision the screen makes can be pinned by a test without
 * mounting it: [StagnantAgendaContent] only supplies the setting.
 *
 * The stored `0`/negative value means "the default", which [stagnationWindowOf]
 * translates and the preset's own default completes — so this never builds a
 * zero-day window that would make every undated task stagnant on arrival.
 */
internal fun stagnantDefinition(stalenessDays: Int): AgendaDefinition =
    AgendaPresets.byStagnant(
        stagnationWindowOf(stalenessDays) ?: Selector.Stagnant.DEFAULT_STAGNATION_THRESHOLD,
    )

/**
 * Maps this route to an [AgendaDefinition].
 *
 * Use when you need the definition without the screen wrapper.
 *
 * The Stagnant branch is the one preset whose definition a pure function cannot
 * state: its threshold is a stored setting, and reading it needs the settings port.
 * The screen owns the real definition ([StagnantAgendaContent]); this returns the
 * default-window one, which is what a caller with no settings can honestly offer.
 * The two produce the same sections — a section id, a header and one selector —
 * and differ only in the window value.
 */
fun AgendaStartRoute.toDefinition(): AgendaDefinition =
    when (this) {
        is AgendaStartRoute.Inbox -> AgendaPresets.Inbox

        is AgendaStartRoute.Today -> AgendaPresets.Today

        is AgendaStartRoute.Upcoming -> AgendaPresets.Upcoming

        is AgendaStartRoute.Project -> AgendaPresets.byProject(projectId)

        is AgendaStartRoute.Tag -> AgendaPresets.byTag(tagId)

        is AgendaStartRoute.Tags -> AgendaPresets.byTags(tagIds)

        is AgendaStartRoute.SavedAgendaList -> AgendaPresets.All

        // Not used — results are loaded by SavedAgendaViewModel and rendered directly
        is AgendaStartRoute.SavedAgendaResults -> AgendaPresets.All

        // Not used
        is AgendaStartRoute.SavedAgendaEdit -> AgendaPresets.All

        // Not used
        AgendaStartRoute.SavedAgendaCreate -> AgendaPresets.All

        AgendaStartRoute.Blocked -> AgendaPresets.byBlocked()

        AgendaStartRoute.Stagnant -> AgendaPresets.byStagnant()

        AgendaStartRoute.Overdue -> AgendaPresets.byOverdue()
    }
