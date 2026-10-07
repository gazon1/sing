package com.singularity.todo.feature.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.singularity.todo.core.platform.systemToday
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Top-level destinations shown in the Android bottom navigation bar.
 *
 * Six tabs (see [DestinationKind.tabs]) + a separate "Menu" button that opens a
 * `MenuBottomSheet` overlay (NOT a navigation destination — see `MenuBottomSheet`).
 *
 * Bottom bar (six tabs): [AgendaGraph] with [AgendaStartRoute.Inbox]/[Today]/[Upcoming],
 * [Plans], [Pomodoro], [Calendar] — see [DestinationKind.tabs].
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
@Serializable
sealed interface AppDestination : AppNavKey {
    /** User-visible label — used by BottomBar and MenuSheet. */
    val title: String

    @Serializable
    data object Plans : AppDestination {
        override val title = "Plans"
    }

    @Serializable
    data object Pomodoro : AppDestination {
        override val title = "Pomodoro"
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
     */
    @Serializable
    sealed interface TasksStartRoute : AppNavKey {
        @Serializable
        data class Create(val sectionPrefillKey: String? = null) : TasksStartRoute

        @Serializable
        data class Detail(val taskId: TaskId) : TasksStartRoute
    }

    /**
     * Nested tasks graph. Contains its own NavBackStack[TasksRoute].
     * Used for: FAB "Add task" from any screen, deep-links, and direct navigation.
     */
    @Serializable
    data class TasksGraph(val start: TasksStartRoute, val initialDueDate: LocalDate? = null) : AppDestination {
        override val title = "Tasks"
    }

    @Serializable
    data class TasksByProject(val projectId: ProjectId) : AppDestination {
        override val title = "Project Tasks"
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
     * The in-app/external viewer for one attachment.
     *
     * A sub-route rather than a tab or a menu entry ([DestinationKind.isSubRoute]), so
     * it pushes onto the current stack and Back returns to the task it came from.
     *
     * It carries only the attachment's identity. What is displayed is decided by
     * `AttachmentViewerRoute.routeFor` from the attachment's own type — storing a
     * "show an image" instruction here would put a display decision in navigation
     * state, where it could disagree with the attachment after a sync changed its type.
     *
     * Declared on [AppDestination] rather than as its own sealed route interface
     * because it is pushed onto the app-level stack, whose entry provider is typed to
     * `AppDestination`. A separate route type would have to be added to that stack's
     * type parameter, and a route nothing renders is precisely the defect this work set
     * out to remove. It is still a leaf of the single sealed [AppNavKey] root, per
     * ADR `2026-09-29-single-sealed-navkey-root`.
     */
    @Serializable
    data class AttachmentViewer(val attachmentId: AttachmentId) : AppDestination {
        override val title = "Attachment"
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
    sealed interface ProjectsStartRoute : AppNavKey {
        @Serializable
        data object List : ProjectsStartRoute

        @Serializable
        data class Editor(val projectId: ProjectId? = null) : ProjectsStartRoute
    }

    /**
     * Start route for the notes nested graph. Used as `start` param in [NotesGraph].
     */
    @Serializable
    sealed interface NotesStartRoute : AppNavKey {
        @Serializable
        data object List : NotesStartRoute

        @Serializable
        data class Preview(val noteId: NoteId) : NotesStartRoute

        /**
         * Opens the note editor pre-attached to [taskId].
         * A separate route (not [NotesRoute.Editor]) avoids the invalid state of
         * passing both `noteId` and `taskId` to one editor instance.
         */
        @Serializable
        data class EditorForTask(val taskId: TaskId) : NotesStartRoute
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
    sealed interface CalendarStartRoute : AppNavKey {
        @Serializable
        data class Month(val anchor: String) : CalendarStartRoute
    }

    /**
     * Nested Calendar graph. Contains its own NavBackStack[CalendarRoute].
     * Used for deep-links and direct navigation.
     *
     * `start` defaults to *this* month, which is the one place in navigation where
     * "today" really is the answer rather than a dependency: the route names a
     * month, and opening a calendar without naming one means the current one. It is a
     * default rather than a required argument so the key round-trips through
     * serialisation in `NavKeyRegistrationTest` and friends, and it is spelled
     * `systemToday` so that a reader can see it is host-dependent by name.
     */
    @Serializable
    data class CalendarGraph(
        val start: CalendarStartRoute = CalendarStartRoute.Month(
            systemToday().toString(),
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
        // Three of these are bottom-bar tabs and the shell renders `title`
        // directly as the tab label, so it has to vary by start route.
        override val title: String = when (start) {
            AgendaStartRoute.Inbox -> "Inbox"
            AgendaStartRoute.Today -> "Today"
            AgendaStartRoute.Upcoming -> "Upcoming"
            is AgendaStartRoute.Project -> "Project"
            is AgendaStartRoute.Tag -> "Tag"
            AgendaStartRoute.SavedAgendaList -> "Saved views"
            is AgendaStartRoute.SavedAgendaResults -> "Saved view"
            is AgendaStartRoute.SavedAgendaEdit -> "Edit view"
            AgendaStartRoute.SavedAgendaCreate -> "New view"
        }
    }
}

/** UI metadata for [AppDestination]. Kept separate so the route stays pure-data. */
val AppDestination.icon: ImageVector
    get() = when (this) {
        AppDestination.Plans -> Icons.Filled.Check
        AppDestination.Pomodoro -> Icons.Filled.Repeat
        AppDestination.Statistics -> Icons.Filled.BarChart
        AppDestination.Calendar -> Icons.Filled.CalendarMonth
        AppDestination.Notes -> Icons.Filled.Create
        AppDestination.AiChat -> Icons.Filled.AutoAwesome
        is AppDestination.AttachmentViewer -> Icons.Filled.AttachFile
        AppDestination.Search -> Icons.Filled.Search
        AppDestination.Archive -> Icons.Filled.Check
        AppDestination.Settings -> Icons.Filled.Settings
        AppDestination.AiUsage -> Icons.Filled.BarChart
        AppDestination.ProfileSwitcher -> Icons.Filled.Person
        is AppDestination.TasksGraph -> Icons.Filled.Check
        is AppDestination.TasksByProject -> Icons.Filled.Folder
        is AppDestination.ProjectEditor -> Icons.Filled.Check
        is AppDestination.ProjectDetail -> Icons.Filled.Check
        is AppDestination.ProjectsGraph -> Icons.Filled.Check
        is AppDestination.ProjectsStartRoute -> Icons.Filled.Check
        is AppDestination.NotesGraph -> Icons.Filled.Create
        is AppDestination.NotesStartRoute -> Icons.Filled.Create
        is AppDestination.CalendarGraph -> Icons.Filled.CalendarMonth
        is AppDestination.CalendarStartRoute -> Icons.Filled.CalendarMonth
        is AppDestination.AgendaGraph -> Icons.Filled.Check
        is AgendaStartRoute -> Icons.Filled.Check
        is AppDestination.TasksStartRoute -> Icons.Filled.Check
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
        AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
        AppDestination.AgendaGraph(AgendaStartRoute.Today),
        AppDestination.AgendaGraph(AgendaStartRoute.Upcoming),
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
        AppDestination.ProfileSwitcher,
        AppDestination.Settings,
    )

    private val tabSet: Set<AppDestination> = tabs.toSet()

    private val menuSet: Set<AppDestination> = menuEntries.toSet()
}
