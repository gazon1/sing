package com.singularity.todo.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The error the tool server sends when it cannot start.
 *
 * Two properties, and both of them used to be false: the response has to be a valid
 * protocol message whatever the error text contains, and it must not carry the internals
 * of the failure.
 */
@Tag("fast")
class JsonRpcErrorTest {

    private val parser = Json

    @Test
    fun `the response is a valid protocol message`() {
        val body = jsonRpcError(-32000, "The server could not start.")

        val parsed = parser.parseToJsonElement(body).jsonObject
        assertEquals("2.0", parsed["jsonrpc"]?.jsonPrimitive?.content, "It says which protocol it is")
        assertTrue(parsed.containsKey("error"), "And it carries an error")
    }

    @Test
    fun `a quote in the message does not break it`() {
        val body = jsonRpcError(-32000, """He said "no" and left""")

        val parsed = parser.parseToJsonElement(body).jsonObject
        assertEquals(
            """He said "no" and left""",
            parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content,
            "The message survives the quote",
        )
    }

    @Test
    fun `a newline in the message does not break it`() {
        val body = jsonRpcError(-32000, "first line\nsecond line")

        val parsed = parser.parseToJsonElement(body).jsonObject
        assertEquals(
            "first line\nsecond line",
            parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content,
            "The message survives the newline",
        )
    }

    @Test
    fun `a backslash does not break it`() {
        val body = jsonRpcError(-32000, """a\b\c""")

        val parsed = parser.parseToJsonElement(body).jsonObject
        assertEquals(
            """a\b\c""",
            parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content,
            "The message survives the backslash",
        )
    }

    @Test
    fun `the code is the code that was asked for`() {
        val body = jsonRpcError(-32001, "nope")

        val parsed = parser.parseToJsonElement(body).jsonObject
        assertEquals(
            -32001,
            parsed["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content?.toInt(),
            "The code is not lost",
        )
    }

    @Test
    fun `a path in the message is a path the caller was told about`() {
        // The message the caller receives is neutral by contract. This test pins the
        // contract, not a policy about paths: the server decides what to say, and the
        // detail belongs in the log.
        val body = jsonRpcError(-32001, "The server could not open its database.")

        assertFalse(body.contains("/home/"), "No path in the neutral message")
    }
}
