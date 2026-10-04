package com.singularity.todo.core.ui

import java.util.Locale

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
 */

/**
 * Elapsed milliseconds as a clock: "4:07", or "1:02:03" once past an hour.
 *
 * Truncates to whole seconds — this is a duration display, not a stopwatch.
 */
fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
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

/**
 * Bytes as a human-readable size: "500 B", "1 KB", "12 MB", "2.0 GB".
 *
 * Note the asymmetry, which is pre-existing and deliberate: KB and MB use integer
 * division and therefore truncate (1.5 MB renders as "1 MB"), while GB uses one
 * decimal. Rounding consistently would change the size shown next to every
 * attachment and backup, which is a visual decision rather than a de-duplication
 * — so the quirk is preserved and pinned by `FormattersTest`.
 */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
}
