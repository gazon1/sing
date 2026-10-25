package com.singularity.todo.mcp.errors

import io.modelcontextprotocol.kotlin.sdk.types.McpException
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What an internal failure is allowed to say.
 *
 * An exception's message can carry anything: a file path, a database URL, the arguments the
 * caller sent, the response of a server that is not ours. The caller of a tool reads the error's
 * text, so the text is a place where the internals of a failure become visible to whoever
 * happens to be asking.
 *
 * The rule is therefore in the type rather than in the discipline of each branch: an internal
 * error carries a code, and the cause — where the details are — never travels with it.
 */
@Tag("fast")
class InternalErrorTest {

    private val leakyMessage = "connection to /home/max/.config/secret.db refused by 10.0.0.4"

    @Test
    fun `the caller's answer carries the code and not the failure's text`() {
        val error = McpToolError.Internal(code = "tool_call_failed", cause = IllegalStateException(leakyMessage))

        val thrown = assertFailsWith<McpException> { ErrorMapper.toResult(error) }

        val said = thrown.message.orEmpty()
        assertTrue("tool_call_failed" in said, "The caller is told what happened: $said")
        assertFalse("secret.db" in said, "A path must not reach the caller: $said")
        assertFalse("10.0.0.4" in said, "An address must not reach the caller: $said")
        assertFalse("refused" in said, "The failure's own words must not reach the caller: $said")
    }

    @Test
    fun `the cause stays on the error, for the log`() {
        val cause = IllegalStateException(leakyMessage)

        val error = McpToolError.Internal(code = "tool_call_failed", cause = cause)

        assertEquals(cause, error.cause, "The details are kept — in the log, not in the answer")
    }

    @Test
    fun `a business error still tells the caller what to fix`() {
        val error = McpToolError.Validation(field = "title", text = "must not be empty")

        val result = ErrorMapper.toResult(error)

        assertTrue(result.isError == true, "A business error is a result the model can read")
        val text = result.content.filterIsInstance<io.modelcontextprotocol.kotlin.sdk.types.TextContent>()
            .joinToString(" ") { it.text }
        assertTrue("title" in text, "It says which field: $text")
    }
}
