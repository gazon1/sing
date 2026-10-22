package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.formatRussianShortDate
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * The card that tells the user the task on screen is one occurrence of a series.
 *
 * ## What it shows
 *
 * How many dates the work is scheduled on, and which ones. The dates are
 * tappable: an occurrence is a task like any other, and the fastest way to reach
 * the one on 17 October is to tap 17 October.
 *
 * ## What it offers
 *
 * * **Применить ко всем** — copies the time of day and the pin of this
 *   occurrence to the others. The shared fields (title, project, tags, …) do not
 *   need this button: they are already applied to the whole series when they are
 *   edited.
 * * **Оставить отдельно** — takes this occurrence out of the series. The
 *   occasion is a date that turned out to be a different job: the occurrence
 *   keeps its date and becomes a task that stands alone.
 * * **Удалить серию** — archives every occurrence. Deleting one occurrence
 *   archives only that one; that is what the overflow menu of the task does.
 *
 * The card is not rendered at all for a task that stands alone.
 */
@Composable
fun TaskSeriesCard(
    members: List<Task>,
    onOpenMember: (TaskId) -> Unit,
    onApplyToAll: () -> Unit,
    onDetach: () -> Unit,
    onDeleteSeries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (members.size < 2) return
    ExtraSectionCard(
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Outlined.DateRange,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        label = "Серия · ${members.size} дат",
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                members.forEach { member ->
                    val date = member.dueDate
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenMember(member.id) }
                            .testTag(TestTags.TaskSeries.MEMBER)
                            .mapTestTagsAsResourceIds()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = date?.let { formatRussianShortDate(it) } ?: "—",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = member.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    TextButton(
                        onClick = onApplyToAll,
                        modifier = Modifier
                            .testTag(TestTags.TaskSeries.APPLY_TO_ALL)
                            .mapTestTagsAsResourceIds(),
                    ) { Text("Применить ко всем") }
                    TextButton(
                        onClick = onDetach,
                        modifier = Modifier
                            .testTag(TestTags.TaskSeries.DETACH)
                            .mapTestTagsAsResourceIds(),
                    ) { Text("Оставить отдельно") }
                    TextButton(
                        onClick = onDeleteSeries,
                        modifier = Modifier
                            .testTag(TestTags.TaskSeries.DELETE)
                            .mapTestTagsAsResourceIds(),
                    ) { Text("Удалить серию") }
                }
            }
        },
    )
}
