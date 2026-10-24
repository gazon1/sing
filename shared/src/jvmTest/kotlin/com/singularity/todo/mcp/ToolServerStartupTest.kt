package com.singularity.todo.mcp

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * dependency that will not build, a database that will not open, a profile nobody
 * owns — are not decided here, and this test does not pretend otherwise. What it
 * pins is the decision that can be made from the arguments alone.
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
}
