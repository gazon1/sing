package com.singularity.todo.feature.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.feature.calendar.domain.logic.visibleRange
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskStatus
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.components.calendar.groupedByDate
import com.singularity.todo.feature.calendar.presentation.screen.CalendarContent
import com.singularity.todo.feature.calendar.presentation.state.CalendarIntent
import com.singularity.todo.feature.calendar.presentation.state.CalendarUiState
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * @Preview composables for the Calendar screen.
 * Follows the preview-with-koin pattern: manual VM state construction, no Koin.
 */
private object CalendarPreviewData {
    private val today = todayInSystemZone()
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
        CalendarTaskUi(TaskId.generate(), "Предложить олегу прокатиться на велах", d(18), isAllDay = true, isRecurring = true),
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
            today = today,
            selectedDate = anchor,
            isMiniCalendarOpen = isMiniOpen,
            headerLabel = "September 2026",
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@Composable
private fun CalendarContentPreview(
    name: String = "Month",
    state: CalendarUiState = CalendarPreviewData.loadedState(CalendarViewMode.MONTH),
) {
    MaterialTheme {
        CalendarContent(
            state = state,
            onIntent = {},
            today = todayInSystemZone(),
        )
    }
}

@Composable
private fun CalendarPreviewMonth() = CalendarContentPreview(
    name = "Month",
    state = CalendarPreviewData.loadedState(CalendarViewMode.MONTH),
)

@Composable
private fun CalendarPreviewWeek() = CalendarContentPreview(
    name = "Week",
    state = CalendarPreviewData.loadedState(CalendarViewMode.WEEK),
)

@Composable
private fun CalendarPreview4Days() = CalendarContentPreview(
    name = "4 Days",
    state = CalendarPreviewData.loadedState(CalendarViewMode.FOUR_DAYS),
)

@Composable
private fun CalendarPreviewDay() = CalendarContentPreview(
    name = "Day",
    state = CalendarPreviewData.loadedState(CalendarViewMode.DAY),
)

@Composable
private fun CalendarPreviewMiniOpen() = CalendarContentPreview(
    name = "MiniOpen",
    state = CalendarPreviewData.loadedState(CalendarViewMode.MONTH, isMiniOpen = true),
)
