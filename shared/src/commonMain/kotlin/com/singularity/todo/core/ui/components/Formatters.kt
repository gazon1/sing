package com.singularity.todo.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Pure-Kotlin presentation helpers used by [core/ui/components]. No Compose
 * runtime — safe to unit-test directly from `commonTest` without Robolectric.
 *
 * Feature-specific formatters (e.g. `formatAiResult` in `feature/tasks`) live
 * next to their domain types to avoid core → feature back-references.
 */

/** Hex → Compose [Color]. [0L] means "no color" / unspecified. */
internal fun hexColor(value: Long): Color = if (value == 0L) Color.Unspecified else Color(value)

/** Priority badge color by ordinal index. `4 == None` returns unspecified. */
internal fun priorityColorByIndex(index: Int): Color = when (index) {
    0 -> Color(0xFF4CAF50)

    // Low    - green
    1 -> Color(0xFFFF9800)

    // Medium - orange
    2 -> Color(0xFFF44336)

    // High   - red
    3 -> Color(0xFFE91E63)

    // Urgent - pink
    else -> Color.Unspecified
}

/**
 * Visual state for the due-date chip on the task detail screen.
 * Used to determine background / text colour (overdue = error, today = warning, future = neutral).
 */

/**
 * Returns the background and foreground colors for a due-date chip,
 * based on its visual state.
 *
 * @.compose Must be called from a @Composable context — reads [MaterialTheme.colorScheme].
 */
@Composable
@ReadOnlyComposable
internal fun dueChipColors(state: DueVisualState?): Pair<Color, Color> = when (state) {
    DueVisualState.Overdue ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer

    DueVisualState.Today ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer

    else ->
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}

internal enum class DueVisualState {
    /** Past due date. */
    Overdue,

    /** Due today. */
    Today,

    /** Future date. */
    Future,
}

/**
 * Result of [formatDueChip]. Contains the display text and the visual state
 * for the chip background / tint.
 */
internal data class DueChipModel(val text: String, val state: DueVisualState)

/**
 * Formats a due-date and optional time into the chip label shown on the
 * task detail screen, e.g. `"Today, 09:00"` or `"Tomorrow"`.
 * Returns `null` when [date] is `null` (no due date set).
 *
 * [today] must be the current local date (passed in to avoid hardcoding `Clock` here —
 * pure functions must not call `Clock` directly).
 */
internal fun formatDueChip(date: LocalDate?, time: String?, today: LocalDate): DueChipModel? {
    if (date == null) return null

    val datePart = when (date) {
        today -> "Today"
        today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
        today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
        else -> date.toString() // "YYYY-MM-DD" — Composable layer can re-format
    }

    val fullText = if (time.isNullOrBlank()) datePart else "$datePart, $time"

    val state = when {
        date < today -> DueVisualState.Overdue
        date == today -> DueVisualState.Today
        else -> DueVisualState.Future
    }

    return DueChipModel(text = fullText, state = state)
}

/**
 * Formats a reminder's fire-at instant into a human-readable label.
 *
 * - `"At 9:00 AM"` — when [offsetMinutes] is 0 (AT_DUE)
 * - `"15 min before due"` — when [offsetMinutes] is non-zero and no absolute time
 * - `"Tomorrow at 9:00 AM"` / `"Today at 9:00 AM"` / `"Sep 8 at 9:00 AM"` — absolute datetime
 *
 * [fireAt] is epoch millis. [zone] is the user's local timezone.
 */
internal fun formatReminderTime(fireAt: Long, offsetMinutes: Int, zone: kotlinx.datetime.TimeZone): String {
    val instant = Instant.fromEpochMilliseconds(fireAt)
    val local = instant.toLocalDateTime(zone)
    val timeStr = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"

    return if (offsetMinutes == 0) {
        // AT_DUE — just show the absolute time
        "${local.day} ${shortMonth(local.month)} at $timeStr"
    } else {
        // Relative offset — show how many minutes/hours/days before due
        val mins = kotlin.math.abs(offsetMinutes)
        when {
            mins < 60 -> "$mins min before due"
            mins < 1440 -> "${mins / 60} hour${if (mins >= 120) "s" else ""} before due"
            else -> "${mins / 1440} day${if (mins >= 2880) "s" else ""} before due"
        }
    }
}

private fun shortMonth(month: kotlinx.datetime.Month): String = when (month) {
    kotlinx.datetime.Month.JANUARY -> "Jan"
    kotlinx.datetime.Month.FEBRUARY -> "Feb"
    kotlinx.datetime.Month.MARCH -> "Mar"
    kotlinx.datetime.Month.APRIL -> "Apr"
    kotlinx.datetime.Month.MAY -> "May"
    kotlinx.datetime.Month.JUNE -> "Jun"
    kotlinx.datetime.Month.JULY -> "Jul"
    kotlinx.datetime.Month.AUGUST -> "Aug"
    kotlinx.datetime.Month.SEPTEMBER -> "Sep"
    kotlinx.datetime.Month.OCTOBER -> "Oct"
    kotlinx.datetime.Month.NOVEMBER -> "Nov"
    kotlinx.datetime.Month.DECEMBER -> "Dec"
}

/**
 * Formats `createdAt` and `updatedAt` timestamps for display in the task detail footer.
 *
 * - createdAt: "Created Sep 8"
 * - updatedAt: "Updated 2m ago" (relative), or "Updated Sep 8" if >7 days old
 */
internal fun formatTimestampsRelative(createdAt: Instant, updatedAt: Instant, now: Instant): TimestampsModel {
    val createdStr = formatCreatedDate(createdAt)
    val updatedStr = formatUpdatedRelative(updatedAt, now)
    return TimestampsModel(created = createdStr, updated = updatedStr)
}

internal data class TimestampsModel(val created: String, val updated: String)

private fun formatCreatedDate(instant: Instant): String {
    val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    return "Created ${shortMonth(local.month)} ${local.day}"
}

private fun formatUpdatedRelative(instant: Instant, now: Instant): String {
    val diffMs = now.toEpochMilliseconds() - instant.toEpochMilliseconds()
    val diffMinutes = diffMs / 60_000
    val diffHours = diffMinutes / 60
    val diffDays = diffHours / 24

    return when {
        diffMinutes < 1 -> "Updated just now"

        diffMinutes < 60 -> "Updated ${diffMinutes}m ago"

        diffHours < 24 -> "Updated ${diffHours}h ago"

        diffDays <= 7 -> "Updated ${diffDays}d ago"

        else -> {
            val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            "Updated ${shortMonth(local.month)} ${local.day}"
        }
    }
}
