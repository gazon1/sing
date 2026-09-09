package com.singularity.todo.feature.tasks

import androidx.compose.material3.MaterialTheme
import kotlinx.datetime.toInstant
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.singularity.todo.core.ui.components.DueVisualState

/**
 * Formatters for AI action results and UI rendering helpers.
 * Kept out of Composables so they can be unit-tested without launching a Compose runtime.
 *
 * Lives in the `tasks` package because it knows about [AiActionResult] — a
 * task-domain concept that core UI must not depend on.
 */
object TasksFormatters {

    /**
     * Returns the background and foreground colors for a due-date chip,
     * based on its visual state.
     *
     * @.compose Must be called from a @Composable context — reads [MaterialTheme.colorScheme].
     */
    @Composable
    @ReadOnlyComposable
    internal fun dueChipColors(state: DueVisualState?): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
        return when (state) {
            DueVisualState.Overdue ->
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            DueVisualState.Today ->
                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            else ->
                MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
}

/**
 * Parses a `"HH:mm"` time string into [kotlinx.datetime.LocalTime].
 * Returns null if the string is null or malformed.
 */
internal fun parseDueTime(time: String?): kotlinx.datetime.LocalTime? {
    return time?.let {
        runCatching {
            val parts = it.split(":")
            kotlinx.datetime.LocalTime(parts[0].toInt(), parts[1].toInt())
        }.getOrNull()
    }
}

/**
 * Computes the instant at which a reminder should fire for a task with the given
 * due date/time, applying the [ReminderOffset] offset.
 *
 * Pure: depends only on its inputs, no side effects, no `Clock.now()`.
 *
 * @param dueDate  the task's due date, or null if no date is set.
 * @param dueTime  the task's due time as `"HH:mm"`, or null (defaults to 12:00).
 * @param offset   how many minutes before the due moment to fire.
 * @param zone     the time zone to use for date→instant conversion.
 * @return epoch milliseconds at which the reminder should fire.
 */
internal fun dueInstant(
    dueDate: kotlinx.datetime.LocalDate?,
    dueTime: String?,
    offset: com.singularity.todo.feature.settings.ReminderOffset,
    zone: kotlinx.datetime.TimeZone,
    nowEpochMs: Long = System.currentTimeMillis(),
): Long {
    if (dueDate == null) return nowEpochMs
    val parsed = parseDueTime(dueTime)
    val hour = parsed?.hour ?: 12
    val minute = parsed?.minute ?: 0
    val ldt = kotlinx.datetime.LocalDateTime(dueDate.year, dueDate.month, dueDate.day, hour, minute)
    val base = ldt.toInstant(zone).toEpochMilliseconds()
    return base - offset.minutes * 60_000L
}

/**
 * Pure formatter for AI action results.
 */
internal fun formatAiResult(result: AiActionResult): String = when (result) {
    is AiActionResult.RefineTitle -> "Refined title: ${result.newTitle}"
    is AiActionResult.GenerateDescription -> "Description: ${result.description}"
    is AiActionResult.GenerateChecklist -> "Checklist:\n" + result.steps.joinToString("\n") { "- $it" }
    is AiActionResult.DecomposeTask -> "Sub-tasks:\n" + result.subTasks.joinToString("\n") { "- $it" }
    is AiActionResult.PickTime -> "Suggested time: ${result.suggestedTime}"
    is AiActionResult.Error -> "Error: ${result.message}"
}
