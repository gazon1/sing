package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import com.singularity.todo.core.platform.systemToday
import com.singularity.todo.feature.agenda.presentation.nav.androidAgendaContextMenuHost
import com.singularity.todo.feature.nav.entries.agendaEntries
import com.singularity.todo.feature.nav.entries.appEntries
import com.singularity.todo.feature.nav.entries.calendarEntries
import com.singularity.todo.feature.nav.entries.notesEntries
import com.singularity.todo.feature.nav.entries.projectsEntries
import com.singularity.todo.feature.nav.entries.searchEntries
import com.singularity.todo.feature.nav.entries.settingsEntries
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.tasks.presentation.nav.TasksNavGraph
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject

/**
 * Creates the app-wide entry provider for Android.
 *
 * Uses `androidx.navigation3.runtime.entryProvider { }` DSL (NOT koin's `navigation {}` DSL)
 * to manually build `NavEntry<AppDestination>` objects. Inside each entry's `@Composable`
 * content, `koinViewModel()` works normally.
 *
 * This bypasses the koin `navigation {}` DSL classpath conflict where the multiplatform
 * metadata JAR shadows the platform-specific implementation.
 *
 * ## What this file holds now
 *
 * The entry *bodies* moved to the contributors in `feature/nav/entries/` — one per
 * feature, in common code. What stays here is what is genuinely Android's:
 *
 * - the context menu host (a bottom sheet, opened by a long press),
 * - the tasks graph entry, which on Desktop seeds its own per-entry stack.
 *
 * Android lets every graph own its stack: the saved-state backing already survives
 * recreation, so there are no hoisted stacks here — hence the empty [AppNavStacks].
 *
 * @param today the date a calendar route with no explicit anchor opens at. Required
 *   (#91) for the same reason as the desktop provider: a date computed outside the
 *   Koin graph is a date a test cannot reach. Not a composable, so the caller
 *   resolves the clock and passes the result.
 * @param pomodoroTimer injected as a default argument, i.e. once per *shell*
 *   composition, not inside the Pomodoro entry's content (#414): resolving the Koin
 *   graph from entry content runs `Scope.get` deep inside NavDisplay's composition
 *   stack, and a factory binding recreated the timer on every visit to the tab.
 */
@Composable
fun createAppEntryProvider(
    nav: NavCallbacks,
    today: LocalDate = systemToday(),
    pomodoroTimer: PomodoroTimer = koinInject(),
    pomodoroTaskList: PomodoroTaskListProvider = koinInject(),
): (AppDestination) -> NavEntry<AppDestination> = entryProvider {
    // No hoisted stacks on Android: each graph's stack is saved-state backed.
    val stacks = AppNavStacks()

    appEntries(nav, pomodoroTimer, pomodoroTaskList)
    agendaEntries(nav, stacks, androidAgendaContextMenuHost())
    calendarEntries(nav, stacks, today)
    notesEntries(nav, stacks)
    projectsEntries(nav, stacks)
    searchEntries(nav, stacks)
    settingsEntries(nav, stacks)

    // The tasks graph entry stays platform-specific. On Desktop the pushed graph
    // seeds its own per-entry stack and adds the detail route to it, so that the
    // right frame renders immediately — a detail route pushed onto a stack whose
    // top is the start route is not the frame the user asked for.
    entry<AppDestination.TasksGraph> { route ->
        TasksNavGraph(
            start = route.start.toTasksRoute(route.initialDueDate),
            onExitGraph = nav.graphExit,
        )
    }
}
