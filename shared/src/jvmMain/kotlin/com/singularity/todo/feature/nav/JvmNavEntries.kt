package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import com.singularity.todo.core.platform.systemToday
import com.singularity.todo.feature.agenda.presentation.nav.desktopAgendaContextMenuHost
import com.singularity.todo.feature.nav.entries.agendaEntries
import com.singularity.todo.feature.nav.entries.appEntries
import com.singularity.todo.feature.nav.entries.calendarEntries
import com.singularity.todo.feature.nav.entries.notesEntries
import com.singularity.todo.feature.nav.entries.projectsEntries
import com.singularity.todo.feature.nav.entries.searchEntries
import com.singularity.todo.feature.nav.entries.settingsEntries
import com.singularity.todo.feature.nav.stableContentKey
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.tasks.presentation.nav.TasksNavGraph
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject

/**
 * Creates the app-wide entry provider for JVM Desktop, using the same [entryProvider] DSL
 * as Android.
 *
 * ## Stack hoisting (desktop only)
 *
 * Top-level graph stacks are created *here*, in the shell's composition, and passed into
 * the graphs via their `backStack` parameter — never with `remember` inside the entry { }
 * content. Desktop recomposes NavDisplay's entries wholesale on tab switches and on every
 * outer push above a top-level entry, so an entry-local `remember { NavBackStack }` is
 * destroyed and the nested stack reseeds at its start route (ADR
 * `2026-10-01-desktop-nav-followup`, Bug 3: "nested back stack dies on tab switch").
 * Hoisting makes nested state survive: a cross-feature open that pushes above Plans and a
 * tab round-trip both return to the frame the user was on.
 *
 * Android keeps its entry-local `rememberNavBackStack(navSavedStateConfig(), start)` —
 * it is saveable-backed, so entry recreation restores it.
 *
 * Sub-route entries (`TasksGraph`, `ProjectsGraph`, `AgendaGraph` as a pushed target, …)
 * still seed a per-entry stack: their entry key carries the start route, so recreation
 * re-seeds to the right frame; only the plain top-level graph entries need hoisting.
 *
 * [AgendaStartRoute.SavedAgendaEdit] and other non-tab agenda starts fall back to a
 * per-entry stack — they are never top-level routes, so there is nothing to hoist.
 *
 * ## The date the calendar opens at
 *
 * `today` is a parameter rather than a `systemToday()` read inside the graph (#91): the
 * two call sites below read `systemToday()` *outside* the Koin graph, so a test that
 * binds a fixed `Clock` in its module was overridden by a second, un-injectable read of
 * the host's wall clock. `CalendarFlowTest` failed on exactly this — the harness
 * supplied a fixed clock, the app rendered the host's month.
 *
 * @param today the date a calendar route with no explicit anchor opens at.
 * @param pomodoroTimer resolved once per shell composition, not inside the entry
 *   content (#414).
 */
@Composable
fun createJvmEntryProvider(
    nav: NavCallbacks,
    today: LocalDate = systemToday(),
    pomodoroTimer: PomodoroTimer = koinInject(),
    pomodoroTaskList: PomodoroTaskListProvider = koinInject(),
): (AppDestination) -> NavEntry<AppDestination> {
    // Hoisted top-level graph stacks — see the KDoc above for why they must live in the
    // shell composition rather than inside the entry content.
    val stacks = AppNavStacks(
        projects = remember { NavBackStack<ProjectsRoute>(ProjectsRoute.List) },
        calendar = remember { NavBackStack<CalendarRoute>(CalendarRoute.Month(today.toString())) },
        notes = remember { NavBackStack<NotesRoute>(NotesRoute.List) },
        search = remember { NavBackStack<Search>(Search) },
        settings = remember { NavBackStack<Settings>(Settings) },
        agenda = remember {
            mapOf(
                AgendaStartRoute.Today to NavBackStack<AgendaStartRoute>(AgendaStartRoute.Today),
                AgendaStartRoute.Upcoming to NavBackStack<AgendaStartRoute>(AgendaStartRoute.Upcoming),
                AgendaStartRoute.Inbox to NavBackStack<AgendaStartRoute>(AgendaStartRoute.Inbox),
            )
        },
    )

    return entryProvider {
        appEntries(nav, pomodoroTimer, pomodoroTaskList)
        agendaEntries(nav, stacks, desktopAgendaContextMenuHost())
        calendarEntries(nav, stacks, today)
        notesEntries(nav, stacks)
        projectsEntries(nav, stacks)
        searchEntries(nav, stacks)
        settingsEntries(nav, stacks)

        // The tasks graph entry stays platform-specific: the pushed graph seeds its own
        // per-entry stack, and that stack starts at the route the outer graph asked for.
        //
        // It used to start at a placeholder — `Create(null)` — with the requested route
        // pushed on top when it was a detail. A placeholder put a screen the person never
        // asked for underneath the one they did: back from a task's detail screen opened
        // an empty editor instead of leaving the graph, and the date and the section
        // prefill the route carried were dropped on the way, because the editor read the
        // placeholder rather than the route. NavDisplay renders `stack.top`, so the seed
        // *is* the screen.
        entry<AppDestination.TasksGraph>(clazzContentKey = { it.stableContentKey() }) { route ->
            val startRoute = route.start.toTasksRoute(route.initialDueDate)
            val tasksStack: NavBackStack<TasksRoute> = rememberInMemoryNavBackStack(startRoute)
            TasksNavGraph(
                start = startRoute,
                onExitGraph = nav.graphExit,
                backStack = tasksStack,
            )
        }
    }
}
