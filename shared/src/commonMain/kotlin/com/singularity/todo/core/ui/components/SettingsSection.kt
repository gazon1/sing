package com.singularity.todo.core.ui.components

import com.singularity.todo.core.ui.preview.PreviewThemed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Titled card used as the visual container for one settings sub-section.
 *
 * Replaces the `Card(modifier = Modifier.fillMaxWidth()) { Column { Text("Section", titleSmall) ... } }`
 * pattern that previously lived in every Settings sub-screen.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/**
 * One labelled row inside a [SettingsSection]. Shows a title, an optional
 * subtitle, and a [Switch] on the right edge. Used by every "toggle" setting
 * (Dark theme, Notifications, Vibration, Saturday/Sunday, …).
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SettingsSectionLightPreview() = PreviewThemed(darkTheme = false) {
    SettingsSection(title = "Appearance") {
        SettingsSwitchRow(
            title = "Dark theme",
            subtitle = "Use dark color scheme",
            checked = false,
            onCheckedChange = {},
        )
        SettingsSwitchRow(
            title = "Notifications",
            subtitle = "Show reminders",
            checked = true,
            onCheckedChange = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SettingsSectionDarkPreview() = PreviewThemed(darkTheme = true) {
    SettingsSection(title = "Account") {
        SettingsSwitchRow(
            title = "Auto-sync",
            subtitle = "Sync data automatically",
            checked = true,
            onCheckedChange = {},
        )
        SettingsSwitchRow(
            title = "Offline mode",
            checked = false,
            onCheckedChange = {},
        )
    }
}
