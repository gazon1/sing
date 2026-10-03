package com.singularity.todo.core.ui.detail

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pure formatter for relative "saved" timestamp — "Saved just now", "Saved 2m ago", etc.
 *
 * Used by detail screens to show a live-updating relative time since last edit,
 * replacing hardcoded string interpolation in UI code.
 *
 * @param clock Current clock instance (inject for testability).
 * @param lastEditedAt The instant of the last edit, or `null` if never edited.
 * @return Human-readable relative string, or empty string if `lastEditedAt` is `null`.
 */
fun formatSavedRelative(clock: Clock, lastEditedAt: Instant?): String {
    if (lastEditedAt == null) return ""
    val now = clock.now()
    val diffMs = now.toEpochMilliseconds() - lastEditedAt.toEpochMilliseconds()
    val diffMinutes = diffMs / 60_000
    val diffHours = diffMinutes / 60

    return when {
        diffMs < 5_000 -> "Saved just now"
        diffMinutes < 1 -> "Saved ${diffMs / 1_000}s ago"
        diffMinutes < 60 -> "Saved ${diffMinutes}m ago"
        diffHours < 24 -> "Saved ${diffHours}h ago"
        else -> "Saved"
    }
}
