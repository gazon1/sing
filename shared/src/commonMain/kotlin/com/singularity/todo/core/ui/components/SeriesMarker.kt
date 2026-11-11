package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.mutedTextColor
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.series_label
import singularity_todo.shared.generated.resources.series_marker

/**
 * The mark that says "this row is one date of a series".
 *
 * A series is one piece of work on several dates, and a row that shows only its
 * own date reads like a task that has nothing to do with the rows around it.
 *
 * The mark is the icon alone: the word that used to stand beside it took more
 * width than the date and the project could spare, and the icon already says
 * what the mark is. How many dates the work has is in the icon's description,
 * in the user's language — spoken, not shown.
 *
 * @param isSeries Whether the row is one date of a series. A task that stands
 *   alone carries no mark.
 * @param dates How many dates the series has. `null` means the surface does not
 *   know the count — and not that the row is not part of a series.
 */
@Composable
fun SeriesMarker(
    isSeries: Boolean,
    dates: Int? = null,
    modifier: Modifier = Modifier,
) {
    if (!isSeries) return
    val label =
        if (dates == null) {
            stringResource(Res.string.series_marker)
        } else {
            pluralStringResource(Res.plurals.series_label, dates, dates)
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
    }
}
