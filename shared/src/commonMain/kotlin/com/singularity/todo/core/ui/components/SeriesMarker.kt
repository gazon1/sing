package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.presentation.theme.mutedTextColor

/**
 * The mark that says "this row is one date of a series".
 *
 * A series is one piece of work on several dates, and a row that shows only its
 * own date reads like a task that has nothing to do with the rows around it.
 * The mark says what the row is, and how many dates the work has.
 *
 * @param dates How many dates the series has. `null` renders nothing — a task
 *   that stands alone has nothing to mark.
 * @param compact The icon alone, for the places where there is no room for a
 *   word — the calendar's chip.
 */
@Composable
fun SeriesMarker(
    dates: Int?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (dates == null) return
    val label = if (compact) {
        "Серия"
    } else {
        "серия · ${formatDateCount(dates)}"
    }
    Row(
        modifier = modifier.testTag(TestTags.TaskSeries.MARKER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.EventRepeat,
            contentDescription = label,
            tint = mutedTextColor(),
            modifier = Modifier.size(14.dp),
        )
        if (!compact) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = mutedTextColor(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
