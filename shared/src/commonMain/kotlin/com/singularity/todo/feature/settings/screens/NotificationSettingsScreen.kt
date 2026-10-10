package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSwitchRow
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.settings.SettingsUiState

@Composable
fun NotificationSettingsScreen(
    state: SettingsUiState.Content,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Notifications") {
            SettingsSwitchRow(
                title = "Enable Notifications",
                testTag = TestTags.Settings.NOTIFICATIONS_ENABLED_SWITCH,
                checked = state.notifications.enabled,
                onCheckedChange = { onIntent(SettingsIntent.Notifications.UpdateEnabled(it)) },
            )
        }

        if (state.notifications.enabled) {
            SettingsSection(title = "Alerts") {
                SettingsSwitchRow(
                    title = "Notification Sound",
                    testTag = TestTags.Settings.NOTIFICATIONS_SOUND_SWITCH,
                    checked = state.notifications.sound,
                    onCheckedChange = { onIntent(SettingsIntent.Notifications.UpdateSound(it)) },
                )
                SettingsSwitchRow(
                    title = "Vibration",
                    testTag = TestTags.Settings.NOTIFICATIONS_VIBRATION_SWITCH,
                    checked = state.notifications.vibration,
                    onCheckedChange = { onIntent(SettingsIntent.Notifications.UpdateVibration(it)) },
                )
            }
        }

        ReminderDefaultsSection(
            selected = state.notifications.reminderDefault,
            onSelect = { onIntent(SettingsIntent.Notifications.UpdateReminderDefault(it)) },
        )
    }
}

@Composable
private fun ReminderDefaultsSection(
    selected: ReminderOffset,
    onSelect: (ReminderOffset) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSection(
        title = "Default Reminder",
        modifier = modifier.testTag(TestTags.Settings.REMINDER_DEFAULTS_SECTION),
    ) {
        ReminderOffset.entries.forEach { offset ->
            ReminderRadioRow(
                label = offset.label,
                selected = selected == offset,
                onClick = { onSelect(offset) },
                testTag = TestTags.Settings.reminderOffsetRadioRow(offset),
            )
        }
    }
}

@Composable
private fun ReminderRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth()
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            },
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
            notifications = SettingsSection.Notifications(
                enabled = true,
                sound = true,
                vibration = true,
                reminderDefault = ReminderOffset.AT_DUE,
            ),
        ),
        onIntent = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotificationSettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    NotificationSettingsScreen(
        state = SettingsUiState.Content(
            notifications = SettingsSection.Notifications(
                enabled = false,
            ),
        ),
        onIntent = {},
    )
}
