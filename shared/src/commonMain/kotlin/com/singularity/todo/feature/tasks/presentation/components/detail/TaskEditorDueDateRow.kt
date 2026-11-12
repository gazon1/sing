package com.singularity.todo.feature.tasks.presentation.components.detail
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.localizedDateList
import com.singularity.todo.core.ui.components.localizedDueDateWithTime
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.add_date
import singularity_todo.shared.generated.resources.reset_date
import singularity_todo.shared.generated.resources.someday_label

/**
 * Renders the due date attribute row in the task editor.
 * Tapping the row opens the [com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet.Date] sheet.
 *
 * @param dueDate Current due date value, or `null` if not set.
 * @param dueDateTime Current due time value, or `null` if not set.
 * @param dueDates The dates of the work when it is scheduled on several of them.
 *   More than one entry changes the label to the list of dates; one or none keeps
 *   the single-date label exactly as it was.
 * @param onDueDateClick Called when the row is tapped to open the date picker sheet.
 *   Pass `null` when [onDueDateClear] is also `null` (Create mode — sheet opened by default).
 * @param onDueDateClear Called when the X button is tapped. Pass `null` in Create mode
 *   (no X button is shown).
 * @param someday Whether the task sits in the `someday` bucket. The row then reads
 *   «Когда-нибудь» instead of a date, because a deferred task carries no date.
 */
@Composable
fun TaskEditorDueDateRow(
    dueDate: LocalDate?,
    dueTime: LocalTime?,
    onDueDateClick: (() -> Unit)?,
    onDueDateClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
    labelTestTag: String? = null,
    dueDates: ImmutableList<LocalDate> = persistentListOf(),
    someday: Boolean = false,
) {
    val dates = dueDates.ifEmpty { dueDate?.let { persistentListOf(it) } ?: persistentListOf() }
    val isSet = dueDate != null || someday
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(TestTags.TASK_EDITOR_DUE_ROW)
                .mapTestTagsAsResourceIds()
                // No clickable() at all when the callback is null — see TaskEditorPriorityRow.
                .then(
                    if (onDueDateClick != null) {
                        Modifier.clickable { onDueDateClick() }
                    } else {
                        Modifier
                    },
                ).padding(
                    horizontal = TaskSpacing.cardPaddingHorizontal,
                    vertical = TaskSpacing.cardPaddingVertical,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val activeTint =
            if (isSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        val idleTint =
            if (isSet) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(
            imageVector = Icons.Outlined.CalendarToday,
            contentDescription = null,
            tint = if (isSet) activeTint else idleTint,
            modifier = Modifier.size(TaskSpacing.iconSize),
        )
        Spacer(Modifier.width(TaskSpacing.lg))
        val labelModifier =
            if (labelTestTag != null) {
                Modifier.testTag(dueDateRowLabelTag(labelTestTag, dates, someday))
            } else {
                Modifier
            }
        Text(
            text = dueDateRowLabel(dueDate, dueTime, dates, someday),
            color = if (isSet) idleTint else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            fontWeight = if (isSet) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f).then(labelModifier),
        )
        if (isSet && onDueDateClear != null) {
            IconButton(onClick = onDueDateClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.reset_date),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

/**
 * The tag the row's label carries, and the state it names.
 *
 * The row shows three different facts — a date, the absence of a date and the
 * «Когда-нибудь» bucket — and each of them has to be tellable apart without
 * reading the copy: the copy is localized, so a test that matched on it would
 * break on every device set to another language.
 */
private fun dueDateRowLabelTag(
    labelTestTag: String,
    dates: ImmutableList<LocalDate>,
    someday: Boolean,
): String =
    when {
        someday -> TestTags.TASK_EDITOR_DUE_DATE_ROW_SOMEDAY
        dates.isEmpty() -> TestTags.TASK_EDITOR_DUE_DATE_ROW_PLACEHOLDER
        else -> labelTestTag
    }

/**
 * The label of the due-date row, in the resource locale.
 *
 * This row is the one that was reported as Russian next to an English screen
 * (#1700), so both of its readings come from `composeResources`: the empty one
 * *and* the dated one. Localising only "Add date" left the same defect behind a
 * tap — an English placeholder that became "30 окт 2026" once a date was set.
 *
 * The shape is the agenda list's — weekday, day, month, year, and the time next
 * to it. The agenda itself still renders through `formatRussianDueDate`, whose
 * weekday and month names take no locale; localising that is a separate job
 * (#1634, ADR `2026-11-02-app-numbers-are-not-the-device-locale`). Until it
 * lands the two surfaces agree in Russian and differ in English, and the
 * difference is that formatter's, not this row's.
 *
 * Several dates read as a list, because a row that showed only the first of them
 * would hide the fact that there are others.
 */
@Composable
private fun dueDateRowLabel(
    date: LocalDate?,
    time: LocalTime?,
    dates: ImmutableList<LocalDate>,
    someday: Boolean,
): String {
    // A deferred task has no date to show, so the row says what the task
    // actually is instead of an empty date.
    if (someday) return stringResource(Res.string.someday_label)
    if (date == null) return stringResource(Res.string.add_date)
    if (dates.size > 1) return localizedDateList(dates)
    return localizedDueDateWithTime(date, time)
}
