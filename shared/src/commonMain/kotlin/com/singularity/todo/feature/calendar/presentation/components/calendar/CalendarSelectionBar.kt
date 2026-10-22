package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds

/**
 * The bar that appears at the bottom of the calendar while days are being picked.
 *
 * It says how many days are picked and offers the one thing the mode is for:
 * create one task on all of them. The count is in the bar rather than in the
 * top bar because the top bar already carries the month label, and two
 * competing counters in one header is how a user loses track of which is which.
 */
@Composable
fun CalendarSelectionBar(
    selectedCount: Int,
    onCreateTask: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth()
            .testTag(TestTags.Calendar.SELECTION_BAR)
            .mapTestTagsAsResourceIds(),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Выбрано $selectedCount дн.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .testTag(TestTags.Calendar.CANCEL_SELECTION)
                        .mapTestTagsAsResourceIds(),
                ) { Text("Отмена") }
                TextButton(
                    onClick = onCreateTask,
                    enabled = selectedCount > 0,
                    modifier = Modifier
                        .testTag(TestTags.Calendar.CREATE_ON_SELECTED)
                        .mapTestTagsAsResourceIds(),
                ) { Text("Создать задачу") }
            }
        }
    }
}
