package com.singularity.todo.core.database

import androidx.room3.Dao
import androidx.room3.Query

/**
 * Every owner-scoped delete REQ-UA-017 performs, in one place.
 *
 * ## Why these live here rather than on the entity DAOs
 *
 * Two reasons, and the second is the real one.
 *
 * The first is mechanical: eight entity DAOs each gained a method for the same operation,
 * and `ReminderDao` crossed detekt's `TooManyFunctions` threshold because of it. That is
 * a symptom.
 *
 * The second is that these deletes have an **order**, and the order is load-bearing.
 * Four tables carry no `user_id` and declare no foreign keys — `task_tags`,
 * `task_dependencies` and `checklist_items` hang off `tasks`, `project_tag_groups` hangs
 * off `projects` — so a parent deleted first leaves its children orphaned: rows that
 * belong to neither the account that left nor the account that arrives. Scattering the
 * statements across eight interfaces puts that order at the mercy of whoever writes the
 * caller. Grouping them by *operation* makes the order something one file states.
 *
 * ## Why nothing here uses a LIKE pattern
 *
 * A profile is not a column on these tables; it survives only as the shape of the id
 * (`scopedUserIdFor` composes `"profile/owner"`, while the default profile's rows are
 * the bare owner). An owner-scoped delete therefore cannot be `user_id = :owner` — that
 * erases the default profile and nothing else, succeeding silently — and cannot be
 * `LIKE '%/' || :owner` either, because a suffix match cannot tell `prof/u1` from an
 * account whose own id is `eu1`.
 *
 * So [scopedUserIds] is the exact list the `OwnerRowIdResolver` composed, and every
 * statement below selects on membership in it.
 *
 * ## Who calls what, in what order
 *
 * [com.singularity.todo.feature.auth.OwnerScopedEraser] is the only caller, and its
 * order is: read the parent id sets → delete children → delete parents → delete sync
 * state. The parent id sets are read **before** anything is deleted, because once a
 * task is gone there is no query left that can find the children naming it.
 *
 * See ADR 2026-10-06-a-profile-is-owned-and-an-erase-resolves-its-ids-first.
 */
@Dao
@Suppress("TooManyFunctions")
interface OwnerEraseDao {

    // ── Children: no `user_id`, reached through their parent ───────────────────

    /** Tag cross-references held by [taskIds]. Run before deleting those tasks. */
    @Query(
        """
        DELETE FROM task_tags
        WHERE task_id IN (
            SELECT id FROM tasks
            WHERE id IN (:taskIds) AND user_id IN (:scopedUserIds)
        )
        """,
    )
    suspend fun deleteTagRefs(taskIds: List<String>, scopedUserIds: List<String>): Int

    /**
     * Dependency rows naming [taskIds] — **both** directions.
     *
     * `depends_on_task_id` is matched as well: a row where the *other* task is the
     * departing owner's must go too, or a surviving task keeps a dependency on a task
     * that no longer exists.
     */
    @Query(
        """
        DELETE FROM task_dependencies
        WHERE task_id IN (
            SELECT id FROM tasks
            WHERE id IN (:taskIds) AND user_id IN (:scopedUserIds)
        )
        OR depends_on_task_id IN (
            SELECT id FROM tasks
            WHERE id IN (:taskIds) AND user_id IN (:scopedUserIds)
        )
        """,
    )
    suspend fun deleteDependencies(taskIds: List<String>, scopedUserIds: List<String>): Int

    /** Checklist items hung off [taskIds]. Run before deleting those tasks. */
    @Query(
        """
        DELETE FROM checklist_items
        WHERE task_id IN (
            SELECT id FROM tasks
            WHERE id IN (:taskIds) AND user_id IN (:scopedUserIds)
        )
        """,
    )
    suspend fun deleteChecklistItems(taskIds: List<String>, scopedUserIds: List<String>): Int

    /**
     * Inherited tag groups of the owner's projects. Run before deleting those projects.
     *
     * Note it is scoped through `projects`, not `tasks`: an `EXISTS` against the wrong
     * parent deletes nothing and reports success, which is the harder failure to notice.
     */
    @Query(
        """
        DELETE FROM project_tag_groups
        WHERE project_id IN (
            SELECT id FROM projects
            WHERE id IN (:projectIds) AND user_id IN (:scopedUserIds)
        )
        """,
    )
    suspend fun deleteInheritedTagGroups(projectIds: List<String>, scopedUserIds: List<String>): Int

    // ── Parents: they carry their own `user_id` ────────────────────────────────

    @Query("DELETE FROM tasks WHERE user_id IN (:scopedUserIds)")
    suspend fun deleteTasks(scopedUserIds: List<String>): Int

    @Query("DELETE FROM notes WHERE user_id IN (:scopedUserIds)")
    suspend fun deleteNotes(scopedUserIds: List<String>): Int

    @Query("DELETE FROM projects WHERE user_id IN (:scopedUserIds)")
    suspend fun deleteProjects(scopedUserIds: List<String>): Int

    @Query("DELETE FROM tags WHERE user_id IN (:scopedUserIds)")
    suspend fun deleteTags(scopedUserIds: List<String>): Int

    @Query("DELETE FROM tag_groups WHERE user_id IN (:scopedUserIds)")
    suspend fun deleteTagGroups(scopedUserIds: List<String>): Int

    @Query("DELETE FROM task_reminders WHERE user_id IN (:scopedUserIds)")
    suspend fun deleteTaskReminders(scopedUserIds: List<String>): Int

    @Query("DELETE FROM project_reminders WHERE user_id IN (:scopedUserIds)")
    suspend fun deleteProjectReminders(scopedUserIds: List<String>): Int

    @Query("DELETE FROM time_entries WHERE user_id IN (:scopedUserIds)")
    suspend fun deleteTimeEntries(scopedUserIds: List<String>): Int

    @Query("DELETE FROM agenda_views WHERE user_id IN (:scopedUserIds)")
    suspend fun deleteAgendaViews(scopedUserIds: List<String>): Int

    // ── Preview (count without delete) ────────────────────────────────────────

    /** @see deleteTagRefs */
    @Query(
        """
        SELECT COUNT(*) FROM task_tags
        WHERE task_id IN (
            SELECT id FROM tasks
            WHERE id IN (:taskIds) AND user_id IN (:scopedUserIds)
        )
        """,
    )
    suspend fun countTagRefs(taskIds: List<String>, scopedUserIds: List<String>): Int

    /** @see deleteDependencies */
    @Query(
        """
        SELECT COUNT(*) FROM task_dependencies
        WHERE task_id IN (
            SELECT id FROM tasks
            WHERE id IN (:taskIds) AND user_id IN (:scopedUserIds)
        )
        OR depends_on_task_id IN (
            SELECT id FROM tasks
            WHERE id IN (:taskIds) AND user_id IN (:scopedUserIds)
        )
        """,
    )
    suspend fun countDependencies(taskIds: List<String>, scopedUserIds: List<String>): Int

    /** @see deleteChecklistItems */
    @Query(
        """
        SELECT COUNT(*) FROM checklist_items
        WHERE task_id IN (
            SELECT id FROM tasks
            WHERE id IN (:taskIds) AND user_id IN (:scopedUserIds)
        )
        """,
    )
    suspend fun countChecklistItems(taskIds: List<String>, scopedUserIds: List<String>): Int

    /** @see deleteInheritedTagGroups */
    @Query(
        """
        SELECT COUNT(*) FROM project_tag_groups
        WHERE project_id IN (
            SELECT id FROM projects
            WHERE id IN (:projectIds) AND user_id IN (:scopedUserIds)
        )
        """,
    )
    suspend fun countInheritedTagGroups(projectIds: List<String>, scopedUserIds: List<String>): Int

    /** @see deleteTasks */
    @Query("SELECT COUNT(*) FROM tasks WHERE user_id IN (:scopedUserIds)")
    suspend fun countTasks(scopedUserIds: List<String>): Int

    /** @see deleteNotes */
    @Query("SELECT COUNT(*) FROM notes WHERE user_id IN (:scopedUserIds)")
    suspend fun countNotes(scopedUserIds: List<String>): Int

    /** @see deleteProjects */
    @Query("SELECT COUNT(*) FROM projects WHERE user_id IN (:scopedUserIds)")
    suspend fun countProjects(scopedUserIds: List<String>): Int

    /** @see deleteTags */
    @Query("SELECT COUNT(*) FROM tags WHERE user_id IN (:scopedUserIds)")
    suspend fun countTags(scopedUserIds: List<String>): Int

    /** @see deleteTagGroups */
    @Query("SELECT COUNT(*) FROM tag_groups WHERE user_id IN (:scopedUserIds)")
    suspend fun countTagGroups(scopedUserIds: List<String>): Int

    /** @see deleteTaskReminders */
    @Query("SELECT COUNT(*) FROM task_reminders WHERE user_id IN (:scopedUserIds)")
    suspend fun countTaskReminders(scopedUserIds: List<String>): Int

    /** @see deleteProjectReminders */
    @Query("SELECT COUNT(*) FROM project_reminders WHERE user_id IN (:scopedUserIds)")
    suspend fun countProjectReminders(scopedUserIds: List<String>): Int

    /** @see deleteTimeEntries */
    @Query("SELECT COUNT(*) FROM time_entries WHERE user_id IN (:scopedUserIds)")
    suspend fun countTimeEntries(scopedUserIds: List<String>): Int

    /** @see deleteAgendaViews */
    @Query("SELECT COUNT(*) FROM agenda_views WHERE user_id IN (:scopedUserIds)")
    suspend fun countAgendaViews(scopedUserIds: List<String>): Int

    /** @see deleteProfiles */
    @Query("SELECT COUNT(*) FROM profiles WHERE user_id = :ownerId")
    suspend fun countProfiles(ownerId: String): Int
}
