package com.singularity.todo.feature.profile

import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import com.singularity.todo.test.fakes.FakeProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A profile that was never activated is not reported as activated (REQ-PROF-001).
 *
 * ## What this is a control for
 *
 * `ProfileBootstrapper.run` called `repository.switchTo(profile.id)` and discarded the
 * `Result`, then returned `activated = profile.id` and logged "activated profile". So a
 * failed switch produced a result claiming a profile was active.
 *
 * That is not a cosmetic lie about a return value. `mcp/Main.kt:164` reads
 * `result.activated` and, on a non-null id, runs `retromigrateRowsToAgentScope` — a
 * destructive migration of every row owned by the unscoped local user id into a scope
 * keyed on the activated profile. A switch that failed but still returned an id moved
 * the rows into a namespace the server is not running under. The return value is an
 * input to a migration, so "best effort" is not a safe reading of it.
 *
 * ## Why throwing, and why it is safe here
 *
 * `.getOrThrow()` rather than returning null or logging. The only production caller
 * already wraps the whole bootstrap in a try/catch that logs and continues against the
 * default profile (mcp/Main.kt:175-179), so a throw lands in a handler that exists and
 * says why — and, critically, does *not* reach the migration.
 *
 * Returning null instead would be worse than it looks: the caller's `activated?.let`
 * would simply skip the migration, which is the same outcome, but `null` is also what
 * a successful `activateName = null` run returns, so a caller could no longer tell
 * "nothing asked for a switch" from "the switch failed". Those are different situations
 * and the type would conflate them.
 *
 * ## What the fake had to grow
 *
 * `FakeProfileRepository.switchTo` could only ever succeed. That is precisely the fake
 * that let the defect ship: every test that exercised the bootstrapper exercised the
 * branch where the lie is invisible. `switchToFailure` is the smallest change that makes
 * the branch reachable.
 */
@Tag("fast")
class ProfileBootstrapperSwitchFailureTest {

    private val agentProfile = ProfileBootstrapper.SeedProfile.AI_AGENT

    private suspend fun seeded(): FakeProfileRepository {
        val repo = FakeProfileRepository()
        val seed = Triple(agentProfile.name, agentProfile.emoji, agentProfile.colorIdx)
        repo.ensureDefaults(extraProfiles = listOf(seed))
        return repo
    }

    @Test
    fun `a profile that was not activated is not reported as activated`() = runTest {
        val repo = seeded()
        val target = repo.observeAll().first().first { it.name == agentProfile.name }
        val lockError = IllegalStateException("DataStore is closed")
        repo.switchToFailure = lockError

        val thrown = assertFailsWith<IllegalStateException> {
            ProfileBootstrapper(repo).run(activateName = agentProfile.name)
        }

        assertSame(
            lockError,
            thrown,
            "the reason DataStore refused the switch, not a wrapper — the operator reading " +
                "this log is the only one who can tell a closed store from a missing profile",
        )
        assertTrue(
            repo.activeProfileId.value != target.id,
            "and the active profile genuinely did not change, which is the point: the " +
                "previous code reported this id as activated anyway",
        )
    }

    @Test
    fun `a successful switch is reported`() = runTest {
        // The negative case. Without it, "always throw" would pass the control above and
        // break every caller that legitimately activates a profile.
        val repo = seeded()
        val target = repo.observeAll().first().first { it.name == agentProfile.name }

        val result = ProfileBootstrapper(repo).run(activateName = agentProfile.name)

        assertEquals(
            target.id,
            result.activated,
            "the switch succeeded, so the id is reported and the caller may migrate",
        )
        assertEquals(target.id, repo.activeProfileId.value, "and the store agrees")
    }

    @Test
    fun `a run that asked for no switch reports nothing`() = runTest {
        // The case the throw must not swallow: activateName = null is a legitimate
        // request, not a failure, and conflating the two would change what every
        // non-agent MCP invocation does.
        val repo = seeded()

        val result = ProfileBootstrapper(repo).run(activateName = null)

        assertNull(result.activated, "nothing was asked for, so nothing is reported")
    }

    @Test
    fun `a name that was not found reports nothing and does not throw`() = runTest {
        // Also a legitimate outcome, handled by the other arm of the same if. A fix that
        // made every non-null activateName throw would break this.
        val repo: ProfileRepository = seeded()

        val result = ProfileBootstrapper(repo).run(activateName = "No Such Profile")

        assertNull(result.activated, "the profile was not seeded, so there is nothing to report")
    }
}
