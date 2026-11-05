package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.text.SelectableText

/**
 * The sentence the agenda shows for a task that falls outside the tab's own range.
 *
 * ## Why it exists
 *
 * The three agenda tabs partition the tasks: the Inbox holds the undated ones,
 * Today the overdue and today's, Upcoming tomorrow through next week. A task dated
 * further out than next week is in none of them — it is in the Calendar and in the
 * Plans timeline, and nowhere else a person would think to look.
 *
 * Nothing on the screen said so. The decision that puts it there
 * (`docs/decisions/2026-10-31-the-agenda-tabs-are-disjoint.md`) and the
 * documentation of `AgendaPresets.Upcoming` both name the two surfaces, but both
 * live where only a developer reads. A person who reached the end of the Upcoming
 * tab and did not see their task had no way to learn that the task was not lost.
 *
 * ## The wording
 *
 * The app's own names — "Calendar" and "Plans" — and nothing about a range or a
 * horizon: the person is being told where to look, not how the buckets are cut.
 * Neither destination is called a tab, because the Calendar is a bottom-bar tab on
 * Android and a menu entry on the desktop, and the Plans surface is the other way
 * round. A sentence that named a tab would be wrong on one of them.
 */
internal const val BEYOND_NEXT_WEEK_NOTE: String =
    "Tasks dated beyond next week are in the Calendar and the Plans timeline."

/**
 * The pointer itself: one muted line, under the list or under the empty state.
 *
 * @param modifier Compose modifier.
 */
@Composable
internal fun BeyondNextWeekNote(modifier: Modifier = Modifier) {
    SelectableText(
        text = BEYOND_NEXT_WEEK_NOTE,
        style = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
