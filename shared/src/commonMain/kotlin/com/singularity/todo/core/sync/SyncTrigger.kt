package com.singularity.todo.core.sync

/**
 * Triggers that can cause a sync to fire.
 * Used by [AutoSync] to decide whether to run based on user preferences.
 */
enum class SyncTrigger {
    /** Entity was created — push immediately. */
    Created,

    /** Entity was updated — push immediately. */
    Updated,

    /** App moved to foreground. */
    AppResumed,

    /** App moved to background. */
    AppSuspended,

    /** Periodic scheduler fired. */
    Scheduled,

    /** Network became available (WiFi/cellular connected). */
    NetworkConnected,
}
