package com.singularity.todo.core.database

import androidx.room3.Dao
import androidx.room3.Query

/**
 * The parent id sets an owner-scoped erase scopes its child deletes by.
 *
 * Split from [OwnerEraseDao] because these are **reads** and those are writes, and
 * because a delete cannot find the children that named a parent once the parent is
 * gone: the caller must read these first, delete children, and only then delete parents.
 * Keeping the reads in a separate interface makes that order a compile-time shape rather
 * than a comment — a caller that has an `OwnerScopeDao` has something to read, and one
 * that does not is mid-sequence.
 *
 * ## Why ids rather than a pattern
 *
 * A profile is not a column on `tasks` or `projects`; it survives only as the shape of
 * the id. `WHERE user_id = :owner` would return the default profile's rows and nothing
 * else, and a `LIKE '%/' || :owner` cannot tell `prof/u1` from an account whose own id is
 * `eu1`. So the caller resolves the exact scopes first (see `OwnerRowIdResolver`) and these
 * queries select on membership in that list.
 *
 * See ADR 2026-10-06-a-profile-is-owned-and-an-erase-resolves-its-ids-first.
 */
@Dao
interface OwnerScopeDao {

    /** Task ids the owner holds — the parent set for the task-scoped child deletes. */
    @Query("SELECT id FROM tasks WHERE user_id IN (:scopedUserIds)")
    suspend fun listTaskIdsForOwner(scopedUserIds: List<String>): List<String>

    /** Project ids the owner holds — the parent set for `project_tag_groups`. */
    @Query("SELECT id FROM projects WHERE user_id IN (:scopedUserIds)")
    suspend fun listProjectIdsForOwner(scopedUserIds: List<String>): List<String>
}
