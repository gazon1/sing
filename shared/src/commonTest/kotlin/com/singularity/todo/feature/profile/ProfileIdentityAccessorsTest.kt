package com.singularity.todo.feature.profile

import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.loggingBackgroundFailureHandler
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileRepository
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the identity contract the MCP bootstrap and every user-scoped repository read
 * depend on: which accessor is safe to read *across* a profile switch, and which is not.
 *
 * The bug this guards is `mcp-server`'s `bootstrapProfiles`, which used to read the
 * cached `ProfileAwareCurrentUser.current` **after** activating the agent profile and
 * then moved every row from that value to `"{agentId}/{value}"`. Because
 * `bootstrapper.run` is `suspend`, the background collector got a chance to run, so the
 * read sometimes returned the already-scoped id and the migration rewrote live agent
 * rows to `"{agent}/{agent}/{user}"` — an id no profile matches, making them
 * unreachable. Whether it happened depended on dispatcher timing, which is the worst
 * possible property for a data migration.
 *
 * These tests assert the *shape* of the API that makes that impossible, not the
 * migration itself (that one shells out to `sqlite3` and is covered by the MCP E2E
 * suite). ADR `2026-10-04-derived-identity-flows`.
 */
@Tag("fast")
class ProfileIdentityAccessorsTest {

    private val localUserId = UserId.fromString("u-1")

    private fun newUser(): Triple<ProfileAwareCurrentUser, FakeAuthRepository, FakeProfileRepository> {
        val auth = FakeAuthRepository(initialSession = Session.Anonymous(localUserId))
        val profiles = FakeProfileRepository()
        val currentUser = ProfileAwareCurrentUser(
            currentUser = CurrentUser(auth, scope = createBackgroundScope(loggingBackgroundFailureHandler())),
            profileRepository = profiles,
            scope = createBackgroundScope(loggingBackgroundFailureHandler()),
        )
        return Triple(currentUser, auth, profiles)
    }

    @Test
    fun liveLocalUserId_is_invariant_across_a_profile_switch() = kotlinx.coroutines.test.runTest {
        val (currentUser, _, profiles) = newUser()
        val before = currentUser.liveLocalUserId.first().value

        profiles.switchTo(ProfileId.fromString("ai-agent"))

        // The whole point: a caller that must describe the identity *before* the
        // switch gets the same answer on both sides of it, so it cannot accidentally
        // build a doubly-scoped id.
        assertEquals(localUserId.value, before, "pre-switch id must be the un-scoped local id")
        assertEquals(
            before,
            currentUser.liveLocalUserId.first().value,
            "liveLocalUserId must not change when the profile changes",
        )
    }

    @Test
    fun liveScopedUserId_reflects_the_switch_immediately() = kotlinx.coroutines.test.runTest {
        val (currentUser, _, profiles) = newUser()
        assertEquals(localUserId.value, currentUser.liveScopedUserId.first().value)

        profiles.switchTo(ProfileId.fromString("ai-agent"))

        val scoped = currentUser.liveScopedUserId.first()
        assertEquals("ai-agent/${localUserId.value}", scoped.value)
        assertNotEquals(currentUser.liveLocalUserId.first(), scoped)
    }

    @Test
    fun liveUserId_never_reports_the_anonymous_seed_for_a_signed_in_session() =
        kotlinx.coroutines.test.runTest {
            // `CurrentUser.userId` is seeded with `UserId.anonymous` and corrected by a
            // collector, so the cached copy can report "anonymous" for a real session.
            // The derived flow must never do that — this is the property that lets a
            // repository scope a subscription correctly before any collector runs.
            val (currentUser, _, _) = newUser()
            val live = currentUser.liveLocalUserId.first().value
            assertTrue(
                live != UserId.anonymous.value,
                "liveUserId reported the anonymous seed for a signed-in session: $live",
            )
            assertEquals(localUserId.value, live)
        }
}
