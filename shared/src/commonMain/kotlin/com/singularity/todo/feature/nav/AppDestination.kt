package com.singularity.todo.feature.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.graphics.vector.ImageVector
import com.singularity.todo.core.platform.systemToday
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Top-level destinations shown in the Android bottom navigation bar.
 *
 * Five tabs (see [DestinationKind.tabs]) + a separate "Menu" button that opens a
 * `MenuBottomSheet` overlay (NOT a navigation destination — see `MenuBottomSheet`).
 *
 * Bottom bar (five tabs): [AgendaGraph] with [AgendaStartRoute.Inbox]/[Today]/[Upcoming],
 * [Plans], [Pomodoro] — see [DestinationKind.tabs].
 *
 * The [Calendar] is not a tab any more: a bottom bar has no room for it, and it
 * lives in the app menu on both platforms — see [DestinationKind.menuEntries].
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

    /**
     * The external agent — a third-party agent hosted inside the application.
     *
     * A sub-route rather than a menu entry: the agent is a process, and processes are
     * a desktop thing. A menu entry would put it on the mobile menu sheet, where
     * there is nothing behind it.
     */
    @Serializable
    data object ExternalAgent : AppDestination {
        override val title = "External Agent"
    }

    /**
     * The proposals waiting for a decision.
     *
     * A change the assistant proposed and the user has not seen yet. The screen lists
     * them and lets the user apply or turn them down — one decision per change, and
     * nothing is applied until it is made.
     */
    @Serializable
    data object Proposals : AppDestination {
        override val title = "Proposals"
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

    /**
     * Screen for attaching an email/password account to the current anonymous session.
     *
     * Only reachable when [com.singularity.todo.core.auth.Session.Anonymous]; the UI
     * is driven by [com.singularity.todo.feature.auth.AuthIntent.AttachAccount].
     *
     * Navigated to from [AccountSettingsScreen][com.singularity.todo.feature.profile.presentation.AccountSettingsScreen]
     * via [SettingsNavigator.openAttachAccount][com.singularity.todo.feature.settings.presentation.nav.SettingsNavigator.openAttachAccount].
     */
    @Serializable
    data object AttachAccount : AppDestination {
        override val title = "Attach Account"
    }

    /**
     * Screen for changing the password of the currently signed-in account.
     *
     * Only reachable when [com.singularity.todo.core.auth.Session.SignedIn].
     * The UI is driven by [com.singularity.todo.feature.profile.presentation.ChangePasswordIntent].
     *
     * Navigated to from [AccountSettingsScreen][com.singularity.todo.feature.profile.presentation.AccountSettingsScreen]
     * via [SettingsNavigator.openChangePassword][com.singularity.todo.feature.settings.presentation.nav.SettingsNavigator.openChangePassword].
     */
    @Serializable
    data object ChangePassword : AppDestination {
        override val title = "Change Password"
    }

    /**
     * Screen for entering the token from a magic link email.
     *
     * Used to recover from an unconfirmed sign-up: after [AuthRepository.sendMagicLink]
     * sends the link, the user opens this screen to complete sign-in.
     *
     * Navigated to from the email link via deep-link.
     *
     * @param email Pre-fills the email field. Used by deep-links and by the resend flow.
     * @param token When non-blank, auto-submits verification immediately on screen entry.
     *   Set by the deep-link handler in [App] when a magic link is opened.
     */
    @Serializable
    data class MagicLinkConfirm(val email: String = "", val token: String = "") : AppDestination {
        override val title = "Verify Email"
    }

    // ─── Sub-routes (push on top of a top-level destination) ────────────────

    /**
     * The destinations that stand for a record — a task, a project, an attachment —
     * or for a nested graph.
     *
     * The group is a *type*, not a list: a `when` over it is exhaustive by the
     * compiler, so a destination that joins the group does not compile until it
     * has a content key. As a list, the membership was written twice — once
     * here and once in the `when` that computes the keys — and the two could
     * drift: a destination that joined the group in one place and not the other
     * reached the runtime and failed with "no content key for ...".
     */
    sealed interface RecordDestination : AppDestination

    /**
     * Start route for the tasks nested graph. Used as `start` param in [TasksGraph].
     */
    @Serializable
    sealed interface TasksStartRoute : AppNavKey {
        /**
         * Opens the create editor with the draft named by [draftKey].
         *
         * The key is complete: whoever writes the draft builds the key with
         * [com.singularity.todo.feature.tasks.domain.model.TaskDraftKeys].
         */
        @Serializable
        data class Create(val draftKey: String? = null) : TasksStartRoute

        /**
         * A task the person opened.
         *
         * [sourceViewId] is the saved agenda view the task was opened from, when there
         * was one. It travels with the route because the agenda is the only place that
         * knows it — by the time the task's detail screen is up, the list it came from
         * is gone, and a reminder set there needs somewhere to point back to.
         */
        @Serializable
        data class Detail(val taskId: TaskId, val sourceViewId: SavedAgendaViewId? = null) : TasksStartRoute
    }

    /**
     * Nested tasks graph. Contains its own NavBackStack[TasksRoute].
     * Used for: FAB "Add task" from any screen, deep-links, and direct navigation.
     */
    @Serializable
    data class TasksGraph(val start: TasksStartRoute, val initialDueDate: LocalDate? = null) : RecordDestination {
        override val title = "Tasks"
    }

    @Serializable
    data class TasksByProject(val projectId: ProjectId) : RecordDestination {
        override val title = "Project Tasks"
    }

    @Serializable
    data class ProjectEditor(val projectId: ProjectId? = null) : RecordDestination {
        override val title = "New Project"
    }

    @Serializable
    data class ProjectDetail(val projectId: ProjectId) : RecordDestination {
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
    data class AttachmentViewer(val attachmentId: AttachmentId) : RecordDestination {
        override val title = "Attachment"
    }

    /**
     * Nested projects graph. Contains its own NavBackStack[ProjectsRoute].
     * Used for deep-links and future navigation flexibility.
     */
    @Serializable
    data class ProjectsGraph(val start: ProjectsStartRoute = ProjectsStartRoute.List) : RecordDestination {
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
    data class NotesGraph(val start: NotesStartRoute = NotesStartRoute.List) : RecordDestination {
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
    ) : RecordDestination {
        override val title = "Calendar"
    }

    /**
     * Nested Agenda graph. Contains its own NavBackStack[AgendaStartRoute].
     * Used for: Inbox, Today, Upcoming tabs, and "See all" from project detail.
     */
    @Serializable
    data class AgendaGraph(val start: AgendaStartRoute = AgendaStartRoute.Inbox) : RecordDestination {
        // Three of these are bottom-bar tabs and the shell renders `title`
        // directly as the tab label, so it has to vary by start route.
        override val title: String = when (start) {
            AgendaStartRoute.Inbox -> "Inbox"
            AgendaStartRoute.Today -> "Today"
            AgendaStartRoute.Upcoming -> "Upcoming"
            is AgendaStartRoute.Project -> "Project"
            is AgendaStartRoute.Tag -> "Tag"
            is AgendaStartRoute.Tags -> "Tags"
            AgendaStartRoute.SavedAgendaList -> "Saved views"
            is AgendaStartRoute.SavedAgendaResults -> "Saved view"
            is AgendaStartRoute.SavedAgendaEdit -> "Edit view"
            AgendaStartRoute.SavedAgendaCreate -> "New view"
            AgendaStartRoute.Blocked -> "Blocked"
            AgendaStartRoute.Stagnant -> "Stagnant"
            AgendaStartRoute.Overdue -> "Overdue"
        }
    }
}

/** UI metadata for [AppDestination]. Kept separate so the route stays pure-data. */
val AppDestination.icon: ImageVector
    get() = when {
        this is AppDestination.AgendaGraph -> when (this.start) {
            AgendaStartRoute.Inbox -> Icons.Filled.MoveToInbox
            AgendaStartRoute.Today -> Icons.Filled.Today
            AgendaStartRoute.Upcoming -> Icons.Filled.Event
            else -> Icons.Filled.Check
        }

        else -> when (this) {
            AppDestination.Plans -> Icons.AutoMirrored.Filled.Assignment
            AppDestination.Pomodoro -> Icons.Filled.Repeat
            AppDestination.Statistics -> Icons.Filled.BarChart
            AppDestination.Calendar -> Icons.Filled.CalendarMonth
            AppDestination.Notes -> Icons.Filled.Create
            AppDestination.AiChat -> Icons.Filled.AutoAwesome
            is AppDestination.AttachmentViewer -> Icons.Filled.AttachFile
            AppDestination.Search -> Icons.Filled.Search
            AppDestination.ExternalAgent -> Icons.Filled.AutoAwesome
            AppDestination.Proposals -> Icons.AutoMirrored.Filled.FactCheck
            AppDestination.Archive -> Icons.Filled.Check
            AppDestination.Settings -> Icons.Filled.Settings
            AppDestination.AiUsage -> Icons.Filled.BarChart
            AppDestination.ProfileSwitcher -> Icons.Filled.Person
            AppDestination.AttachAccount -> Icons.Filled.Person
            AppDestination.ChangePassword -> Icons.Filled.Person
            is AppDestination.MagicLinkConfirm -> Icons.Filled.Person
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
            is AgendaStartRoute -> Icons.Filled.Check
            is AppDestination.TasksStartRoute -> Icons.Filled.Check
        }
    }

/** Title for the special "Menu" bottom-bar item that opens the bottom sheet. */
const val MenuButtonTitle = "Menu"

/**
 * Pure helpers — no Compose runtime, no Android, no JVM.
 * Testable without any UI infrastructure.
 */
object DestinationKind {
    /** Five persistent tabs shown in the bottom bar (excludes the Menu button). */
    fun isTab(destination: AppDestination): Boolean = destination in tabSet

    /** Menu destinations opened from the bottom sheet overlay. */
    fun isMenuEntry(destination: AppDestination): Boolean = destination in menuSet

    /** Anything that is NOT a top-level tab — i.e. push-on sub-routes. */
    fun isSubRoute(destination: AppDestination): Boolean = destination !in tabSet && destination !in menuSet

    /** Five bottom-bar tab destinations in display order. */
    val tabs: List<AppDestination> = listOf(
        AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
        AppDestination.AgendaGraph(AgendaStartRoute.Today),
        AppDestination.AgendaGraph(AgendaStartRoute.Upcoming),
        AppDestination.Plans,
        AppDestination.Pomodoro,
    )

    /** Menu destinations in display order. */
    val menuEntries: List<AppDestination> = listOf(
        AppDestination.Statistics,
        AppDestination.Notes,
        AppDestination.AiChat,
        AppDestination.Proposals,
        AppDestination.Search,
        AppDestination.Archive,
        // The Calendar left the bottom bar for the menu: a five-tab bar has no
        // room for a sixth, and the menu is where the rest of the app's
        // destinations live.
        AppDestination.Calendar,
        AppDestination.ProfileSwitcher,
        AppDestination.AttachAccount,
        AppDestination.Settings,
    )

    private val tabSet: Set<AppDestination> = tabs.toSet()

    private val menuSet: Set<AppDestination> = menuEntries.toSet()
}
