package com.singularity.todo.feature.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import com.singularity.todo.core.platform.todayInSystemZone
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Top-level destinations shown in the Android bottom navigation bar.
 *
 * Five tabs + a separate "Menu" button that opens a `MenuBottomSheet` overlay
 * (NOT a navigation destination — see `MenuBottomSheet`).
 *
 * Mapping from screenshots (bottom bar):
 * - Inbox → Tasks (filter=Inbox)
 * - Today → Tasks (filter=Today)
 * - Plans → Projects
 * - Habits → Pomodoro
 * - Calendar → Statistics
 *
 * Why a [Serializable] sealed interface and not an enum:
 * - Type-safe payloads (e.g. [TaskDetail.taskId]) without manual route-string plumbing.
 * - Compose Navigation's `composable<AppDestination.X>` infers argument types at compile time.
 * - Adding a new destination is `data object` — no need to maintain a parallel route registry.
 *
 * Each destination carries its own [title] and [icon] — this replaces the
 * older `BottomBarEntry`/`MenuEntry` enums that duplicated the same metadata.
 *
 * [icon] is `@Transient` because [ImageVector] isn't serializable; the
 * Compose runtime uses [title] for accessibility only.
 */
sealed interface AppDestination : NavKey {
    /** User-visible label — used by BottomBar and MenuSheet. */
    val title: String

    /**
     * Inbox tab — now handled by [AgendaGraph] with [AgendaStartRoute.Inbox].
     * @deprecated Use [AgendaGraph] with [AgendaStartRoute.Inbox] instead.
     */
    @Deprecated(
        "Use AgendaGraph(AgendaStartRoute.Inbox) instead",
        replaceWith = ReplaceWith("AgendaGraph(AgendaStartRoute.Inbox)"),
    )
    @Serializable
    data object Inbox : AppDestination {
        override val title = "Inbox"
    }

    /**
     * Today tab — now handled by [AgendaGraph] with [AgendaStartRoute.Today].
     * @deprecated Use [AgendaGraph] with [AgendaStartRoute.Today] instead.
     */
    @Deprecated(
        "Use AgendaGraph(AgendaStartRoute.Today) instead",
        replaceWith = ReplaceWith("AgendaGraph(AgendaStartRoute.Today)"),
    )
    @Serializable
    data object Today : AppDestination {
        override val title = "Today"
    }

    @Serializable
    data object Plans : AppDestination {
        override val title = "Plans"
    }

    @Serializable
    data object Pomodoro : AppDestination {
        override val title = "Pomodoro"
    }

    /**
     * Upcoming tab — now handled by [AgendaGraph] with [AgendaStartRoute.Upcoming].
     * @deprecated Use [AgendaGraph] with [AgendaStartRoute.Upcoming] instead.
     */
    @Deprecated(
        "Use AgendaGraph(AgendaStartRoute.Upcoming) instead",
        replaceWith = ReplaceWith("AgendaGraph(AgendaStartRoute.Upcoming)"),
    )
    @Serializable
    data object Upcoming : AppDestination {
        override val title = "Upcoming"
    }

    @Serializable
    data object Statistics : AppDestination {
        override val title = "Statistics"
    }

    @Serializable
    data object Calendar : AppDestination {
        override val title = "Calendar"
    }

    /** Menu destinations opened from `MenuBottomSheet`. */
    @Serializable
    data object Notes : AppDestination {
        override val title = "Notes"
    }

    @Serializable
    data object AiChat : AppDestination {
        override val title = "AI Chat"
    }

    @Serializable
    data object Search : AppDestination {
        override val title = "Search"
    }

    @Serializable
    data object Archive : AppDestination {
        override val title = "Archive"
    }

    @Serializable
    data object Settings : AppDestination {
        override val title = "Settings"
    }

    @Serializable
    data object AiUsage : AppDestination {
        override val title = "AI Usage"
    }

    @Serializable
    data object ProfileSwitcher : AppDestination {
        override val title = "Profiles"
    }

    // ─── Sub-routes (push on top of a top-level destination) ────────────────

    /**
     * Start route for the tasks nested graph. Used as `start` param in [TasksGraph].
     *
     * @deprecated Inbox/Today/Upcoming/ByProject are deprecated (AgendaEngine MR1).
     * They no longer have corresponding routes in TasksNavGraph — use [Create] or [Detail].
     */
    @Serializable
    sealed interface TasksStartRoute : NavKey {
        @Deprecated("Use Create instead — deprecated in AgendaEngine MR1", ReplaceWith("Create"))
        @Serializable data object Inbox : TasksStartRoute

        @Deprecated("Use Create instead — deprecated in AgendaEngine MR1", ReplaceWith("Create"))
        @Serializable data object Today : TasksStartRoute

        @Serializable data object Create : TasksStartRoute

        @Deprecated("Use Create instead — deprecated in AgendaEngine MR1", ReplaceWith("Create"))
        @Serializable data object Upcoming : TasksStartRoute

        @Deprecated("Use Create instead — deprecated in AgendaEngine MR1", ReplaceWith("Create"))
        @Serializable data class ByProject(val projectId: String) : TasksStartRoute

        @Serializable data class Detail(val taskId: String) : TasksStartRoute
    }

    /**
     * Nested tasks graph. Contains its own NavBackStack[TasksRoute].
     * Used for: FAB "Add task" from any screen, deep-links, and direct navigation.
     */
    @Serializable
    data class TasksGraph(val start: TasksStartRoute, val initialDueDate: LocalDate? = null) : AppDestination {
        override val title = "Tasks"
    }

    /**
     * Direct entry to tasks filtered by project. Now redirects to [AgendaGraph] with [AgendaStartRoute.Project].
     * @deprecated Use [AgendaGraph] with [AgendaStartRoute.Project] instead.
     */
    @Deprecated(
        "Use AgendaGraph(AgendaStartRoute.Project(projectId)) instead",
        replaceWith = ReplaceWith("AgendaGraph(AgendaStartRoute.Project(projectId))"),
    )

    @Serializable
    data class TasksByProject(val projectId: String) : AppDestination {
        override val title = "Project Tasks"
    }

    /** @deprecated Use TasksGraph(TasksStartRoute.Create) or navigate to TasksRoute.Create internally */
    @Deprecated(
        "Use TasksGraph(TasksStartRoute.Create) instead",
        replaceWith = ReplaceWith("TasksGraph(TasksStartRoute.Detail(taskId))"),
    )

    @Serializable
    data class TaskDetail(val taskId: String) : AppDestination {
        override val title = "Task"
    }

    /** @deprecated Use TasksGraph(TasksStartRoute.Create, initialDueDate) instead */
    @Deprecated(
        "Use TasksGraph(TasksStartRoute.Create, initialDueDate) instead",
        replaceWith = ReplaceWith("TasksGraph(TasksStartRoute.Create, initialDueDate)"),
    )

    @Serializable
    data class TaskDetailCreate(val initialDueDate: String? = null) : AppDestination {
        override val title = "New Task"
    }

    @Serializable
    data class ProjectEditor(val projectId: String? = null) : AppDestination {
        override val title = "New Project"
    }

    @Serializable
    data class ProjectDetail(val projectId: String) : AppDestination {
        override val title = "Project"
    }

    /**
     * Nested projects graph. Contains its own NavBackStack[ProjectsRoute].
     * Used for deep-links and future navigation flexibility.
     */
    @Serializable
    data class ProjectsGraph(val start: ProjectsStartRoute = ProjectsStartRoute.List) : AppDestination {
        override val title = "Projects"
    }

    /**
     * Start route for the projects nested graph. Used as `start` param in [ProjectsGraph].
     */
    @Serializable
    sealed interface ProjectsStartRoute : NavKey {
        @Serializable data object List : ProjectsStartRoute

        @Serializable data class Editor(val projectId: String? = null) : ProjectsStartRoute
    }

    /**
     * Start route for the notes nested graph. Used as `start` param in [NotesGraph].
     */
    @Serializable
    sealed interface NotesStartRoute : NavKey {
        @Serializable data object List : NotesStartRoute

        @Serializable data class Preview(val noteId: String) : NotesStartRoute
    }

    /**
     * Nested notes graph. Contains its own NavBackStack[NotesRoute].
     * Used for deep-links and direct navigation.
     */
    @Serializable
    data class NotesGraph(val start: NotesStartRoute = NotesStartRoute.List) : AppDestination {
        override val title = "Notes"
    }

    /**
     * Start route for the Calendar nested graph. Used as `start` param in [CalendarGraph].
     */
    @Serializable
    sealed interface CalendarStartRoute : NavKey {
        @Serializable data class Month(val anchor: String) : CalendarStartRoute
    }

    /**
     * Nested Calendar graph. Contains its own NavBackStack[CalendarRoute].
     * Used for deep-links and direct navigation.
     */
    @Serializable
    data class CalendarGraph(
        val start: CalendarStartRoute = CalendarStartRoute.Month(
            todayInSystemZone().toString(),
        ),
    ) : AppDestination {
        override val title = "Calendar"
    }

    /**
     * Nested Agenda graph. Contains its own NavBackStack[AgendaStartRoute].
     * Used for: Inbox, Today, Upcoming tabs, and "See all" from project detail.
     */
    @Serializable
    data class AgendaGraph(val start: AgendaStartRoute = AgendaStartRoute.Inbox) : AppDestination {
        override val title = "Agenda"
    }
}

/** UI metadata for [AppDestination]. Kept separate so the route stays pure-data. */
val AppDestination.icon: ImageVector
    get() = when (this) {
        AppDestination.Inbox -> Icons.Filled.Inbox
        AppDestination.Today -> Icons.Filled.Today
        AppDestination.Upcoming -> Icons.Filled.DateRange
        AppDestination.Plans -> Icons.Filled.Check
        AppDestination.Pomodoro -> Icons.Filled.Repeat
        AppDestination.Statistics -> Icons.Filled.BarChart
        AppDestination.Calendar -> Icons.Filled.CalendarMonth
        AppDestination.Notes -> Icons.Filled.Create
        AppDestination.AiChat -> Icons.Filled.AutoAwesome
        AppDestination.Search -> Icons.Filled.Search
        AppDestination.Archive -> Icons.Filled.Inbox
        AppDestination.Settings -> Icons.Filled.Settings
        AppDestination.AiUsage -> Icons.Filled.BarChart
        AppDestination.ProfileSwitcher -> Icons.Filled.Person
        is AppDestination.TaskDetail -> Icons.Filled.Check
        is AppDestination.TaskDetailCreate -> Icons.Filled.Check
        is AppDestination.TasksGraph -> Icons.Filled.Check
        is AppDestination.TasksByProject -> Icons.Filled.Folder
        is AppDestination.ProjectEditor -> Icons.Filled.Check
        is AppDestination.ProjectDetail -> Icons.Filled.Check
        is AppDestination.ProjectsGraph -> Icons.Filled.Check
        is AppDestination.NotesGraph -> Icons.Filled.Create
        is AppDestination.CalendarGraph -> Icons.Filled.CalendarMonth
        is AppDestination.CalendarStartRoute -> Icons.Filled.CalendarMonth
        is AppDestination.AgendaGraph -> Icons.Filled.Inbox
    }

/** Title for the special "Menu" bottom-bar item that opens the bottom sheet. */
const val MenuButtonTitle = "Menu"

/**
 * Pure helpers — no Compose runtime, no Android, no JVM.
 * Testable without any UI infrastructure.
 */
object DestinationKind {
    /** Six persistent tabs shown in the bottom bar (excludes the Menu button). */
    fun isTab(destination: AppDestination): Boolean = destination in tabSet

    /** Menu destinations opened from the bottom sheet overlay. */
    fun isMenuEntry(destination: AppDestination): Boolean = destination in menuSet

    /** Anything that is NOT a top-level tab — i.e. push-on sub-routes. */
    fun isSubRoute(destination: AppDestination): Boolean = destination !in tabSet && destination !in menuSet

    /** Six bottom-bar tab destinations in display order. */
    val tabs: List<AppDestination> = listOf(
        AppDestination.Inbox,
        AppDestination.Today,
        AppDestination.Upcoming,
        AppDestination.Plans,
        AppDestination.Pomodoro,
        AppDestination.Calendar,
    )

    /** Menu destinations in display order. */
    val menuEntries: List<AppDestination> = listOf(
        AppDestination.Statistics,
        AppDestination.Notes,
        AppDestination.AiChat,
        AppDestination.Search,
        AppDestination.Archive,
        AppDestination.Settings,
    )

    private val tabSet: Set<AppDestination> = tabs.toSet()

    private val menuSet: Set<AppDestination> = menuEntries.toSet()
}
