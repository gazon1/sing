package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSwitchRow
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.settings.ReminderOffset
import com.singularity.todo.feature.settings.SettingsIntent
import com.singularity.todo.feature.settings.SettingsUiState

@Composable
fun NotificationSettingsScreen(
    state: SettingsUiState.Content,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Notifications") {
            SettingsSwitchRow(
                title = "Enable Notifications",
                checked = state.notificationsEnabled,
                onCheckedChange = { onIntent(SettingsIntent.UpdateNotificationsEnabled(it)) },
            )
        }

        if (state.notificationsEnabled) {
            SettingsSection(title = "Alerts") {
                SettingsSwitchRow(
                    title = "Notification Sound",
                    checked = state.notificationSound,
                    onCheckedChange = { onIntent(SettingsIntent.UpdateNotificationSound(it)) },
                )
                SettingsSwitchRow(
                    title = "Vibration",
                    checked = state.notificationVibration,
                    onCheckedChange = { onIntent(SettingsIntent.UpdateNotificationVibration(it)) },
                )
            }
        }

        ReminderDefaultsSection(
            selected = state.reminderDefault,
            onSelect = { onIntent(SettingsIntent.UpdateReminderDefault(it)) },
        )
    }
}

@Composable
private fun ReminderDefaultsSection(
    selected: ReminderOffset,
    onSelect: (ReminderOffset) -> Unit,
) {
    SettingsSection(title = "Default Reminder") {
        ReminderOffset.entries.forEach { offset ->
            ReminderRadioRow(
                label = offset.label,
                selected = selected == offset,
                onClick = { onSelect(offset) },
            )
        }
    }
}

@Composable
private fun ReminderRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotificationSettingsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    NotificationSettingsScreen(
        state = SettingsUiState.Content(
            notificationsEnabled = true,
            notificationSound = true,
            notificationVibration = true,
            reminderDefault = ReminderOffset.AT_DUE,
        ),
        onIntent = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotificationSettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    NotificationSettingsScreen(
        state = SettingsUiState.Content(
            notificationsEnabled = false,
        ),
        onIntent = {},
    )
}
