package com.singularity.todo.feature.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavGroup {
    Work,
    Knowledge,
    Insights,
}

enum class NavDestination(
    val title: String,
    val icon: ImageVector,
    val group: NavGroup,
) {
    Tasks("Tasks", Icons.Filled.Check, NavGroup.Work),
    Notes("Notes", Icons.Filled.Create, NavGroup.Knowledge),
    Projects("Projects", Icons.Filled.Home, NavGroup.Work),
    Pomodoro("Timer", Icons.Filled.Timer, NavGroup.Work),
    Statistics("Stats", Icons.Filled.BarChart, NavGroup.Insights),
    Chat("AI Chat", Icons.Filled.AutoAwesome, NavGroup.Knowledge),
    Search("Search", Icons.Filled.Search, NavGroup.Knowledge),
    Settings("Settings", Icons.Filled.Settings, NavGroup.Insights);

    companion object {
        /** One-pass groupBy — computed once at startup. */
        val grouped: Map<NavGroup, List<NavDestination>> = entries.groupBy { it.group }

        fun at(index: Int): NavDestination = entries[index]
    }
}
