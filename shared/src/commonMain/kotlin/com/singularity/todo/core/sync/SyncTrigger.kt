package com.singularity.todo.core.sync

/**
 * Triggers that can cause a sync to fire.
 *
 * The set is stored per scope in `sync_state.enabled_triggers`, so this is no longer
 * only consulted by the periodic driver.
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
    ;

    companion object {
        /**
         * Parses the stored CSV.
         *
         * A blank string means "all of them", which is the default a fresh row gets.
         * Unrecognised names are dropped rather than throwing: a trigger added by a
         * newer build and removed again, or a hand-edited row, should cost one missing
         * trigger and not a sync that cannot start.
         */
        fun parseCsv(raw: String): Set<SyncTrigger> {
            if (raw.isBlank()) return entries.toSet()
            return raw.split(',')
                .mapNotNull { name -> entries.find { it.name == name } }
                .toSet()
        }

        fun toCsv(triggers: Set<SyncTrigger>): String =
            triggers.sortedBy { it.ordinal }.joinToString(",") { it.name }
    }
}
