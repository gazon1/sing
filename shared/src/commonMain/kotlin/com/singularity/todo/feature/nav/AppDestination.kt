package com.singularity.todo.feature.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.graphics.vector.ImageVector
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
sealed interface AppDestination {
    /** User-visible label — used by BottomBar and MenuSheet. */
    val title: String

    /** Five persistent tabs (bottom bar / drawer). */
    @Serializable
    data object Inbox : AppDestination {
        override val title = "Inbox"
    }

    @Serializable
    data object Today : AppDestination {
        override val title = "Today"
    }

    @Serializable
    data object Plans : AppDestination {
        override val title = "Plans"
    }

    @Serializable
    data object Habits : AppDestination {
        override val title = "Habits"
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

    @Serializable
    data class TaskDetail(val taskId: String) : AppDestination {
        override val title = "Task"
    }

    @Serializable
    data class TaskEditor(
        val initialDueDate: String? = null,
        /** When non-null, the editor opens in edit mode for this task ID. */
        val taskId: String? = null,
    ) : AppDestination {
        override val title = "New Task"
    }

    @Serializable
    data class NoteView(val noteId: String) : AppDestination {
        override val title = "Note"
    }

    @Serializable
    data class NoteEditor(val noteId: String? = null) : AppDestination {
        override val title = "New Note"
    }

    @Serializable
    data class ProjectEditor(val projectId: String? = null) : AppDestination {
        override val title = "New Project"
    }

    @Serializable
    data class ProjectDetail(val projectId: String) : AppDestination {
        override val title = "Project"
    }

    @Serializable
    data class TasksByProject(val projectId: String) : AppDestination {
        override val title = "Project Tasks"
    }
}

/** UI metadata for [AppDestination]. Kept separate so the route stays pure-data. */
val AppDestination.icon: ImageVector
    get() = when (this) {
        AppDestination.Inbox -> Icons.Filled.Inbox
        AppDestination.Today -> Icons.Filled.Today
        AppDestination.Plans -> Icons.Filled.Check
        AppDestination.Habits -> Icons.Filled.Repeat
        AppDestination.Calendar -> Icons.Filled.CalendarMonth
        AppDestination.Notes -> Icons.Filled.Create
        AppDestination.AiChat -> Icons.Filled.AutoAwesome
        AppDestination.Search -> Icons.Filled.Search
        AppDestination.Archive -> Icons.Filled.Inbox
        AppDestination.Settings -> Icons.Filled.Settings
        AppDestination.AiUsage -> Icons.Filled.BarChart
        AppDestination.ProfileSwitcher -> Icons.Filled.Person
        is AppDestination.TaskDetail -> Icons.Filled.Check
        is AppDestination.TaskEditor -> Icons.Filled.Check
        is AppDestination.NoteView -> Icons.Filled.Create
        is AppDestination.NoteEditor -> Icons.Filled.Create
        is AppDestination.ProjectEditor -> Icons.Filled.Check
        is AppDestination.ProjectDetail -> Icons.Filled.Check
        is AppDestination.TasksByProject -> Icons.Filled.Folder
    }

/** Title for the special "Menu" bottom-bar item that opens the bottom sheet. */
const val MenuButtonTitle = "Menu"

/**
 * Pure helpers — no Compose runtime, no Android, no JVM.
 * Testable without any UI infrastructure.
 */
object DestinationKind {
    /** Five persistent tabs shown in the bottom bar (excludes the Menu button). */
    fun isTab(destination: AppDestination): Boolean =
        destination in tabSet

    /** Menu destinations opened from the bottom sheet overlay. */
    fun isMenuEntry(destination: AppDestination): Boolean =
        destination in menuSet

    /** Anything that is NOT a top-level tab — i.e. push-on sub-routes. */
    fun isSubRoute(destination: AppDestination): Boolean =
        destination !in tabSet && destination !in menuSet

    /** Five bottom-bar tab destinations in display order. */
    val tabs: List<AppDestination> = listOf(
        AppDestination.Inbox,
        AppDestination.Today,
        AppDestination.Plans,
        AppDestination.Habits,
        AppDestination.Calendar,
    )

    /** Menu destinations in display order. */
    val menuEntries: List<AppDestination> = listOf(
        AppDestination.Notes,
        AppDestination.AiChat,
        AppDestination.Search,
        AppDestination.Archive,
        AppDestination.Settings,
    )

    private val tabSet: Set<AppDestination> = tabs.toSet()

    private val menuSet: Set<AppDestination> = menuEntries.toSet()
}
