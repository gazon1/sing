package com.singularity.todo.feature.auth

/**
 * The shape of what an owner-scoped write operation reports about what it did.
 *
 * ## Why an interface rather than a shared base class
 *
 * Extracting a base class would create a conflict with data class mechanics: the
 * inherited properties would not be in the primary constructor, and `copy()` would
 * only carry the declared fields. An interface with default implementations avoids
 * that conflict entirely.
 *
 * ## What lives here vs in the implementing data classes
 *
 * The shared fields (12 data-table counters and 3 sync-table counters) are declared
 * here with default values. The two `childRows` computed property (erase) and
 * `profiles` counter (reassign) stay in their respective classes — they have no
 * counterpart in the other operation.
 *
 * ## Why 17 counters and not 18
 *
 * `tagRefs`, `dependencies`, `checklists`, and `inheritedTagGroups` are child rows
 * counted differently in each operation: erase deletes them explicitly, while
 * reassign moves them implicitly when their parent moves. They are excluded from
 * the interface to keep the semantics honest.
 */
interface OwnerOperationCounts {
    // ── Data rows ───────────────────────────────────────────────────────────

    val tasks: Int
    val notes: Int
    val projects: Int
    val tags: Int
    val tagGroups: Int
    val taskReminders: Int
    val projectReminders: Int
    val timeEntries: Int
    val agendaViews: Int

    // ── Sync rows ───────────────────────────────────────────────────────────

    val outbox: Int
    val deadLetter: Int
    val shadows: Int

    /**
     * Whether the operation affected any row at all.
     *
     * Used to distinguish "there was nothing to do" from "it succeeded silently",
     * which look identical from the outside but require different follow-up
     * messages to the user.
     */
    val isEmpty: Boolean

    /** Outbox + dead letter + shadows, for the log line. */
    val syncTotal: Int
        get() = outbox + deadLetter + shadows

    companion object {
        /** Sum of the 12 shared data/sync counters. */
        fun sum(counts: OwnerOperationCounts): Int = with(counts) {
            tasks + notes + projects + tags + tagGroups +
                taskReminders + projectReminders + timeEntries + agendaViews +
                outbox + deadLetter + shadows
        }
    }
}
