package com.singularity.todo.feature.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Drawer destinations for the **Desktop** chrome (`DesktopShell`).
 *
 * Android uses [AppDestination] directly. This enum only exists for the
 * desktop drawer's left-rail labels and icons — it has no routes, no
 * navigation logic of its own.
 *
 * If you add a new top-level destination to [AppDestination], also add
 * a corresponding entry here and wire the mapping in
 * [com.singularity.todo.shell.DesktopShell.tabDestinationFor].
 */
enum class NavDestination(val title: String, val icon: ImageVector) {
    Tasks("Tasks", Icons.Filled.CheckCircleOutline),
    Notes("Notes", Icons.AutoMirrored.Filled.StickyNote2),
    Projects("Projects", Icons.Filled.Folder),
    Pomodoro("Timer", Icons.Filled.Timer),
    Statistics("Stats", Icons.Filled.BarChart),
    Chat("AI Chat", Icons.AutoMirrored.Filled.Chat),
    Search("Search", Icons.AutoMirrored.Filled.ManageSearch),
    Archive("Archive", Icons.Filled.Inventory2),
    Settings("Settings", Icons.Filled.AdminPanelSettings),
}
