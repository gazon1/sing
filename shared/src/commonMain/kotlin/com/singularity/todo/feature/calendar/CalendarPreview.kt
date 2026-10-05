package com.singularity.todo.feature.calendar

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.calendar.domain.logic.visibleRange
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.components.calendar.groupedByDate
import com.singularity.todo.feature.calendar.presentation.screen.CalendarContent
import com.singularity.todo.feature.calendar.presentation.state.CalendarUiState
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * @Preview composables for the Calendar screen.
 * Follows the preview-with-koin pattern: manual VM state construction, no Koin.
 */
private object CalendarPreviewData {
    private val sep = kotlinx.datetime.Month.SEPTEMBER

    private fun d(day: Int) = LocalDate(2026, sep, day)

    val sampleTasks: List<CalendarTaskUi> = listOf(
        CalendarTaskUi(TaskId.generate(), "Выбрать куда пойду в fitmost", d(16), isAllDay = true, isRecurring = true),
        CalendarTaskUi(TaskId.generate(), "Забрать заказ из озон", d(16), isAllDay = true),
        CalendarTaskUi(TaskId.generate(), "Погулять полчаса быстрым шагом", d(16), isAllDay = true, isRecurring = true),
        CalendarTaskUi(TaskId.generate(), "Ещё одна задача 16 числа", d(16), isAllDay = true),
        CalendarTaskUi(TaskId.generate(), "Делаю пилинг перчаткой в душе", d(17), isAllDay = true, isRecurring = true),
        CalendarTaskUi(TaskId.generate(), "Купить хлеб", d(18), isAllDay = true, isRecurring = true),
        CalendarTaskUi(TaskId.generate(), "Накачать статей из miniflux", d(18), isAllDay = true, isRecurring = true),
        CalendarTaskUi(
            TaskId.generate(),
            "Предложить олегу прокатиться на велах",
            d(18),
            isAllDay = true,
            isRecurring = true,
        ),
        CalendarTaskUi(TaskId.generate(), "Позвонить родителям", d(19), isAllDay = true, isRecurring = true),
    )

    fun loadedState(
        viewMode: CalendarViewMode = CalendarViewMode.MONTH,
        isMiniOpen: Boolean = false,
    ): CalendarUiState.Loaded {
        val anchor = d(16)
        return CalendarUiState.Loaded(
            anchor = anchor,
            viewMode = viewMode,
            visibleDates = visibleRange(anchor, viewMode),
            tasksByDate = sampleTasks.groupedByDate(),
            // A fixed date, not the host's today. A preview that highlights
            // "now" changes what it renders from one day to the next, which is
            // the same host-dependence #91 is about, in a file nobody thought to
            // check: this used to be `private val today = todayInSystemZone()` on
            // the data holder above.
            today = LocalDate(2026, 9, 16),
            selectedDate = anchor,
            isMiniCalendarOpen = isMiniOpen,
            headerLabel = "September 2026",
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@Composable
private fun CalendarContentPreview(state: CalendarUiState = CalendarPreviewData.loadedState(CalendarViewMode.MONTH)) {
    // The theme wrapper, not a bare MaterialTheme: the calendar paints its own
    // palette from the colour scheme, and a baseline scheme here would show a
    // screen the app never produces.
    PreviewThemed(darkTheme = false, useSurface = true) {
        CalendarContent(
            state = state,
            onIntent = {},
        )
    }
}

@Preview
@Composable
private fun CalendarPreviewMonth() = CalendarContentPreview(
    state = CalendarPreviewData.loadedState(CalendarViewMode.MONTH),
)

@Preview
@Composable
private fun CalendarPreviewWeek() = CalendarContentPreview(
    state = CalendarPreviewData.loadedState(CalendarViewMode.WEEK),
)

@Preview
@Composable
private fun CalendarPreview4Days() = CalendarContentPreview(
    state = CalendarPreviewData.loadedState(CalendarViewMode.FOUR_DAYS),
)

@Preview
@Composable
private fun CalendarPreviewDay() = CalendarContentPreview(
    state = CalendarPreviewData.loadedState(CalendarViewMode.DAY),
)

@Preview
@Composable
private fun CalendarPreviewMiniOpen() = CalendarContentPreview(
    state = CalendarPreviewData.loadedState(CalendarViewMode.MONTH, isMiniOpen = true),
)
