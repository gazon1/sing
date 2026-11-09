package com.singularity.todo.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One of the settings sections the screen offers.
 */
internal enum class SettingsTab(val label: String) {
    Interface("Interface"),
    Agenda("Agenda"),
    Notifications("Notifications"),
    AIProvider("AI Provider"),
    WorkSchedule("Work Schedule"),
    Calendar("Calendar"),
    Sync("Sync"),
    Tags("Tags"),
    TagGroups("Tag Groups"),
    Tasks("Tasks"),
    Files("Files"),
    Backup("Backup"),
    Account("Account"),
}

internal val SettingsTab.icon: ImageVector
    get() =
        when (this) {
            SettingsTab.Interface -> Icons.Filled.Palette
            SettingsTab.Agenda -> Icons.Filled.Schedule
            SettingsTab.Notifications -> Icons.Filled.Notifications
            SettingsTab.AIProvider -> Icons.Filled.SmartToy
            SettingsTab.WorkSchedule -> Icons.Filled.DateRange
            SettingsTab.Calendar -> Icons.Filled.CalendarMonth
            SettingsTab.Sync -> Icons.Filled.CloudSync
            SettingsTab.Tags -> Icons.AutoMirrored.Filled.Label
            SettingsTab.TagGroups -> Icons.Filled.Category
            SettingsTab.Tasks -> Icons.Filled.CheckCircle
            SettingsTab.Files -> Icons.Filled.Folder
            SettingsTab.Backup -> Icons.Filled.CloudUpload
            SettingsTab.Account -> Icons.Filled.AccountCircle
        }

/**
 * A named group of settings tabs — the unit the wide nav rail shows as a
 * heading and the narrow-screen menu shows as a header.
 */
internal data class SettingsGroup(val title: String, val tabs: List<SettingsTab>)

/**
 * The settings groups, in the order both layouts show them.
 *
 * The wide nav rail and the narrow-screen menu read this one list. They used
 * to carry their own: the rail grouped the thirteen tabs under five headings,
 * the narrow menu listed the same thirteen tabs flat, in an order that matched
 * neither the rail's grouping nor the tab order. The phone was therefore not a
 * narrower version of the tablet — it was a different screen, and neither list
 * said which sections existed.
 */
internal val SETTINGS_GROUPS: List<SettingsGroup> =
    listOf(
        SettingsGroup("Appearance", listOf(SettingsTab.Interface)),
        SettingsGroup(
            "Productivity",
            listOf(SettingsTab.Agenda, SettingsTab.WorkSchedule, SettingsTab.Tasks),
        ),
        SettingsGroup("Notifications", listOf(SettingsTab.Notifications)),
        SettingsGroup(
            "Data",
            listOf(
                SettingsTab.Files,
                SettingsTab.Backup,
                SettingsTab.Sync,
                SettingsTab.Calendar,
                SettingsTab.Tags,
                SettingsTab.TagGroups,
            ),
        ),
        SettingsGroup("AI & Account", listOf(SettingsTab.AIProvider, SettingsTab.Account)),
    )

/**
 * Tab → group, built once at load.
 *
 * The previous form scanned the group list on every access — and
 * [SettingsTab.groupedLabel], which calls this, is read on every recomposition
 * of the narrow trigger — and it threw [NoSuchElementException] for a tab that
 * no group listed, turning a forgotten group entry into a crash on the settings
 * screen. This map is O(1); `SettingsNavigationTest` proves it covers every
 * [SettingsTab], so a new tab cannot be added without a group.
 */
private val SETTINGS_GROUP_BY_TAB: Map<SettingsTab, SettingsGroup> =
    SETTINGS_GROUPS.flatMap { group -> group.tabs.map { tab -> tab to group } }.toMap()

/**
 * The group a tab lives in.
 */
internal val SettingsTab.group: SettingsGroup
    get() = SETTINGS_GROUP_BY_TAB.getValue(this)

/**
 * "AI & Account › Account".
 *
 * The narrow menu's collapsed control has to name the group as well as the
 * tab: "Account" on its own says nothing about where the tab lives, which is
 * exactly what the wide rail's headings are there to say.
 */
internal val SettingsTab.groupedLabel: String
    get() = "${group.title} › $label"
