package com.singularity.todo.core.database

import androidx.room3.Dao
import androidx.room3.Query

/**
 * Every owner-scoped `UPDATE` that moves rows from one owner to another, in one place.
 *
 * ## Why this exists rather than the delete DAO with UPDATE instead of DELETE
 *
 * Because the two operations do not agree on what one row is. A delete takes a *set* of
 * scopes; a transfer has to say which scope becomes which, and the pairing is the whole
 * of it. `prof/a` must become `prof/b`, not merely "some scope of b's". So the data
 * statements take one `(from, to)` pair at a time and the caller walks the pairs the
 * `OwnerIdMapping` composed. That is a loop rather than one statement per table, and the
 * loop is the honest shape: it costs one statement per (scope, table) instead of one
 * per table, in exchange for every statement remaining a single exact-equality match
 * that can be read without simulating it.
 *
 * ## Why nothing here uses a LIKE pattern
 *
 * Same reason as [OwnerEraseDao], and the same consequence. A profile survives only as
 * the shape of the id, so a suffix match could not tell `prof/u1` from an account whose
 * own id is `eu1` — and here it would be worse than deleting the wrong rows: it would
 * hand another owner's rows to the account that just signed in. Every statement below is
 * `SET user_id = :to WHERE user_id = :from`, which is the only form that cannot.
 *
 * ## Why profiles and the sync tables are not walked pairwise
 *
 * They are keyed by the **bare owner id**, not by a composed scope. A profile row's
 * `user_id` is the account, and `sync_outbox` / `sync_dead_letter` / `sync_shadow` carry
 * `SyncScope.ownerId`, which is the account id and not `"profile/owner"` (see the note on
 * [com.singularity.todo.core.sync.SyncShadowDao.deleteForOwner]). Those three-plus-one
 * move once, from owner to owner.
 *
 * ## Who calls what, in what order
 *
 * [com.singularity.todo.feature.auth.OwnerScopedReassigner] is the only caller, and it
 * moves the data rows first and `profiles.user_id` **last** — the profile row is what
 * makes rows visible to an owner, so flipping it first would expose a half-moved set.
 *
 * See ADR 2026-10-06-a-profile-is-owned-and-an-erase-resolves-its-ids-first.
 */
@Dao
@Suppress("TooManyFunctions")
interface OwnerReassignDao {

    // ── Data rows: walked one scoped-id pair at a time ──────────────────────

    @Query("UPDATE tasks SET user_id = :to WHERE user_id = :from")
    suspend fun reassignTasks(from: String, to: String): Int

    @Query("UPDATE notes SET user_id = :to WHERE user_id = :from")
    suspend fun reassignNotes(from: String, to: String): Int

    @Query("UPDATE projects SET user_id = :to WHERE user_id = :from")
    suspend fun reassignProjects(from: String, to: String): Int

    @Query("UPDATE tags SET user_id = :to WHERE user_id = :from")
    suspend fun reassignTags(from: String, to: String): Int

    @Query("UPDATE tag_groups SET user_id = :to WHERE user_id = :from")
    suspend fun reassignTagGroups(from: String, to: String): Int

    @Query("UPDATE task_reminders SET user_id = :to WHERE user_id = :from")
    suspend fun reassignTaskReminders(from: String, to: String): Int

    @Query("UPDATE project_reminders SET user_id = :to WHERE user_id = :from")
    suspend fun reassignProjectReminders(from: String, to: String): Int

    @Query("UPDATE time_entries SET user_id = :to WHERE user_id = :from")
    suspend fun reassignTimeEntries(from: String, to: String): Int

    @Query("UPDATE agenda_views SET user_id = :to WHERE user_id = :from")
    suspend fun reassignAgendaViews(from: String, to: String): Int

    // ── Ownership itself: bare owner id, moved once ─────────────────────────

    /**
     * Hands the profiles over to [toOwnerId]. **Run last.**
     *
     * A profile row is what resolves an owner to a set of scopes, so until this flips
     * the new owner resolves nothing and a half-finished transfer stays invisible
     * rather than half-visible.
     */
    @Query("UPDATE profiles SET user_id = :toOwnerId WHERE user_id = :fromOwnerId")
    suspend fun reassignProfiles(fromOwnerId: String, toOwnerId: String): Int

    /**
     * Moves queued patches onto the new owner. **Not for symmetry.**
     *
     * A row created offline was queued under the local owner even though the push was
     * cancelled — the write pipeline queues regardless of whether a session may send it.
     * Leave the queue behind and a claimed task is a row the account holds locally and
     * the server never hears about, with no error anywhere: the push simply finds nothing
     * for this owner to send.
     */
    @Query("UPDATE sync_outbox SET owner_id = :toOwnerId WHERE owner_id = :fromOwnerId")
    suspend fun reassignOutbox(fromOwnerId: String, toOwnerId: String): Int

    /**
     * Moves shelved patches too.
     *
     * A dead-lettered patch is failed work for an owner, and after the transfer the new
     * owner is the one who would have to resolve it.
     */
    @Query("UPDATE sync_dead_letter SET owner_id = :toOwnerId WHERE owner_id = :fromOwnerId")
    suspend fun reassignDeadLetter(fromOwnerId: String, toOwnerId: String): Int

    /**
     * Moves the shadow rows that say what the server has already confirmed.
     *
     * Leaving them would make the next pull for the new owner believe the server had
     * already confirmed documents it has never seen.
     */
    @Query("UPDATE sync_shadow SET owner_id = :toOwnerId WHERE owner_id = :fromOwnerId")
    suspend fun reassignShadows(fromOwnerId: String, toOwnerId: String): Int

    // ── Preview (count without reassign) ───────────────────────────────────────

    /** Count tasks the [scopedUserIds] hold, without moving any. For preview before reassign. */
    @Query("SELECT COUNT(*) FROM tasks WHERE user_id IN (:scopedUserIds)")
    suspend fun countTasks(scopedUserIds: List<String>): Int

    /** Count notes the [scopedUserIds] hold. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM notes WHERE user_id IN (:scopedUserIds)")
    suspend fun countNotes(scopedUserIds: List<String>): Int

    /** Count projects the [scopedUserIds] hold. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM projects WHERE user_id IN (:scopedUserIds)")
    suspend fun countProjects(scopedUserIds: List<String>): Int

    /** Count tags the [scopedUserIds] hold. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM tags WHERE user_id IN (:scopedUserIds)")
    suspend fun countTags(scopedUserIds: List<String>): Int

    /** Count tag_groups the [scopedUserIds] hold. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM tag_groups WHERE user_id IN (:scopedUserIds)")
    suspend fun countTagGroups(scopedUserIds: List<String>): Int

    /** Count task_reminders the [scopedUserIds] hold. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM task_reminders WHERE user_id IN (:scopedUserIds)")
    suspend fun countTaskReminders(scopedUserIds: List<String>): Int

    /** Count project_reminders the [scopedUserIds] hold. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM project_reminders WHERE user_id IN (:scopedUserIds)")
    suspend fun countProjectReminders(scopedUserIds: List<String>): Int

    /** Count time_entries the [scopedUserIds] hold. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM time_entries WHERE user_id IN (:scopedUserIds)")
    suspend fun countTimeEntries(scopedUserIds: List<String>): Int

    /** Count agenda_views the [scopedUserIds] hold. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM agenda_views WHERE user_id IN (:scopedUserIds)")
    suspend fun countAgendaViews(scopedUserIds: List<String>): Int

    /** Count profiles the [ownerId] holds. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM profiles WHERE user_id = :ownerId")
    suspend fun countProfiles(ownerId: String): Int

    /** Count outbox rows the [ownerId] holds. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE owner_id = :ownerId")
    suspend fun countOutbox(ownerId: String): Int

    /** Count dead_letter rows the [ownerId] holds. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM sync_dead_letter WHERE owner_id = :ownerId")
    suspend fun countDeadLetter(ownerId: String): Int

    /** Count shadow rows the [ownerId] holds. See [countTasks]. */
    @Query("SELECT COUNT(*) FROM sync_shadow WHERE owner_id = :ownerId")
    suspend fun countShadows(ownerId: String): Int
}
