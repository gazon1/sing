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
         * Stored value for "no triggers are enabled".
         *
         * ## Why the empty set needs its own encoding
         *
         * A blank string used to mean "all of them" — the default a fresh row gets — and
         * `toCsv(emptySet())` also produced `""`. The two were therefore indistinguishable:
         * explicitly disabling every trigger, saving, and reloading silently turned all of
         * them back on. Since no [SyncTrigger] is named `none`, that token is free to carry
         * the empty set without ambiguity.
         */
        private const val NONE = "none"

        /**
         * Parses the stored CSV.
         *
         * An absent value (`null`) or a blank string means "all of them", which is the
         * default a fresh row gets; the literal [NONE] means the empty set. Unrecognised
         * names are dropped rather than throwing: a trigger added by a newer build and
         * removed again, or a hand-edited row, should cost one missing trigger and not a
         * sync that cannot start.
         */
        fun parseCsv(raw: String?): Set<SyncTrigger> = when {
            raw == null || raw.isBlank() -> entries.toSet()

            raw == NONE -> emptySet()

            else -> raw.split(',')
                .mapNotNull { name -> entries.find { it.name == name } }
                .toSet()
        }

        /**
         * Writes the CSV, sorted by declaration order so the stored value is stable and a
         * diff of the preferences file is readable.
         */
        fun toCsv(triggers: Set<SyncTrigger>): String = if (triggers.isEmpty()) {
            NONE
        } else {
            triggers.sortedBy { it.ordinal }.joinToString(",") { it.name }
        }
    }
}
