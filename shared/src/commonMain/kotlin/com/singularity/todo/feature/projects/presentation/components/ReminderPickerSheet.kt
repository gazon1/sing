package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.reminders.ReminderPicker

/**
 * Reminder picker sheet for project-level reminders.
 *
 * ## Selecting an offset here does persist — and does nothing
 *
 * An earlier version of this KDoc said the opposite ("selecting any offset dismisses
 * the sheet without side-effects"), which was false: [ProjectDetailViewModel.setReminder]
 * writes the `project_reminders` row. The row was real; only the firing was missing,
 * because no platform schedules a `ProjectReminder`.
 *
 * The sheet is therefore unreachable — the bell in [ProjectBottomActionBar] is disabled
 * while `PROJECT_REMINDERS_SUPPORTED` is false. It is kept so the follow-up that adds
 * the scheduler does not also have to rebuild the picker.
 *
 * @param currentOffset The currently selected reminder offset, or null for none.
 * @param onSelect Called with the chosen offset when user confirms.
 * @param onDismiss Called when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderPickerSheet(currentOffset: ReminderOffset?, onSelect: (ReminderOffset) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Remind me", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            ReminderPicker(
                selected = currentOffset ?: ReminderOffset.AT_DUE,
                onSelect = onSelect,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }
    }
}
