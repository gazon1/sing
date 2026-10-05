package com.singularity.todo.feature.auth

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.scopedUserIdFor
import com.singularity.todo.test.fakes.FakeAppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which rows an owner-scoped erase would remove, decided before anything is deleted.
 *
 * The property under test is not "the erase works" — it is **"the resolver does not
 * delete"**, and that separation is what makes an irreversible operation reviewable.
 * A test asserting rows are gone cannot tell "resolved correctly and deleted" from
 * "resolved wrongly and deleted"; every assertion here is about resolution alone.
 *
 * The default profile is the case that makes this worth testing. Its rows are filed
 * under the bare owner id rather than a `"profile/owner"` prefix, so a delete scoped
 * with `WHERE user_id = :owner` reaches the default profile's data *and nothing else* —
 * it succeeds, silently, having missed every other profile. It fails in the shape of
 * working, which is why "the owner's rows are all in the set" is asserted explicitly
 * for a second profile rather than left implicit.
 *
 * See ADR 2026-10-06-a-profile-is-owned-and-an-erase-resolves-its-ids-first.
 */
@Tag("fast")
class OwnerRowIdResolverTest {

    private val owner = UserId("u1")
    private val other = UserId("u2")
    private val workProfile = ProfileId.fromString("p-work")
    private val agentProfile = ProfileId.fromString("p-agent")

    private fun profileEntity(id: String, ownerId: String?, isDefault: Boolean = false) =
        com.singularity.todo.core.database.ProfileEntity(
            id = id,
            name = id,
            emoji = "🏠",
            colorIdx = 0,
            isDefault = isDefault,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            userId = ownerId,
        )

    private fun dbWithProfiles(profiles: List<com.singularity.todo.core.database.ProfileEntity>) =
        FakeAppDatabase().apply { seedProfiles(profiles) }

    @Test
    fun `an owner with no profiles still resolves the bare owner id`() = runTest {
        val db = dbWithProfiles(listOf(profileEntity("p-work", owner.value)))

        val resolved = OwnerRowIdResolver(db).resolve(owner)

        // The default profile's rows carry no profile column, so the bare owner is in
        // every owner's set whether or not a default profile row exists.
        assertTrue(
            resolved.contains(owner.value),
            "the bare owner id is missing, so the default profile's rows would never be erased",
        )
    }

    @Test
    fun `every profile the owner owns contributes its scope`() = runTest {
        val db = dbWithProfiles(
            listOf(
                profileEntity("p-work", owner.value),
                profileEntity("p-agent", owner.value),
            ),
        )

        val resolved = OwnerRowIdResolver(db).resolve(owner)

        assertTrue(resolved.contains(scopedUserIdFor(workProfile, owner).value))
        assertTrue(resolved.contains(scopedUserIdFor(agentProfile, owner).value))
    }

    /**
     * The regression this whole design exists for: a single-profile owner whose rows
     * are all under a prefix must be erased by the default profile too.
     */
    @Test
    fun `a profile is scoped by prefix, so the erase cannot stop at the default profile`() = runTest {
        val db = dbWithProfiles(listOf(profileEntity("p-work", owner.value)))

        val resolved = OwnerRowIdResolver(db).resolve(owner)

        assertEquals(
            setOf(owner.value, "p-work/u1"),
            resolved.scopedUserIds.toSet(),
        )
    }

    @Test
    fun `another account's profile is not resolved`() = runTest {
        val db = dbWithProfiles(
            listOf(
                profileEntity("p-work", owner.value),
                profileEntity("p-theirs", other.value),
            ),
        )

        val resolved = OwnerRowIdResolver(db).resolve(owner)

        assertFalse(
            resolved.scopedUserIds.any { it.contains("p-theirs") },
            "another account's profile leaked into the resolved set",
        )
        assertTrue(resolved.contains(scopedUserIdFor(workProfile, owner).value))
    }

    /**
     * The suffix trap.
     *
     * `p/u1` and `eu1` differ by one character, and a `LIKE '%/u1'`-style pattern cannot
     * tell them apart — it matches both. The resolved set is built by composing ids from
     * the owner's own profiles, so it cannot contain `eu1` even though the strings share
     * a suffix. This is the property a pattern-based delete would fail.
     */
    @Test
    fun `an account whose id shares a suffix is not included`() = runTest {
        val lookalike = UserId("eu1")
        val db = dbWithProfiles(
            listOf(
                profileEntity("p-work", owner.value),
                profileEntity("p-lookalike", lookalike.value),
            ),
        )

        val resolved = OwnerRowIdResolver(db).resolve(owner)

        assertFalse(
            resolved.scopedUserIds.any { it.endsWith(lookalike.value) },
            "an id sharing a suffix with the owner leaked in; a LIKE would have done this",
        )
    }

    @Test
    fun `an unowned profile belongs to nobody's erase`() = runTest {
        val db = dbWithProfiles(
            listOf(
                profileEntity("p-work", owner.value),
                profileEntity("p-unowned", null),
            ),
        )

        val resolved = OwnerRowIdResolver(db).resolve(owner)

        assertFalse(
            resolved.scopedUserIds.any { it.startsWith("p-unowned/") },
            "a profile with no owner was treated as the owner's",
        )
    }

    @Test
    fun `resolving deletes nothing`() = runTest {
        val db = dbWithProfiles(
            listOf(
                profileEntity("p-work", owner.value),
                profileEntity("p-agent", owner.value),
            ),
        )

        OwnerRowIdResolver(db).resolve(owner)

        // The resolver is read-only by construction; this asserts it, so a future
        // "optimisation" that folds the delete into the resolve fails here rather than
        // in a user's database.
        assertEquals(2, db.profileDao().allNames().size)
    }

    @Test
    fun `the profile list and the scope list describe the same owner`() = runTest {
        val db = dbWithProfiles(
            listOf(
                profileEntity("p-work", owner.value),
                profileEntity("p-agent", owner.value),
            ),
        )

        val resolved = OwnerRowIdResolver(db).resolve(owner)

        assertEquals(resolved.profileIds.size + 1, resolved.scopedUserIds.size)
    }
}
