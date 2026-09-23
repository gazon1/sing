package com.singularity.todo.feature.calendar_sync.sync

/**
 * Source of a calendar sync trigger.
 *
 * Used by [CalendarSyncOrchestrator] to pick the strongest trigger when multiple
 * fire in quick succession, and to carry that information to the WorkManager worker.
 *
 * Inspired by Tasks.org `SyncAdapter` `SyncSource` enum — adapted for a one-way
 * Task → system-calendar sync pipeline.
 *
 * @param showIndicator Whether to show a sync indicator in the UI while this sync runs.
 * @param immediate Whether this sync should bypass debounce and run immediately.
 */
enum class SyncSource(val showIndicator: Boolean, val immediate: Boolean) {
    /** Periodic background trigger from the refresh worker. May be skipped when nothing changed. */
    Periodic(showIndicator = false, immediate = false),

    /**
     * User toggled sync on, picked a calendar, or picked a calendar app.
     * Always immediate — user expects instant feedback.
     */
    ConfigChanged(showIndicator = true, immediate = true),

    /**
     * A task was edited (title, due date, completion, trashed, color).
     * Not immediate — debounce window coalesces burst edits.
     */
    TaskDirty(showIndicator = true, immediate = false),

    /** User explicitly tapped "Sync now". Always immediate, always shows indicator. */
    Manual(showIndicator = true, immediate = true),

    /** App resumed from background. Low priority — skip if nothing changed. */
    AppResumed(showIndicator = false, immediate = false),
    ;

    /**
     * Picks the strongest of two concurrent triggers.
     *
     * Priority: `showIndicator` beats non-indicator; otherwise `immediate` beats deferred.
     * This ensures the most user-visible trigger always wins.
     */
    fun upgrade(other: SyncSource): SyncSource = when {
        this.showIndicator && !other.showIndicator -> this
        !this.showIndicator && other.showIndicator -> other
        else -> if (this.immediate) this else other
    }
}
