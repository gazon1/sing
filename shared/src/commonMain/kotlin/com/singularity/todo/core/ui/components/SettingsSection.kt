package com.singularity.todo.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick

/**
 * Type alias for the `trailing` slot in [SettingsRow] — allows callers to pass
 * arbitrary composable content (Switch, RadioButton, Icon, etc.) with [RowScope] access.
 *
 * NOTE: don't use `Modifier.weight` inside `trailing` — the row already allocates
 * space for the leading title/subtitle column via `weight(1f)`, and a second
 * `weight` inside `trailing` will fight it for space.
 */
typealias SettingsRowTrailing = @Composable RowScope.() -> Unit

/**
 * Titled card used as the visual container for one settings sub-section.
 *
 * Replaces the `Card(modifier = Modifier.fillMaxWidth()) { Column { Text("Section", titleSmall) ... } }`
 * pattern that previously lived in every Settings sub-screen.
 *
 * @param contentPadding      Padding applied around the section's content column.
 *                            Pass `PaddingValues(0.dp)` (with rows supplying their
 *                            own horizontal padding) if you want ripple to reach
 *                            the card's edges.
 * @param verticalArrangement Spacing/arrangement between rows. Defaults to a small
 *                            gap; pass e.g. `Arrangement.spacedBy(0.dp)` if you're
 *                            inserting your own `HorizontalDivider`s between rows.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

/**
 * One settings row with a label, optional subtitle, and a composable [trailing] slot.
 *
 * This is the canonical row layout for the settings screen — [SettingsSwitchRow]
 * and any future variant (radio, value-display, …) should be expressed as thin
 * wrappers around this composable rather than duplicating the layout.
 *
 * @param title    Primary label text.
 * @param subtitle Optional secondary label. Clipped to two lines with an ellipsis
 *                 so a long subtitle can't blow up the row's layout.
 * @param onClick  Optional click handler. When non-null the *entire row* is
 *                 tappable (not just [trailing]), and exposed to accessibility
 *                 services as a single `Role.Button` element.
 * @param trailing Composable slot rendered on the right side. Use for Switch,
 *                 icons, chevrons. See [SettingsRowTrailing] for constraints.
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
        modifier = modifier.fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f)
                .fillMaxWidth(),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
 *
 * Implemented as a thin wrapper over [SettingsRow]: the whole row is clickable
 * and toggles the switch, and the [Switch] itself has `onCheckedChange = null`
 * so it doesn't independently handle the click (and double-fire the callback).
 * This also collapses row + switch into a single semantic node for
 * accessibility, rather than two separate ones.
 *
 * @param testTag **Required, with no default.** This parameter previously
 *   defaulted to [TestTags.Settings.DARK_THEME_SWITCH], and 15 of the 16 call
 *   sites omitted it — so every toggle in the app carried the *dark theme*
 *   switch's tag. That is not a cosmetic naming problem: `onNodeWithTag` throws
 *   on multiple matches, so any test that found the dark-theme switch was
 *   relying on the other fifteen being absent, and a screen rendering two
 *   untagged switches was untestable rather than merely untagged. A default
 *   that is wrong for every caller who does not notice it is worse than no
 *   default, because the signature now advertises an addressable control that
 *   is not addressable. There is now exactly one call site that means the dark
 *   theme, and it names it.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier.testTag(testTag),
        // A disabled row drops its click handler entirely rather than ignoring the
        // callback, so the control is genuinely inert instead of merely refusing to act.
        onClick = if (enabled) {
            { onCheckedChange(!checked) }
        } else {
            null
        },
        trailing = {
            Switch(
                checked = checked,
                // Click handling lives on the row itself; leaving this non-null
                // would fire onCheckedChange twice when tapping the switch directly.
                onCheckedChange = null,
                enabled = enabled,
            )
        },
    )
}

/**
 * One labelled row inside a [SettingsSection] whose trailing slot shows the
 * current value as text (e.g. "Language" -> "English") instead of a chevron.
 * Tapping the row (e.g. to open a picker dialog) is handled via [onClick].
 */
@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        onClick = onClick,
        trailing = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * One labelled row inside a [SettingsSection] that navigates elsewhere on tap,
 * shown with a trailing chevron (e.g. "Language", "About").
 */
@Composable
fun SettingsActionRow(title: String, onClick: () -> Unit, subtitle: String? = null, modifier: Modifier = Modifier) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

// Previews for these composables live in androidMain
// (SettingsComponentsPreview.kt), since androidx.compose.ui.tooling.preview
// is Android-only tooling and shouldn't be pulled into commonMain.

@Preview
@Composable
private fun SettingsSectionLightPreview() = PreviewThemed(darkTheme = false) {
    SettingsSection(title = "Appearance") {
        SettingsSwitchRow(
            title = "Dark theme",
            subtitle = "Use dark color scheme",
            testTag = TestTags.Settings.DARK_THEME_SWITCH,
            checked = false,
            onCheckedChange = {},
        )
        SettingsSwitchRow(
            title = "Notifications",
            subtitle = "Show reminders",
            testTag = TestTags.Settings.NOTIFICATIONS_ENABLED_SWITCH,
            checked = true,
            onCheckedChange = {},
        )
    }
}

@Preview
@Composable
private fun SettingsSectionDarkPreview() = PreviewThemed(darkTheme = true) {
    SettingsSection(title = "Account") {
        SettingsSwitchRow(
            title = "Auto-sync",
            subtitle = "Sync data automatically",
            testTag = "preview_auto_sync",
            checked = true,
            onCheckedChange = {},
        )
        SettingsSwitchRow(
            title = "Offline mode",
            testTag = "preview_offline_mode",
            checked = false,
            onCheckedChange = {},
        )
    }
}

@Preview
@Composable
private fun SettingsRowLightPreview() = PreviewThemed(darkTheme = false) {
    SettingsSection(title = "General") {
        SettingsActionRow(title = "Language", subtitle = "English", onClick = noopClick)
        SettingsActionRow(title = "About", onClick = noopClick)
        SettingsSwitchRow(title = "Switch setting", checked = true, onCheckedChange = {}, testTag = "preview_switch")
    }
}

@Preview
@Composable
private fun SettingsRowDarkPreview() = PreviewThemed(darkTheme = true) {
    SettingsSection(title = "General") {
        SettingsActionRow(title = "Language", subtitle = "English", onClick = noopClick)
    }
}

@Preview
@Composable
private fun SettingsValueRowPreview() = PreviewThemed(darkTheme = false) {
    SettingsSection(title = "Region") {
        SettingsValueRow(title = "Language", value = "English", onClick = noopClick)
        SettingsValueRow(
            title = "Week starts on",
            value = "Monday",
            subtitle = "Applies to calendar and reminders",
            onClick = noopClick,
        )
    }
}
