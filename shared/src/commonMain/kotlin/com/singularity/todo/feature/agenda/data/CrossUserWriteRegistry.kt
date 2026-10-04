package com.singularity.todo.feature.agenda.data

/**
 * The sanctioned cross-profile writes in this codebase — one per entry, and each
 * one is a hole in [com.singularity.todo.core.repository.assertCanWrite].
 *
 * ## Why this is a registry and not a rule
 *
 * The obvious enforcement — "a repository method that takes a `userId` and
 * writes must call `assertCanWrite`" — was written and measured. It matches
 * **eight** methods across the data layer, and **seven are legitimate**:
 *
 * ```
 * ReminderRepositoryImpl::delete(id, userId)
 * ProjectRemindersRepositoryImpl::delete(id, userId)
 * TimeTrackingRepositoryImpl::startEntry / createManualEntry / updateNote
 * ProposalRepositoryImpl::refreshStatus / retract
 * ```
 *
 * These are user-*scoped* writes: they pass the user down to a DAO query that
 * is already `WHERE user_id = :userId`. Passing a userId to a scoped DAO is not
 * a cross-user write. Only [SavedAgendaViewsRepositoryImpl.duplicateForProfile]
 * writes a row belonging to somebody *other* than the current profile, because
 * that is the operation's entire purpose.
 *
 * Nothing syntactic separates the two: both take a `userId` and both call
 * `upsert`. Distinguishing them needs to know what the DAO query does with the
 * value, which is the PSI-level rule
 * `2026-09-27-write-layer-soundness.md` ledger #18 already deferred as
 * disproportionate. Allowing those seven here would make the list meaningless —
 * it would grow with every new scoped-DAO method and could never fail on a real
 * violation.
 *
 * ## What this registry does buy
 *
 * - The single exception is greppable instead of living only in a KDoc.
 * - [CrossUserWriteRegistryTest] pins the count at one and fails if the named
 *   method disappears, so the entry cannot rot into a fiction.
 * - The next person to write a cross-user write finds this file by name, which
 *   is the point.
 */
object CrossUserWriteRegistry {
    const val SAVED_AGENDA_DUPLICATE_FOR_PROFILE: String =
        "SavedAgendaViewsRepositoryImpl.duplicateForProfile"

    /**
     * Every sanctioned cross-user write, as `File.method`.
     *
     * The exhaustive list of places that intentionally bypass
     * [com.singularity.todo.core.repository.assertCanWrite]. Adding an entry
     * means taking responsibility for a hole in the write guard — the KDoc on
     * the method must say why the guard does not apply.
     */
    val sanctioned: Set<String> = setOf(SAVED_AGENDA_DUPLICATE_FOR_PROFILE)
}
