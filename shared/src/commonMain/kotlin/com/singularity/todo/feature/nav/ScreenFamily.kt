package com.singularity.todo.feature.nav

/**
 * The feature family a navigation key belongs to — "whose screen is this".
 *
 * The classification is the structural knowledge [NavigationPolicy] needs to decide
 * same-feature (`Push`) vs cross-feature (`ExitAndOpen`) opens (REQ-NAV-004). It is a
 * plain exhaustive function over the sealed [AppNavKey] hierarchy: adding a new key
 * without classifying it is a **compile error**, which is the property a mutable
 * `KClass → family` registry cannot provide.
 *
 * [DestinationKind] remains the source of tabs/menu entries — family is about feature
 * ownership, not shell chrome.
 */
sealed interface ScreenFamily {
    data object Agenda : ScreenFamily
    data object Tasks : ScreenFamily
    data object Projects : ScreenFamily
    data object Notes : ScreenFamily
    data object Calendar : ScreenFamily
    data object Pomodoro : ScreenFamily
    data object Statistics : ScreenFamily
    data object Search : ScreenFamily
    data object Settings : ScreenFamily
    data object AiChat : ScreenFamily
    data object Archive : ScreenFamily
}

/**
 * Classifies [key] into its [ScreenFamily].
 *
 * Exhaustive `when` without `else`: every leaf of the sealed [AppNavKey] root —
 * app destinations, nested-graph wrappers, start routes, inner feature routes and the
 * lone [Settings]/[Search] objects — must be named here.
 *
 * Deprecated legacy members that used to live here were removed in B4, so every
 * branch below names a route that still exists — adding a new one is a compile
 * error until it is classified.
 *
 * The [AppDestination] branch lives in [familyOfDestination] so each function stays
 * under the CyclomaticComplexMethod limit (16 destinations + 13 route kinds in one
 * `when` scores 24 > 20).
 */
fun familyOf(key: AppNavKey): ScreenFamily = when (key) {
    is AppDestination -> familyOfDestination(key)

    // Nested-but-AppNavKey start routes: declared inside AppDestination's body yet
    // extending AppNavKey (not AppDestination), so `is AppDestination` does not cover them.
    is AppDestination.TasksStartRoute -> ScreenFamily.Tasks

    is AppDestination.ProjectsStartRoute -> ScreenFamily.Projects

    is AppDestination.NotesStartRoute -> ScreenFamily.Notes

    is AppDestination.CalendarStartRoute -> ScreenFamily.Calendar

    is AgendaStartRoute -> ScreenFamily.Agenda

    is TasksRoute -> ScreenFamily.Tasks

    is ProjectsRoute -> ScreenFamily.Projects

    is NotesRoute -> ScreenFamily.Notes

    is CalendarRoute -> ScreenFamily.Calendar

    Settings -> ScreenFamily.Settings

    Search -> ScreenFamily.Search
}

/** Classifies an [AppDestination] — see [familyOf]. Split out for method complexity. */
private fun familyOfDestination(key: AppDestination): ScreenFamily = when (key) {
    AppDestination.Plans,
    is AppDestination.ProjectEditor,
    is AppDestination.ProjectDetail,
    is AppDestination.ProjectsGraph,
    -> ScreenFamily.Projects

    is AppDestination.TasksGraph,
    is AppDestination.TasksByProject,
    // The attachment viewer is reached from the task detail screen, so it belongs to
    // Tasks for navigation purposes: it is not a family of its own, because the viewer
    // holds no list state and nothing in the shell renders chrome per family. A new
    // family would add a case to every consumer of this function and buy nothing.
    is AppDestination.AttachmentViewer,
    -> ScreenFamily.Tasks

    AppDestination.Pomodoro -> ScreenFamily.Pomodoro

    AppDestination.Statistics -> ScreenFamily.Statistics

    AppDestination.Calendar,
    is AppDestination.CalendarGraph,
    -> ScreenFamily.Calendar

    AppDestination.Notes,
    is AppDestination.NotesGraph,
    -> ScreenFamily.Notes

    AppDestination.AiChat -> ScreenFamily.AiChat

    AppDestination.Search -> ScreenFamily.Search

    AppDestination.Archive -> ScreenFamily.Archive

    AppDestination.Settings,
    AppDestination.AiUsage,
    AppDestination.ProfileSwitcher,
    -> ScreenFamily.Settings

    is AppDestination.AgendaGraph -> ScreenFamily.Agenda
}
