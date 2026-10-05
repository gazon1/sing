package com.singularity.todo.core.ui

import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/*
 * Pure display formatters shared by more than one screen.
 *
 * These existed as four separate private copies. `formatElapsed` and
 * `formatDuration` were byte-identical duplicates of each other in
 * `LogbookSection.kt` and `TimeTrackingSection.kt`; `formatFileSize` had two
 * variants that were **not** equivalent — the one in `AttachmentDomain` handles
 * gigabytes, the one in `BackupFormatters` stopped at megabytes and would render a
 * 2 GB backup as "2048.0 MB". The more complete implementation won, so a large
 * backup file now displays in GB. That is a behaviour change and it is the
 * intended one.
 *
 * They live here rather than in a feature because "how do I render 3725 ms" is a
 * question three features were each answering separately. Nothing here touches
 * state, so it is testable without a Compose runtime.
 *
 * ## No `String.format`, and why that is not only about `Locale`
 *
 * Every number here is padded by hand. The reason is not that `java.util.Locale`
 * is unavailable off the JVM — it is that `"%.1f".format(x)` with no `Locale`
 * argument formats in the *device's* locale, so a user whose separator is a comma
 * saw "2,0 GB" on an attachment while the same value read "2.0 GB" everywhere
 * else, and the file-size column became unparseable. The zero-padding helpers below
 * cannot have a locale, so the question does not arise.
 */

/**
 * Left-pads to two digits: `7` → `"07"`, `59` → `"59"`.
 *
 * Two overloads rather than a generic `T : Number` because [kotlin.String.padStart]
 * is the only operation involved and a generic would buy a `Number.toString()` that
 * has to be re-implemented per type anyway.
 */
private fun Int.padded(): String = toString().padStart(2, '0')

private fun Long.padded(): String = toString().padStart(2, '0')

/**
 * Elapsed milliseconds as a clock: "4:07", or "1:02:03" once past an hour.
 *
 * Truncates to whole seconds — this is a duration display, not a stopwatch.
 *
 * The leading field is bare and the ones after it are zero-padded, which is what
 * `"%d:%02d"` / `"%d:%02d:%02d"` produced: an elapsed of 7 seconds reads "0:07", not
 * "00:07". Padding the first field too looked tidier and was wrong — a stopwatch
 * display that always showed two digits for minutes drew a false distinction between
 * "0:07" and "00:07" that the number does not contain.
 */
fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.padded()}:${seconds.padded()}"
    } else {
        "$minutes:${seconds.padded()}"
    }
}

/**
 * Milliseconds as a coarse span: "45m", "2h", "2h 30m".
 *
 * Deliberately lossy — the pomodoro and statistics screens use it for totals where
 * seconds are noise. Use [formatElapsed] when the seconds matter.
 */
fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 1000 / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

/** The unit the size formatter steps up at — and the only branch that rounds. */
private const val GIBIBYTE = 1024L * 1024 * 1024

/**
 * Bytes as a human-readable size: "500 B", "1 KB", "12 MB", "2.0 GB".
 *
 * Note the asymmetry, which is pre-existing and deliberate: KB and MB use integer
 * division and therefore truncate (1.5 MB renders as "1 MB"), while GB uses one
 * decimal. Rounding consistently would change the size shown next to every
 * attachment and backup, which is a visual decision rather than a de-duplication
 * — so the quirk is preserved and pinned by `FormattersTest`.
 *
 * The gigabyte branch rounds to one decimal in integer arithmetic, with the quotient
 * and the remainder handled separately so `bytes * 10` cannot overflow a `Long`, and
 * `%10` on the result yields the fractional digit without a second division.
 */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"

    bytes < 1024 * 1024 -> "${bytes / 1024} KB"

    bytes < GIBIBYTE -> "${bytes / (1024 * 1024)} MB"

    else -> {
        val tenths = (bytes / GIBIBYTE) * 10 + ((bytes % GIBIBYTE) * 10 + GIBIBYTE / 2) / GIBIBYTE
        "${tenths / 10}.${tenths % 10} GB"
    }
}

/**
 * [month] as a short English name: "Jan" … "Dec".
 *
 * Derived from the enum constant's own name rather than a hand-kept table, so a
 * month added to the enum cannot be missing from this list. The trade is that the
 * result is English on every device: the alternative is a localised month name from
 * a platform API, and this codebase already renders English month abbreviations on
 * the task logbook, so a second screen answering the same question in the user's
 * language would be the inconsistency.
 */
fun monthAbbreviation(month: Month): String =
    month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

/**
 * [instant] as "Nov 5, 14:30" in [zone] — the shape a time-tracking entry list
 * shows, month abbreviation, day of month, and 24-hour clock.
 */
fun formatMonthDayTime(
    instant: Instant,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val local = instant.toLocalDateTime(zone)
    return "${monthAbbreviation(local.month)} ${local.day}, ${local.hour.padded()}:${local.minute.padded()}"
}
