package com.singularity.todo.mcp

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the server refuses, and what it tells the client about it.
 *
 * ## What this protects
 *
 * The start-up's refusals used to be `exitProcess` calls in the middle of the
 * sequence, so the only way to know what the client would have been told was to read
 * the whole function — and the only way to find out in a test was to start a process,
 * a container and a database.
 *
 * The refusals are values now, so the contract they carry can be checked here: the
 * JSON-RPC code, the message the client sees, and the exit code the process leaves
 * with. A change to any of them is a change to what a client is told, and this is
 * where that change is caught.
 *
 * ## What this does not check
 *
 * The refusals that need a container or a database — a Koin that will not start, a
 * dependency that will not build, a database that will not open — are not decided
 * here, and this test does not pretend otherwise. What it pins is the decision that
 * can be made from the arguments alone, and the decisions whose *lookup* is a
 * parameter: whether a profile id is known is asked of a lookup this test supplies,
 * so the rule can be checked without the database that answers it for real.
 */
@Tag("fast")
class ToolServerStartupTest {

    @Test
    fun `both profile arguments are a refusal, not a guess`() {
        val refusal = refusalFor(ProfileSelection.Ambiguous(label = "ai-agent", id = "abc-123"))

        assertEquals(
            ToolServerStartup.Refused(
                code = -32002,
                message = "Conflicting profile arguments: pass either --profile or --profile-id, not both.",
                exitCode = 2,
            ),
            refusal,
        )
    }

    @Test
    fun `the ambiguous arguments are refused before anything is started`() {
        val selection = parseProfileSelection(arrayOf("--profile=ai-agent", "--profile-id=abc-123"))

        assertNotNull(refusalFor(selection))
    }

    @Test
    fun `one profile argument is not a refusal`() {
        assertNull(refusalFor(ProfileSelection.FixedId(id = "abc-123")))
        assertNull(refusalFor(ProfileSelection.LegacyLabel(name = "ai-agent")))
        assertNull(refusalFor(ProfileSelection.Stored))
    }

    @Test
    fun `the exit codes are the ones the server promises`() {
        assertEquals(1, EXIT_STARTUP)
        assertEquals(2, EXIT_USAGE)
    }

    @Test
    fun `a profile id nobody owns is a refusal, not a fallback`() = runTest {
        val refusal = refusalForUnknownProfile("abc-123") { false }

        assertEquals(
            ToolServerStartup.Refused(
                code = -32002,
                message = "No profile with that id exists.",
                exitCode = 2,
            ),
            refusal,
        )
    }

    @Test
    fun `a profile id somebody owns is served`() = runTest {
        assertNull(refusalForUnknownProfile("abc-123") { true })
    }

    @Test
    fun `a process without a profile id is never refused for one`() = runTest {
        // The stored profile is the application's business, and this process does not
        // question it: the lookup is not even asked.
        var asked = false
        val refusal = refusalForUnknownProfile(null) {
            asked = true
            true
        }

        assertNull(refusal)
        assertFalse(asked, "And the lookup was never made")
    }

    @Test
    fun `the legacy label activates the profile it always activated`() {
        assertEquals("AI Agent", profileNameToActivate("ai-agent"))
        assertEquals("AI Agent", profileNameToActivate("agent"))
        assertEquals("AI Agent", profileNameToActivate("AI-Agent"), "The label is a name, not a case")
    }

    @Test
    fun `the legacy label that means nothing activates nothing`() {
        assertNull(profileNameToActivate("default"))
        assertNull(profileNameToActivate("personal"))
        assertNull(profileNameToActivate(null))
        assertNull(profileNameToActivate("a-label-nobody-knows"), "An unknown label is ignored, as it always was")
    }
}
