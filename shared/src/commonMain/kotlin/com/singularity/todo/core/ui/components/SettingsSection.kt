package com.singularity.todo.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * Type alias for the `trailing` slot in [SettingsRow] — allows callers to pass
 * arbitrary composable content (Switch, RadioButton, Icon, etc.) with [RowScope] access.
 */
typealias SettingsRowTrailing = @Composable RowScope.() -> Unit

/**
 * Titled card used as the visual container for one settings sub-section.
 *
 * Replaces the `Card(modifier = Modifier.fillMaxWidth()) { Column { Text("Section", titleSmall) ... } }`
 * pattern that previously lived in every Settings sub-screen.
 */
@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
 * One settings row with a label, optional subtitle, and a composable [trailing] slot.
 *
 * Use when a setting needs more than a Switch — e.g. a clickable row with a chevron,
 * a row with a value display, or a row with radio buttons.
 *
 * @param title    Primary label text.
 * @param subtitle Optional secondary label.
 * @param onClick  Optional click handler. When non-null the row is tappable.
 * @param trailing Composable slot rendered on the right side. Use for Switch, icons, chevrons.
 * @param modifier Standard Compose modifier.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: SettingsRowTrailing = {},
) {
    Row(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
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

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SettingsRowLightPreview() = PreviewThemed(darkTheme = false) {
    SettingsSection(title = "General") {
        SettingsRow(
            title = "Language",
            subtitle = "English",
            onClick = {},
            trailing = {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        SettingsRow(
            title = "About",
            onClick = {},
            trailing = {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        SettingsRow(
            title = "Switch setting",
            trailing = {
                Switch(checked = true, onCheckedChange = {})
            },
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SettingsRowDarkPreview() = PreviewThemed(darkTheme = true) {
    SettingsSection(title = "General") {
        SettingsRow(
            title = "Language",
            subtitle = "English",
            onClick = {},
            trailing = {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}
