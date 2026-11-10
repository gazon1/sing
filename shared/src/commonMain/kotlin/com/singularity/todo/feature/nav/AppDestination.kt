package com.singularity.todo.feature.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.platform.systemToday
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Top-level destinations shown in the Android bottom navigation bar.
 *
 * Bottom-bar tabs (see [DestinationKind.tabs]) + a separate "Menu" button that opens a
 * `MenuBottomSheet` overlay (NOT a navigation destination — see `MenuBottomSheet`).
 *
 * Bottom bar: [AgendaGraph] with [AgendaStartRoute.Inbox]/[Today]/[Upcoming],
 * [Calendar] — see [DestinationKind.tabs]. Four tabs and a `Menu` button; the fifth tab
 * slot is deliberately empty, per the decision
 * `2026-11-04-the-fifth-tab-slot-stays-empty.md`.
 *
 * [Calendar] is now a tab; [Plans] and [Pomodoro] live in the app menu — see
 * [DestinationKind.menuEntries].
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
         *
         * [projectId] and [tagIds] are the scope of the list the screen was opened
         * from, when it had one: a create action on a project's or a tag's task
         * agenda seeds the new task with that scope, so the task it creates is a
         * member of the list that asked for it. Both are null/empty elsewhere —
         * the tab actions create an unscoped task.
         */
        @Serializable
        data class Create(
            val draftKey: String? = null,
            val projectId: ProjectId? = null,
            val tagIds: Set<TagId> = emptySet(),
        ) : TasksStartRoute

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
        val start: CalendarStartRoute =
            CalendarStartRoute.Month(
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
        override val title: String =
            when (start) {
                AgendaStartRoute.Inbox -> "Inbox"

                // The Today destination's name lives with its definition: the tab
                // label and the header title are the same string, so they cannot
                // drift apart. The other branches are their own labels already.
                AgendaStartRoute.Today -> AgendaPresets.Today.title

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

/**
 * The glyph a destination is drawn with.
 *
 * One metaphor per destination, and the metaphor is the destination's name: a box
 * for the archive, a to-do list for the tasks, a notebook for the notes, a chart
 * for the statistics and a different chart for the AI usage.
 *
 * The rules, each held by a test rather than by this comment:
 *
 * 1. no two entries of one presented list — the menu, the bottom bar — share a
 *    glyph (`MenuIconsTest` in the shell);
 * 2. the agenda's start routes do not share one glyph among themselves, and none
 *    of them is the tasks' glyph (`DestinationIconsTest`);
 * 3. no destination anywhere is drawn with the "done" tick, which says a task is
 *    finished rather than naming a screen (`DestinationIconsTest`).
 *
 * Every destination is named in the `when` below, and it has no `else`: the
 * hierarchy is sealed, so a destination that joins it does not compile until it
 * is named here. A catch-all branch is how the agenda's drill-downs came to share
 * one glyph with Tasks: the branch compiled, the list of destinations looked
 * plausible, and nothing said which ones fell into it.
 *
 * ## Why no start route appears in this `when`
 *
 * A start route is not a destination. `AgendaStartRoute`, `TasksStartRoute`,
 * `ProjectsStartRoute`, `NotesStartRoute` and `CalendarStartRoute` all extend
 * [AppNavKey]; the *graph* they start ([AppDestination.AgendaGraph] and its
 * siblings) is the destination, and it is the graph that is drawn. An
 * `is AgendaStartRoute ->` branch in a `when` over [AppDestination] could
 * therefore never match — it is unreachable, it adds nothing to exhaustiveness,
 * and its presence reads as though start routes were drawn on their own.
 *
 * Only [AppDestination.AgendaGraph]'s glyph varies by its start route, because
 * three of those routes are bottom-bar tabs and the rest are drill-downs; that
 * mapping lives in [agendaStartIcon], typed on [AgendaStartRoute] where a branch
 * per route is exhaustive by the compiler.
 */
val AppDestination.icon: ImageVector
    get() =
        when (this) {
            // ─── Tabs and menu entries ──────────────────────────────────────
            AppDestination.Plans -> Icons.AutoMirrored.Filled.Assignment

            AppDestination.Pomodoro -> Icons.Filled.Repeat

            AppDestination.Statistics -> Icons.Filled.Insights

            AppDestination.Calendar -> Icons.Filled.CalendarMonth

            AppDestination.Notes -> Icons.AutoMirrored.Filled.Note

            AppDestination.AiChat -> Icons.Filled.AutoAwesome

            AppDestination.Search -> Icons.Filled.Search

            AppDestination.ExternalAgent -> Icons.Filled.Extension

            AppDestination.Proposals -> Icons.AutoMirrored.Filled.FactCheck

            AppDestination.Archive -> Icons.Filled.Archive

            AppDestination.Settings -> Icons.Filled.Settings

            AppDestination.AiUsage -> Icons.Filled.BarChart

            AppDestination.ProfileSwitcher -> Icons.Filled.Person

            AppDestination.AttachAccount -> Icons.Filled.PersonAdd

            AppDestination.ChangePassword -> Icons.Filled.Lock

            is AppDestination.MagicLinkConfirm -> Icons.Filled.VerifiedUser

            // ─── Nested graphs: one glyph for the graph ─────────────────────
            // Tasks are a list, not a tick: a tick says one task is finished, a
            // list says where the tasks live. Nothing in the destination map
            // draws the tick at all.
            is AppDestination.TasksGraph -> Icons.AutoMirrored.Filled.ListAlt

            is AppDestination.ProjectsGraph -> Icons.Filled.Folder

            is AppDestination.NotesGraph -> Icons.AutoMirrored.Filled.Note

            is AppDestination.CalendarGraph -> Icons.Filled.CalendarMonth

            // The one graph whose glyph is the start route's: the agenda's
            // start is what tells Inbox from Today from a project's drill-down.
            is AppDestination.AgendaGraph -> agendaStartIcon(start)

            // ─── Record screens ─────────────────────────────────────────────
            is AppDestination.AttachmentViewer -> Icons.Filled.AttachFile

            is AppDestination.TasksByProject -> Icons.Filled.FolderShared

            is AppDestination.ProjectEditor -> Icons.Filled.CreateNewFolder

            is AppDestination.ProjectDetail -> Icons.Filled.FolderOpen
        }

/**
 * The glyph of an agenda start route.
 *
 * The only place where a route that is not itself an [AppDestination] decides a
 * glyph, and it is typed on the route rather than on the destination: the
 * `when` is exhaustive over [AgendaStartRoute] by the compiler, so a start route
 * added later has to be given a glyph instead of silently landing in a
 * catch-all — which is how every drill-down came to draw the generic task tick.
 *
 * The project's agenda keeps the folder the projects list carries: it *is* a
 * project's tasks, and the two are never drawn in one list — the projects list
 * is a sub-route, not a tab and not a menu entry. The project's own screens
 * split the folder family instead — open folder for the project, new folder for
 * its editor, shared folder for its tasks — rather than all repeating one glyph.
 * `DestinationIconsTest` holds that allowance to those conditions.
 */
private fun agendaStartIcon(start: AgendaStartRoute): ImageVector =
    when (start) {
        AgendaStartRoute.Inbox -> Icons.Filled.MoveToInbox

        AgendaStartRoute.Today -> Icons.Filled.Today

        AgendaStartRoute.Upcoming -> Icons.Filled.Event

        is AgendaStartRoute.Project -> Icons.Filled.Folder

        is AgendaStartRoute.Tag -> Icons.AutoMirrored.Filled.Label

        is AgendaStartRoute.Tags -> Icons.Filled.LocalOffer

        // The saved views are a list of saved views — the same metaphor the
        // agenda top bar's "Saved views" button carries.
        AgendaStartRoute.SavedAgendaList -> Icons.Filled.ViewList

        is AgendaStartRoute.SavedAgendaResults -> Icons.Filled.QueryStats

        is AgendaStartRoute.SavedAgendaEdit -> Icons.Filled.Edit

        AgendaStartRoute.SavedAgendaCreate -> Icons.Filled.Add

        AgendaStartRoute.Blocked -> Icons.Filled.Block

        AgendaStartRoute.Stagnant -> Icons.Filled.HourglassEmpty

        AgendaStartRoute.Overdue -> Icons.Filled.WarningAmber
    }

/** Title for the special "Menu" bottom-bar item that opens the bottom sheet. */
const val MENU_BUTTON_TITLE = "Menu"

/**
 * Pure helpers — no Compose runtime, no Android, no JVM.
 * Testable without any UI infrastructure.
 */
object DestinationKind {
    /**
     * Four persistent tabs shown in the bottom bar (excludes the Menu button), so the
     * bar holds five items in all.
     */
    fun isTab(destination: AppDestination): Boolean = destination in tabSet

    /** Menu destinations opened from the bottom sheet overlay. */
    fun isMenuEntry(destination: AppDestination): Boolean = destination in menuSet

    /** Anything that is NOT a top-level tab — i.e. push-on sub-routes. */
    fun isSubRoute(destination: AppDestination): Boolean = destination !in tabSet && destination !in menuSet

    /**
     * Four bottom-bar tab destinations in display order. Four, not five: the empty slot
     * is a decision, not an omission — see
     * `2026-11-04-the-fifth-tab-slot-stays-empty.md`.
     */
    val tabs: List<AppDestination> =
        listOf(
            AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
            AppDestination.AgendaGraph(AgendaStartRoute.Today),
            AppDestination.AgendaGraph(AgendaStartRoute.Upcoming),
            AppDestination.Calendar,
        )

    /** Menu destinations in display order. */
    val menuEntries: List<AppDestination> =
        listOf(
            AppDestination.Statistics,
            AppDestination.Notes,
            AppDestination.AiChat,
            AppDestination.Proposals,
            AppDestination.Search,
            AppDestination.Archive,
            AppDestination.Plans,
            AppDestination.Pomodoro,
            AppDestination.ProfileSwitcher,
            AppDestination.Settings,
            AppDestination.AiUsage,
        )

    private val tabSet: Set<AppDestination> = tabs.toSet()

    private val menuSet: Set<AppDestination> = menuEntries.toSet()
}
