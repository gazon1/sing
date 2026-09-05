package com.singularity.todo.feature.nav

/**
 * Drawer destinations for the **Desktop** chrome (`DesktopShell`).
 *
 * Android uses [AppDestination] directly. This enum only exists for the
 * desktop drawer's grouping labels — it has no icons, no routes, no
 * navigation logic of its own.
 *
 * If you add a new top-level destination to [AppDestination], also add
 * a corresponding entry here and wire the mapping in
 * [com.singularity.todo.shell.DesktopShell.tabDestinationFor].
 */
enum class NavDestination(val title: String) {
    Tasks("Tasks"),
    Notes("Notes"),
    Projects("Projects"),
    Pomodoro("Timer"),
    Statistics("Stats"),
    Chat("AI Chat"),
    Search("Search"),
    Archive("Archive"),
    Settings("Settings"),
}
