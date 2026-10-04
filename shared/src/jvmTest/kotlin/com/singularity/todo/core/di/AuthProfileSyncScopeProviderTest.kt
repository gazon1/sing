@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.di

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncAuthRepository
import com.singularity.todo.core.sync.SyncScope
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.test.fakes.FakeProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [AuthProfileSyncScopeProvider] — the answer to "which `(owner, profile)` is sync
 * operating on".
 *
 * Two failure modes matter and neither is visible from the happy path:
 *
 * - **`null` collapsed into a default scope.** A signed-out client with a fabricated
 *   owner would read and write a cursor belonging to nobody, and every pull would
 *   start from a position that is not in any server's event log.
 * - **A profile switch that keeps the previous profile.** The scope is a `combine`
 *   of two flows, and the half that is easy to get wrong is the *other* half: emit
 *   the new owner with the stale profile id, and sync writes one account's data under
 *   another account's profile.
 */
@Tag("fast")
class AuthProfileSyncScopeProviderTest {

    private val userId = UserId("user-1")
    private val otherUser = UserId("user-2")
    private val workProfile = ProfileId("work")

    private fun provider(
        auth: FakeSyncAuthRepository = FakeSyncAuthRepository(Session.SignedIn(userId, "a@x.com", "t", "r")),
        profiles: FakeProfileRepository = FakeProfileRepository(),
    ) = AuthProfileSyncScopeProvider(auth, profiles)

    @Test
    fun `the scope pairs the signed-in account with the active profile`() = runTest {
        val profiles = FakeProfileRepository().apply { setActiveProfile(workProfile) }

        val scope = provider(profiles = profiles).current.first()

        assertEquals(SyncScope(ownerId = "user-1", profileId = "work"), scope)
    }

    @Test
    fun `a signed-out client has no scope at all`() = runTest {
        val auth = FakeSyncAuthRepository(Session.SignedOut)

        assertNull(
            provider(auth = auth).current.first(),
            "a fabricated default owner would read and write a cursor that belongs to nobody",
        )
    }

    @Test
    fun `switching account switches the owner half of the scope`() = runTest {
        val auth = FakeSyncAuthRepository(Session.SignedIn(userId, "a@x.com", "t", "r"))
        val flow = provider(auth = auth).current

        assertEquals(SyncScope("user-1", "default"), flow.first())

        auth.signIn(otherUser)

        assertEquals(
            SyncScope("user-2", "default"),
            flow.first(),
            "the new owner must not be paired with the previous account's cursor",
        )
    }

    @Test
    fun `switching profile switches the profile half of the scope`() = runTest {
        val profiles = FakeProfileRepository()
        val flow = provider(profiles = profiles).current

        assertEquals(SyncScope("user-1", "default"), flow.first())

        profiles.setActiveProfile(workProfile)

        assertEquals(SyncScope("user-1", "work"), flow.first())
    }

    @Test
    fun `the default profile is a real scope, not a missing one`() = runTest {
        // A fresh install has never synced, and its scope is `(user, default)` with a
        // cursor of zero. Collapsing "no active profile" into "no scope" would leave
        // first-run indistinguishable from signed-out.
        val scope = provider().current.first()

        assertEquals(ProfileId.default.value, scope?.profileId)
    }
}
