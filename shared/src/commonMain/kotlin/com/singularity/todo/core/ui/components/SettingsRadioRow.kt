package com.singularity.todo.core.ui.components

import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * One settings row with a radio button on the right edge.
 *
 * Use in settings screens where exactly one option from a group must be selected
 * (e.g. default agenda view, date format, sort order).
 *
 * @param title    Primary label text.
 * @param selected Whether this row's option is currently selected.
 * @param onClick  Called when the row is tapped. The caller manages the selection state.
 * @param modifier Standard Compose modifier.
 * @param subtitle Optional secondary label.
 */
@Composable
fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    testTag: String = "",
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            RadioButton(selected = selected, onClick = null)
        },
        modifier = if (testTag.isNotEmpty()) modifier.testTag(testTag) else modifier,
    )
}
