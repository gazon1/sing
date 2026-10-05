package com.singularity.todo.feature.auth

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.scopedUserIdFor

/**
 * The exact set of row ids belonging to [UserId], across every profile they own.
 *
 * Produced by reading, consumed by deleting. The separation is the point: a test can
 * assert *what would be deleted* without anything having been deleted, which is the only
 * way to tell "resolved correctly" from "deleted the wrong rows" — a distinction that
 * matters here because the operation is irreversible.
 */
data class OwnerRowIds(
    val ownerId: String,
    /** Profile ids the owner owns, oldest first. Empty for an owner with no profiles. */
    val profileIds: List<String>,
    /** Composed `user_id` values: the bare owner plus one per profile. */
    val scopedUserIds: List<String>,
) {
    /**
     * Whether anything would be deleted at all.
     *
     * A switch to an owner with no profiles still has to run the erase, because the
     * default profile's rows live under the bare owner id and carry no profile column —
     * so this is false only for an owner with neither a profile nor rows of its own.
     */
    val isEmpty: Boolean get() = scopedUserIds.isEmpty()

    /** True when [scoped] is one of this owner's scopes. */
    fun contains(scoped: String): Boolean = scoped in scopedUserIds
}

/**
 * Resolves an account to the exact ids an owner-scoped erase would remove.
 *
 * ## Why this exists rather than a `WHERE user_id = :owner` delete
 *
 * A profile is not a column on the data tables — `tasks`, `notes`, `projects`, `tags`,
 * `time_entries` and `agenda_views` have no `profile_id`. The profile survives only in the
 * shape of the id, via `scopedUserIdFor`: the default profile's rows are the bare owner id,
 * every other profile's are `"<profile>/<owner>"`.
 *
 * So the obvious delete erases the default profile and leaves the rest behind, in the shape
 * of success: the user sees an empty task list and no error. `tasks.md`'s test — two
 * profiles, one switch, both gone — fails on exactly that.
 *
 * The alternative, `user_id LIKE '%/' || :owner`, is a **suffix** match and cannot
 * distinguish `prof/u1` from an account whose own id is `eu1`. A pattern that decides which
 * rows a user permanently loses is the same defect as a pattern that decides who they are.
 *
 * ## Why the ids are composed here and not parsed
 *
 * The composed ids come from `scopedUserIdFor`, the same function that wrote them. Parsing
 * an id back into its profile would be a second, independent implementation of that rule,
 * and the two would eventually disagree — at which point the erase targets rows that do not
 * belong to the owner it resolved.
 *
 * Profiles with a NULL `user_id` belong to nobody and are therefore never in the result.
 * An owner's rows under a profile it does not own (possible only for rows written before
 * v39) are not erased either; see `Migration38To39`.
 *
 * @param database the Room database, used read-only by [resolve].
 */
class OwnerRowIdResolver(private val database: AppDatabase) {

    /**
     * Reads the owner's profiles and composes the scoped ids their rows are filed under.
     *
     * Read-only. Nothing is deleted here, and nothing is deleted by anything that calls
     * this without first having asserted against the returned value.
     */
    suspend fun resolve(owner: UserId): OwnerRowIds {
        val profiles = database.profileDao().listOwnedBy(owner.value)
        val profileIds = profiles.map { it.id }
        // The default profile's scope is the bare owner, which is in the set whether or
        // not a default profile row exists: the row can be absent and the rows still be
        // there, and vice versa. Including it unconditionally is what makes the default
        // profile's data erasable at all.
        val scoped = profileIds
            .map { scopedUserIdFor(ProfileId.fromString(it), owner).value }
            .toMutableSet()
        scoped.add(owner.value)
        return OwnerRowIds(
            ownerId = owner.value,
            profileIds = profileIds,
            scopedUserIds = scoped.toList(),
        )
    }
}
