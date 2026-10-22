package com.singularity.todo.feature.nav

import androidx.navigation3.runtime.NavBackStack

/**
 * The nested-graph stacks that the platform hoists into the shell's composition.
 *
 * Desktop hoists the top-level graphs' stacks so they survive an entry leaving
 * `NavDisplay`'s visible set (ADR `2026-10-01-desktop-nav-followup`, Bug 3);
 * Android lets each graph own its stack, because its saved-state backing already
 * survives recreation.
 *
 * The entry contributors take the stacks as parameters, so the same contributor
 * serves both platforms: `null` means "the graph makes its own".
 *
 * @param projects the hoisted stack of the projects graph.
 * @param calendar the hoisted stack of the calendar graph.
 * @param notes the hoisted stack of the notes graph.
 * @param search the hoisted stack of the search graph.
 * @param settings the hoisted stack of the settings graph.
 * @param agenda the hoisted stacks of the agenda tabs, by their start route.
 */
class AppNavStacks(
    val projects: NavBackStack<ProjectsRoute>? = null,
    val calendar: NavBackStack<CalendarRoute>? = null,
    val notes: NavBackStack<NotesRoute>? = null,
    val search: NavBackStack<Search>? = null,
    val settings: NavBackStack<Settings>? = null,
    val agenda: Map<AgendaStartRoute, NavBackStack<AgendaStartRoute>> = emptyMap(),
)
