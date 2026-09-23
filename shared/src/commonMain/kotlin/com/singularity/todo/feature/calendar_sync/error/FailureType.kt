package com.singularity.todo.feature.calendar_sync.error

/**
 * Failure type for UI-tailored error messages and recovery actions.
 *
 * Passed to [CalendarSyncStatus.Failed] so the settings screen can show
 * context-specific messaging ("Open Settings" / "Re-pick calendar app" / "Retry").
 */
enum class FailureType {
    /** READ_CALENDAR or WRITE_CALENDAR permission was revoked. */
    PermissionRevoked,

    /** The target calendar was deleted from the system. */
    CalendarNotFound,

    /** The user-selected calendar app is no longer installed. */
    CalendarAppMissing,

    /** A transient I/O or ContentResolver error — retry may succeed. */
    Transient,

    /** Network connectivity error. */
    Network,

    /** Unknown error — fallback. */
    Unknown,
}
