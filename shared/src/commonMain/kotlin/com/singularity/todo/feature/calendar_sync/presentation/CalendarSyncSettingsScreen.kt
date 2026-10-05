package com.singularity.todo.feature.calendar_sync.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSwitchRow
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.permission.rememberCalendarPermissionRequester
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.LoadCalendars
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SelectAppPackage
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SelectCalendar
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SetEnabled
import com.singularity.todo.feature.calendar_sync.presentation.CalendarSyncIntent.SyncNow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel

/**
 * Calendar sync settings screen.
 *
 * Allows the user to:
 * 1. Grant READ/WRITE_CALENDAR permissions (on first visit)
 * 2. Enable/disable calendar sync
 * 3. Select which calendar app to sync into (Google Calendar, Samsung Calendar, etc.)
 * 4. Select which calendar within that app
 * 5. Trigger a manual sync
 *
 * Integrated into the Settings tab via [com.singularity.todo.feature.settings.SettingsScreen].
 */
@Composable
fun CalendarSyncSettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: CalendarSyncViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionRequester = rememberCalendarPermissionRequester()

    LaunchedEffect(Unit) {
        viewModel.onIntent(LoadCalendars)
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ─── Permission gate ─────────────────────────────────────────────
        if (!permissionRequester.hasPermissions) {
            PermissionGate(
                onRequestPermission = { permissionRequester.requestPermissions() },
            )
            return@Column
        }

        // ─── Enable toggle ────────────────────────────────────────────────
        SettingsSection(title = "System Calendar Sync") {
            SettingsSwitchRow(
                title = "Enable Sync",
                subtitle = if (state.isSupported) {
                    "One-way: tasks sync to your system calendar"
                } else {
                    "Not available on this platform — system calendar sync is Android-only"
                },
                checked = state.isEnabled,
                enabled = state.isSupported,
                onCheckedChange = { viewModel.onIntent(SetEnabled(it)) },
            )
        }

        // ─── Calendar app picker ───────────────────────────────────────────
        if (state.isEnabled && state.isSupported) {
            CalendarAppPicker(
                selectedAppPackage = state.selectedAppPackage,
                availableApps = state.availableApps,
                onSelectApp = { pkg -> viewModel.onIntent(SelectAppPackage(pkg)) },
            )
        }

        // ─── Calendar selection ──────────────────────────────────────────
        if (state.isEnabled && state.isSupported) {
            SettingsSection(title = "Target Calendar") {
                if (state.availableCalendars.isEmpty() && state.isLoading) {
                    Text(
                        text = "Loading calendars...",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else if (state.availableCalendars.isEmpty()) {
                    Text(
                        text = "No calendars available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    state.availableCalendars.forEach { (id, name) ->
                        RadioRow(
                            label = name,
                            selected = state.selectedCalendarId == id,
                            onClick = { viewModel.onIntent(SelectCalendar(id)) },
                        )
                    }
                }
            }
        }

        // ─── Status ───────────────────────────────────────────────────
        if (state.isEnabled && state.isSupported) {
            SettingsSection(title = "Status") {
                val statusText = when (val s = state.status) {
                    is CalendarSyncStatus.Disabled -> "Disabled"

                    is CalendarSyncStatus.Idle -> {
                        val date = s.lastSyncedAt?.let { ts ->
                            val instant = Instant.fromEpochMilliseconds(ts)
                            val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
                            val month = local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
                            val hour = local.hour.toString().padStart(2, '0')
                            val minute = local.minute.toString().padStart(2, '0')
                            "$month ${local.dayOfMonth}, ${local.year} $hour:$minute"
                        } ?: "Never"
                        "Last synced: $date"
                    }

                    is CalendarSyncStatus.Syncing -> "Syncing..."

                    is CalendarSyncStatus.Failed -> "Failed: ${s.reason}"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                Button(
                    onClick = { viewModel.onIntent(SyncNow) },
                    enabled = state.status !is CalendarSyncStatus.Syncing,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Sync Now")
                }
            }

            // ─── Info ─────────────────────────────────────────────────────
            SettingsSection(title = "About") {
                Text(
                    text = "Syncs task title, due date, due time, and recurrence to your system calendar as all-day or timed events. Deep-links back to this app are embedded in the event description.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionGate(onRequestPermission: () -> Unit) {
    SettingsSection(title = "Permissions Required") {
        Text(
            text = "Calendar sync requires READ_CALENDAR and WRITE_CALENDAR permissions.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onRequestPermission,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Grant Permission")
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
