package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import com.singularity.todo.core.platform.systemToday
import com.singularity.todo.feature.agenda.presentation.nav.AgendaNavGraph
import com.singularity.todo.feature.ai.chat.ChatScreen
import com.singularity.todo.feature.ai.usage.AiUsageScreen
import com.singularity.todo.feature.archive.ArchiveScreen
import com.singularity.todo.feature.calendar.presentation.nav.CalendarNavGraph
import com.singularity.todo.feature.notes.presentation.nav.NotesNavGraph
import com.singularity.todo.feature.pomodoro.PomodoroScreen
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.profile.ProfileSwitcherScreen
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.nav.ProjectsNavGraph
import com.singularity.todo.feature.search.presentation.nav.SearchNavGraph
import com.singularity.todo.feature.settings.presentation.nav.SettingsNavGraph
import com.singularity.todo.feature.statistics.StatisticsScreen
import com.singularity.todo.feature.tasks.presentation.nav.TasksNavGraph
import org.koin.compose.koinInject

/**
 * Creates the app-wide entry provider for JVM Desktop, using the same [entryProvider] DSL
 * as Android.
 *
 * **Stack hoisting (desktop only).** Top-level graph stacks are created *here*, in the
 * shell's composition, and passed into the graphs via their `backStack` parameter —
 * never with `remember` inside the entry { } content. Desktop recomposes NavDisplay's
 * entries wholesale on tab switches and on every outer push above a top-level entry, so
 * an entry-local `remember { NavBackStack }` is destroyed and the nested stack reseeds at
 * its start route (ADR `2026-10-01-desktop-nav-followup`, Bug 3: "nested back stack dies
 * on tab switch"). Hoisting makes nested state survive: a cross-feature open that pushes
 * above Plans and a tab round-trip both return to the frame the user was on.
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
 * @param today the date a calendar route with no explicit anchor opens at. Required
 *   (#91): the two call sites below read `systemToday()` *outside* the Koin graph, so a
 *   test that binds a fixed `Clock` in its module was overridden by a second,
 *   un-injectable read of the host's wall clock. `CalendarFlowTest` failed on exactly
 *   this — the harness supplied a fixed clock, the app rendered the host's month.
 */
@Composable
fun createJvmEntryProvider(
    nav: NavCallbacks,
    today: kotlinx.datetime.LocalDate = systemToday(),
): (AppDestination) -> NavEntry<AppDestination> {
    // Hoisted top-level graph stacks — see the KDoc above for why they must live in the
    // shell composition rather than inside the entry content.
    val projectsStack = remember { NavBackStack<ProjectsRoute>(ProjectsRoute.List) }
    val calendarStack = remember {
        NavBackStack<CalendarRoute>(CalendarRoute.Month(today.toString()))
    }
    val notesStack = remember { NavBackStack<NotesRoute>(NotesRoute.List) }
    val searchStack = remember { NavBackStack<Search>(Search) }
    val settingsStack = remember { NavBackStack<Settings>(Settings) }
    val agendaStacks = remember {
        mapOf(
            AgendaStartRoute.Today to NavBackStack<AgendaStartRoute>(AgendaStartRoute.Today),
            AgendaStartRoute.Upcoming to NavBackStack<AgendaStartRoute>(AgendaStartRoute.Upcoming),
            AgendaStartRoute.Inbox to NavBackStack<AgendaStartRoute>(AgendaStartRoute.Inbox),
        )
    }

    return entryProvider {
        // ─── Top-level tabs ────────────────────────────────────────────────

        entry<AppDestination.Plans> {
            ProjectsNavGraph(
                start = ProjectsRoute.List,
                onExitGraph = nav.graphExit,
                backStack = projectsStack,
            )
        }

        entry<AppDestination.Pomodoro> {
            PomodoroScreen(
                timer = koinInject<PomodoroTimer>(),
                taskListProvider = koinInject<PomodoroTaskListProvider>(),
            )
        }

        entry<AppDestination.Statistics> {
            StatisticsScreen()
        }

        entry<AppDestination.Calendar> {
            CalendarNavGraph(
                start = CalendarRoute.Month(today.toString()),
                onExitGraph = nav.graphExit,
                backStack = calendarStack,
            )
        }

        // ─── Menu destinations ─────────────────────────────────────────────

        entry<AppDestination.Notes> {
            NotesNavGraph(
                navCallbacks = nav,
                start = NotesRoute.List,
                backStack = notesStack,
            )
        }

        entry<AppDestination.AiChat> {
            ChatScreen()
        }

        entry<AppDestination.Search> {
            SearchNavGraph(
                navCallbacks = nav,
                backStack = searchStack,
            )
        }

        entry<AppDestination.Archive> {
            ArchiveScreen(onBack = { nav.goBack() })
        }

        entry<AppDestination.Settings> {
            SettingsNavGraph(
                navCallbacks = nav,
                backStack = settingsStack,
            )
        }

        entry<AppDestination.AiUsage> {
            AiUsageScreen()
        }

        entry<AppDestination.ProfileSwitcher> {
            ProfileSwitcherScreen(onBack = { nav.goBack() })
        }

        // ─── Sub-routes ────────────────────────────────────────────────────

        entry<AppDestination.ProjectEditor> { route ->
            ProjectsNavGraph(
                start = ProjectsRoute.Editor(route.projectId?.let { ProjectId.fromString(it) }),
                onExitGraph = nav.graphExit,
            )
        }

        entry<AppDestination.ProjectDetail> { route ->
            ProjectsNavGraph(
                start = ProjectsRoute.Detail(ProjectId.fromString(route.projectId)),
                onExitGraph = nav.graphExit,
            )
        }

        entry<AppDestination.ProjectsGraph> { route ->
            ProjectsNavGraph(
                start = route.start.toProjectsRoute(),
                onExitGraph = nav.graphExit,
            )
        }

        // TasksGraph entry: converts TasksStartRoute to TasksRoute for the inner graph
        entry<AppDestination.TasksGraph> { route ->
            val tasksStack: NavBackStack<TasksRoute> = rememberInMemoryNavBackStack(TasksRoute.Create(null))
            val startRoute = route.start.toTasksRoute(route.initialDueDate)
            // NavDisplay renders based on stack.top, not the start parameter. When starting
            // with Detail, add it to the stack so the correct entry is rendered immediately.
            if (startRoute is TasksRoute.Detail) {
                tasksStack.add(startRoute)
            }
            TasksNavGraph(
                start = startRoute,
                onExitGraph = nav.graphExit,
                backStack = tasksStack,
            )
        }

        // NotesGraph entry: converts NotesStartRoute to NotesRoute for the inner graph
        entry<AppDestination.NotesGraph> { route ->
            NotesNavGraph(
                navCallbacks = nav,
                start = route.start.toNotesRoute(),
            )
        }

        // CalendarGraph entry
        entry<AppDestination.CalendarGraph> { route ->
            CalendarNavGraph(
                start = route.start.toCalendarRoute(),
                onExitGraph = nav.graphExit,
            )
        }

        // AgendaGraph entry — the three tab starts share their hoisted stacks so an inner
        // push (SavedAgendaEdit, …) survives a tab round-trip; any other start falls back
        // to a per-entry stack, as before.
        entry<AppDestination.AgendaGraph> { route ->
            val agendaStack: NavBackStack<AgendaStartRoute> =
                agendaStacks[route.start] ?: rememberInMemoryNavBackStack(route.start)
            // agendaStack.top (seed = route.start) always equals route.start, so no add() needed.
            AgendaNavGraph(
                start = route.start,
                onExitGraph = nav.graphExit,
                backStack = agendaStack,
            )
        }
    }
}
