package com.singularity.todo.feature.calendar_sync.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarAppInfo

/**
 * Calendar app picker — lets the user choose which calendar app to sync to.
 *
 * Shown as a radio list of installed apps that handle calendar event intents.
 * "System default" is always the first option.
 *
 * @param selectedAppPackage The currently selected package name, or null for system default.
 * @param availableApps List of calendar apps installed on the device.
 * @param onSelectApp Called when the user picks an app (null = system default).
 */
@Composable
fun CalendarAppPicker(
    selectedAppPackage: String?,
    availableApps: List<CalendarAppInfo>,
    onSelectApp: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSection(title = "Calendar App", modifier = modifier) {
        if (availableApps.isEmpty()) {
            Text(
                text = "No calendar apps found — using system default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            AppRadioRow(
                label = "System default",
                selected = selectedAppPackage == null,
                onClick = { onSelectApp(null) },
            )
        } else {
            // "System default" option first
            AppRadioRow(
                label = "System default",
                selected = selectedAppPackage == null,
                onClick = { onSelectApp(null) },
            )
            // Then all discovered apps
            availableApps.forEach { app ->
                AppRadioRow(
                    label = app.displayName,
                    selected = selectedAppPackage == app.packageName,
                    onClick = { onSelectApp(app.packageName) },
                )
            }
        }
    }
}

@Composable
private fun AppRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            modifier = Modifier.padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
