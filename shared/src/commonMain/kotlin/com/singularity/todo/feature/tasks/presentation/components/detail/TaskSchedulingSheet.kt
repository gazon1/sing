package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.isoLocalDate
import com.singularity.todo.core.ui.components.localizedDueDateWithTime
import com.singularity.todo.core.ui.components.sheet.BottomSheetHost
import com.singularity.todo.core.ui.components.sheet.MonthCalendarGrid
import com.singularity.todo.core.ui.components.sheet.MonthCalendarHeader
import com.singularity.todo.core.ui.components.sheet.WeekdayHeaderRow
import com.singularity.todo.core.ui.components.sheet.rememberAppSheetState
import com.singularity.todo.core.ui.components.sheet.shiftMonth
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.action_cancel
import singularity_todo.shared.generated.resources.action_clear
import singularity_todo.shared.generated.resources.action_save
import singularity_todo.shared.generated.resources.add_time
import singularity_todo.shared.generated.resources.clear_time
import singularity_todo.shared.generated.resources.not_set
import singularity_todo.shared.generated.resources.preset_today
import singularity_todo.shared.generated.resources.preset_tomorrow
import singularity_todo.shared.generated.resources.scheduling_deadline_label
import singularity_todo.shared.generated.resources.scheduling_due_date_label
import singularity_todo.shared.generated.resources.scheduling_due_time_label
import singularity_todo.shared.generated.resources.scheduling_title
import singularity_todo.shared.generated.resources.someday_label

/**
 * Which fact of the planning sheet the calendar and the actions are editing.
 *
 * The sheet carries two dates and they are not the same fact: the due date is
 * when the work is *planned*, the deadline is the bound by which it has to be
 * *done*. They are edited one at a time, and the sheet says which one it is
 * editing — a sheet that edited both at once would need two calendars, and two
 * calendars do not fit on a 360×800 screen next to a time control and three
 * actions.
 */
enum class SchedulingTarget {
    /** The due date — and, with it, the due time. */
    DUE_DATE,

    /** The deadline — the bound by which the work has to be done. */
    DEADLINE,
}

/**
 * What the planning sheet commits.
 *
 * The sheet owns three facts and reports all of them: the caller decides which
 * of them actually changed and writes only those. A sheet that wrote
 * everything it was given would erase the reminder's neighbour on every save.
 */
data class TaskSchedulingResult(
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val deadline: LocalDate?,
    val someday: Boolean,
)

/**
 * The facts the sheet edits.
 *
 * The state lives in one object because the sheet has two readers — the body
 * and the action bar — and because the question "what does the grid write?"
 * deserves one answer rather than one per control. Every control that writes a
 * date goes through a method here, so a control's meaning cannot change with
 * the focus.
 */
@Stable
private class SchedulingState(
    dueDate: LocalDate?,
    dueTime: LocalTime?,
    deadline: LocalDate?,
    someday: Boolean,
    initialTarget: SchedulingTarget,
) {
    /** The due date. */
    var date by mutableStateOf(dueDate)

    /** The time of the due date. */
    var time by mutableStateOf(dueTime)

    /** The deadline. */
    var deadlineValue by mutableStateOf(deadline)

    /** Whether the task sits in the `someday` bucket. */
    var deferred by mutableStateOf(someday)

    /** Which fact the calendar and the actions are editing. */
    var target by mutableStateOf(initialTarget)

    /** The date the sheet is editing right now. */
    val focusedDate: LocalDate?
        get() =
            when (target) {
                SchedulingTarget.DUE_DATE -> date
                SchedulingTarget.DEADLINE -> deadlineValue
            }

    /**
     * Picks a date for the focused fact.
     *
     * A dated task is not a deferred one, so picking a date takes the task out
     * of the `someday` bucket.
     */
    fun pickDate(value: LocalDate) {
        when (target) {
            SchedulingTarget.DUE_DATE -> {
                date = value
                deferred = false
            }

            SchedulingTarget.DEADLINE -> {
                deadlineValue = value
            }
        }
    }

    /**
     * Picks a day in the grid.
     *
     * The same day twice takes the date off: the grid is a toggle for the
     * focused fact.
     */
    fun pickDay(day: LocalDate) {
        val picked = if (focusedDate == day) null else day
        when (target) {
            SchedulingTarget.DUE_DATE -> {
                date = picked
                deferred = false
            }

            SchedulingTarget.DEADLINE -> {
                deadlineValue = picked
            }
        }
    }

    /**
     * Puts the task in, or takes it out of, the `someday` bucket.
     *
     * A deferred task carries no date and no time: `someday` means *no date*.
     * Taking the task out of the bucket does not invent a date either.
     */
    fun setSomeday(value: Boolean) {
        deferred = value
        if (value) {
            date = null
            time = null
        }
    }

    /**
     * Clears the focused fact.
     *
     * The due date goes with its time — a time without a date is not a moment —
     * and the deadline goes alone. The reminder is not in this sheet at all.
     */
    fun clearFocused() {
        when (target) {
            SchedulingTarget.DUE_DATE -> {
                date = null
                time = null
                deferred = false
            }

            SchedulingTarget.DEADLINE -> {
                deadlineValue = null
            }
        }
    }
}

/**
 * The planning sheet of the task detail: the due date, its time, the deadline
 * and the `someday` preset in one place.
 *
 * ## What it commits, and when
 *
 * Nothing is written until [onConfirm]. «Отмена», a swipe-down and a back
 * gesture write nothing at all — the persisted values are exactly what they
 * were when the sheet opened.
 *
 * «Очистить» clears the **focused** fact and only that fact: the due date
 * (with its time, because a time without a date is not a moment) or the
 * deadline. The reminder is not in this sheet and is never touched by it.
 *
 * The due date and the due time are one fact, so a time is only offered when
 * there is a date to attach it to.
 *
 * @param initialTarget The fact the sheet opens on. The deadline row opens it
 *   on the deadline, the due-date row on the date.
 * @param today The current day, so that the grid can mark it. Passed in rather
 *   than read from the clock: a picker that marked the wrong day would be a
 *   picker nobody could test.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSchedulingSheet(
    dueDate: LocalDate?,
    dueTime: LocalTime?,
    deadline: LocalDate?,
    someday: Boolean,
    today: LocalDate,
    initialTarget: SchedulingTarget,
    onConfirm: (TaskSchedulingResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val state =
        remember {
            SchedulingState(dueDate, dueTime, deadline, someday, initialTarget)
        }
    var monthAnchor by remember {
        mutableStateOf(
            // The month of the fact the sheet opens on — the deadline row opens
            // it on the deadline, the date row on the date. A sheet that opened
            // on the *other* date's month made the user page to the fact they
            // came for.
            when (initialTarget) {
                SchedulingTarget.DUE_DATE -> dueDate
                SchedulingTarget.DEADLINE -> deadline
            } ?: today,
        )
    }
    // The calendar follows the focus: tapping the deadline row brings the
    // deadline's month, because the grid now edits the deadline.
    LaunchedEffect(state.target) {
        state.focusedDate?.let { monthAnchor = it }
    }
    val timeState = rememberSchedulingTimeState(dueTime) { state.time = it }

    // The sheet opens expanded.
    //
    // A sheet that opens half-expanded shows its top half and hides its
    // bottom — and the bottom of this sheet is where the actions are. Measured
    // on a 360×800 device, the half-expanded sheet was 352 dp tall and the
    // action row was 400 dp below the screen's edge: the buttons were on the
    // sheet and not on the screen. The sheet is tall, so it opens tall.
    val sheetState = rememberAppSheetState(initialValue = SheetValue.Expanded)

    BottomSheetHost(onDismiss = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The sheet's body is capped so that the action bar below it
                    // is never pushed off a 360×800 screen.
                    .heightIn(max = 560.dp),
        ) {
            SchedulingBody(
                state = state,
                today = today,
                monthAnchor = monthAnchor,
                timeState = timeState,
                onShiftMonth = { delta -> monthAnchor = shiftMonth(monthAnchor, delta) },
                modifier = Modifier.weight(1f),
            )

            SchedulingActionBar(
                onClear = { state.clearFocused() },
                onCancel = onDismiss,
                onConfirm = {
                    onConfirm(
                        TaskSchedulingResult(
                            dueDate = state.date,
                            dueTime = state.time,
                            deadline = state.deadlineValue,
                            someday = state.deferred,
                        ),
                    )
                },
            )
        }
    }
}

/**
 * The scrollable body of the sheet: the facts, the presets, the grid and the
 * time.
 */
@Composable
private fun SchedulingBody(
    state: SchedulingState,
    today: LocalDate,
    monthAnchor: LocalDate,
    timeState: TimePickerState,
    onShiftMonth: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.scheduling_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(TestTags.TASK_SCHEDULING_SHEET),
        )

        SchedulingFacts(
            state = state,
            onFocus = { state.target = it },
        )

        SchedulingPresets(
            today = today,
            focusedDate = state.focusedDate,
            someday = state.deferred,
            target = state.target,
            onPickDate = { state.pickDate(it) },
            onToggleSomeday = { state.setSomeday(it) },
        )

        SchedulingCalendar(
            monthAnchor = monthAnchor,
            today = today,
            selected = state.focusedDate.asSingle(),
            onDayClick = { state.pickDay(it) },
            onShiftMonth = onShiftMonth,
        )

        // The time. It belongs to the due date, so it is offered only
        // when there is a date to attach it to.
        if (state.target == SchedulingTarget.DUE_DATE && state.date != null) {
            SchedulingTimeControl(
                dueTime = state.time,
                timeState = timeState,
                onAddTime = { state.time = LocalTime(timeState.hour, timeState.minute) },
                onClearTime = { state.time = null },
            )
        }
    }
}

/**
 * The time input, read back as a time.
 *
 * The input's first emission is not a user edit — it is the value the input
 * was created with — so it is skipped: a task without a time must not grow
 * one just because the sheet was opened.
 */
@Composable
private fun rememberSchedulingTimeState(
    dueTime: LocalTime?,
    onTimeChanged: (LocalTime) -> Unit,
): TimePickerState {
    val timeState =
        rememberTimePickerState(
            initialHour = dueTime?.hour ?: 9,
            initialMinute = dueTime?.minute ?: 0,
            // The app shows 24-hour times everywhere else, so the input does too.
            is24Hour = true,
        )
    var timeEdited by remember { mutableStateOf(false) }
    LaunchedEffect(timeState.hour, timeState.minute) {
        if (timeEdited) {
            onTimeChanged(LocalTime(timeState.hour, timeState.minute))
        } else {
            timeEdited = true
        }
    }
    return timeState
}

/**
 * The three facts of the sheet, as rows.
 *
 * The focused one is marked, and tapping a row focuses it: the calendar and the
 * actions then act on that fact, so the row is the way to say *which* date the
 * sheet is editing.
 */
@Composable
private fun SchedulingFacts(
    state: SchedulingState,
    onFocus: (SchedulingTarget) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SchedulingFactRow(
            label = stringResource(Res.string.scheduling_due_date_label),
            value =
                if (state.deferred) {
                    stringResource(Res.string.someday_label)
                } else {
                    state.date?.let { localizedDueDateWithTime(it, null) }
                        ?: stringResource(Res.string.not_set)
                },
            focused = state.target == SchedulingTarget.DUE_DATE,
            onFocus = { onFocus(SchedulingTarget.DUE_DATE) },
            rowTestTag = TestTags.TASK_SCHEDULING_DATE_ROW,
        )
        SchedulingFactRow(
            label = stringResource(Res.string.scheduling_due_time_label),
            value = state.time?.toString() ?: stringResource(Res.string.not_set),
            focused = state.target == SchedulingTarget.DUE_DATE,
            onFocus = { onFocus(SchedulingTarget.DUE_DATE) },
            rowTestTag = TestTags.TASK_SCHEDULING_TIME_ROW,
        )
        SchedulingFactRow(
            label = stringResource(Res.string.scheduling_deadline_label),
            value =
                state.deadlineValue?.let { localizedDueDateWithTime(it, null) }
                    ?: stringResource(Res.string.not_set),
            focused = state.target == SchedulingTarget.DEADLINE,
            onFocus = { onFocus(SchedulingTarget.DEADLINE) },
            rowTestTag = TestTags.TASK_SCHEDULING_DEADLINE_ROW,
        )
    }
}

/**
 * The presets: `Сегодня`, `Завтра` and `Когда-нибудь`.
 *
 * «Сегодня» and «Завтра» set the fact the sheet is editing — the due date or
 * the deadline, whichever the sheet is focused on — because a preset that
 * always sets the due date would put tomorrow's due date on the screen while
 * the deadline is what the user is picking. «Когда-нибудь» belongs to the due
 * date alone: `someday` means *no date*, so it cannot mean "no deadline".
 */
@Composable
private fun SchedulingPresets(
    today: LocalDate,
    focusedDate: LocalDate?,
    someday: Boolean,
    target: SchedulingTarget,
    onPickDate: (LocalDate) -> Unit,
    onToggleSomeday: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = focusedDate == today,
            onClick = { onPickDate(today) },
            label = { Text(stringResource(Res.string.preset_today)) },
            modifier = Modifier.testTag(TestTags.TASK_SCHEDULING_TODAY_CHIP),
        )
        FilterChip(
            selected = focusedDate == tomorrow(today),
            onClick = { onPickDate(tomorrow(today)) },
            label = { Text(stringResource(Res.string.preset_tomorrow)) },
            modifier = Modifier.testTag(TestTags.TASK_SCHEDULING_TOMORROW_CHIP),
        )
        if (target == SchedulingTarget.DUE_DATE) {
            FilterChip(
                selected = someday,
                onClick = {
                    // The preset is a toggle: the second tap takes the task out
                    // of the someday bucket and leaves it with no date at all —
                    // it does not invent one.
                    onToggleSomeday(!someday)
                },
                label = { Text(stringResource(Res.string.someday_label)) },
                modifier = Modifier.testTag(TestTags.TASK_SCHEDULING_SOMEDAY_CHIP),
            )
        }
    }
}

/**
 * The month grid, with the month it shows.
 *
 * The grid is the app's own, not Material3's `DatePicker`: the Material calendar
 * is a 600 dp block that pushes the action row off a 360×800 screen.
 */
@Composable
private fun SchedulingCalendar(
    monthAnchor: LocalDate,
    today: LocalDate,
    selected: ImmutableSet<LocalDate>,
    onDayClick: (LocalDate) -> Unit,
    onShiftMonth: (Int) -> Unit,
) {
    MonthCalendarHeader(
        monthAnchor = monthAnchor,
        onPrevious = { onShiftMonth(-1) },
        onNext = { onShiftMonth(1) },
        monthTitleTag = TestTags.TASK_SCHEDULING_MONTH_TITLE,
        previousMonthTag = TestTags.TASK_SCHEDULING_PREV_MONTH,
        nextMonthTag = TestTags.TASK_SCHEDULING_NEXT_MONTH,
    )
    WeekdayHeaderRow()
    MonthCalendarGrid(
        monthAnchor = monthAnchor,
        today = today,
        selected = selected,
        onDayClick = onDayClick,
        dayTestTag = { day -> TestTags.taskSchedulingDay(isoLocalDate(day)) },
    )
}

/**
 * The time of the due date.
 *
 * The input is behind a button until there is a time to show: a time that is
 * always on screen is a time the user has to dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulingTimeControl(
    dueTime: LocalTime?,
    timeState: TimePickerState,
    onAddTime: () -> Unit,
    onClearTime: () -> Unit,
) {
    if (dueTime == null) {
        TextButton(
            onClick = onAddTime,
            modifier = Modifier.testTag(TestTags.TASK_SCHEDULING_ADD_TIME),
        ) { Text(stringResource(Res.string.add_time)) }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeInput(
                state = timeState,
                modifier = Modifier.testTag(TestTags.TASK_SCHEDULING_TIME_INPUT),
            )
            IconButton(
                onClick = onClearTime,
                modifier = Modifier.testTag(TestTags.TASK_SCHEDULING_CLEAR_TIME),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.clear_time),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The action bar, pinned to the bottom of the sheet.
 *
 * It is *not* at the end of the scrolling body: a body with a calendar in it
 * is taller than a 360×800 screen, and an action row that scrolls away is an
 * action row nobody can reach.
 */
@Composable
private fun SchedulingActionBar(
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onClear,
            modifier = Modifier.testTag(TestTags.TASK_SCHEDULING_CLEAR),
        ) { Text(stringResource(Res.string.action_clear)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(TestTags.TASK_SCHEDULING_CANCEL),
            ) { Text(stringResource(Res.string.action_cancel)) }
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(TestTags.TASK_SCHEDULING_CONFIRM),
            ) { Text(stringResource(Res.string.action_save)) }
        }
    }
}

/**
 * One fact of the sheet: its name, its value, and whether it is the one the
 * calendar and the actions are editing.
 */
@Composable
private fun SchedulingFactRow(
    label: String,
    value: String,
    focused: Boolean,
    onFocus: () -> Unit,
    rowTestTag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(rowTestTag)
                .mapTestTagsAsResourceIds()
                .clickable { onFocus() }
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.weight(1f),
        )
        if (focused) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** The day after [date]. */
private fun tomorrow(date: LocalDate): LocalDate = date.plus(1, DateTimeUnit.DAY)

/** The set that holds the one picked day, or nothing. */
private fun LocalDate?.asSingle(): ImmutableSet<LocalDate> = this?.let { persistentSetOf(it) } ?: persistentSetOf()
