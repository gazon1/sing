package com.singularity.todo.core.ui.components.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.formatRussianMonthYear
import com.singularity.todo.core.ui.components.russianWeekdayHeaders
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.LocalDate

/**
 * The month calendar every month-shaped date picker in the app draws.
 *
 * ## Why it is here and not inside the multi-date picker
 *
 * The multi-date picker grew the app's own grid because Material's `DatePicker`
 * exposes no stable handle on its day cells. The task detail's scheduling
 * sheet needs the same grid — a Material calendar block is 600 dp tall, which
 * does not fit next to a time control and three actions on a 360×800 screen —
 * and a second copy of the grid would be a second copy of its month paging
 * rules, its "today" marking and its cell tags.
 *
 * The tags are the caller's, because the two pickers address their days
 * differently: [dayTestTag] is what the cell carries, so the multi-date picker
 * keeps its own `multi_date_picker_day_*` tags and the scheduling sheet gets
 * its own.
 */
@Composable
fun MonthCalendarHeader(
    monthAnchor: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    monthTitleTag: String = TestTags.MultiDatePicker.MONTH_TITLE,
    previousMonthTag: String = TestTags.MultiDatePicker.PREV_MONTH,
    nextMonthTag: String = TestTags.MultiDatePicker.NEXT_MONTH,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevious,
            modifier =
                Modifier
                    .testTag(previousMonthTag)
                    .mapTestTagsAsResourceIds(),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий месяц")
        }
        Text(
            text = formatRussianMonthYear(monthAnchor),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(monthTitleTag),
        )
        IconButton(
            onClick = onNext,
            modifier =
                Modifier
                    .testTag(nextMonthTag)
                    .mapTestTagsAsResourceIds(),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий месяц")
        }
    }
}

/**
 * The six-week grid of the month, with the picked days marked.
 *
 * @param selected The days that are picked. A day in this set is drawn as
 *   selected.
 * @param onDayClick Called with the tapped day.
 * @param dayTestTag The tag a day cell carries. The cell is addressed by its
 *   date, not by its position, so a flow does not depend on the grid's layout.
 */
@Composable
fun MonthCalendarGrid(
    monthAnchor: LocalDate,
    today: LocalDate,
    selected: ImmutableSet<LocalDate>,
    onDayClick: (LocalDate) -> Unit,
    dayTestTag: (LocalDate) -> String,
    modifier: Modifier = Modifier,
    disabledDates: ImmutableSet<LocalDate> = persistentSetOf(),
) {
    val gridDates = remember(monthAnchor) { monthGridDates(monthAnchor) }
    Column(modifier = modifier.fillMaxWidth()) {
        gridDates.chunked(7).forEach { week ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
            ) {
                week.forEach { date ->
                    CalendarDayCell(
                        date = date,
                        isCurrentMonth = date.month == monthAnchor.month,
                        isToday = date == today,
                        isSelected = date in selected,
                        isDisabled = date in disabledDates,
                        onClick = { onDayClick(date) },
                        dayTestTag = dayTestTag,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The weekday names above the grid. */
@Composable
internal fun WeekdayHeaderRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        russianWeekdayHeaders.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    isCurrentMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    isDisabled: Boolean,
    onClick: () -> Unit,
    dayTestTag: (LocalDate) -> String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .padding(2.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isDisabled -> Color.Transparent
                        isSelected -> MaterialTheme.colorScheme.primary
                        isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        else -> Color.Transparent
                    },
                ).clickable(enabled = !isDisabled, onClick = onClick)
                .testTag(dayTestTag(date))
                .mapTestTagsAsResourceIds(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.day.toString(),
            color =
                when {
                    isDisabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    !isCurrentMonth -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            fontSize = 14.sp,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
