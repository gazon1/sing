package com.singularity.todo.mcp.introspection

import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The device host's half of the inspector contract (#3074).
 *
 * ## What is being proven
 *
 * The Issue's Android criterion is that a debug/test session "exposes the same logical
 * contract as the JVM Desktop host". That claim is only worth something if the *contract*
 * — the envelope, the allowlist, the refusal of unknown input — is the same object the
 * desktop host serves and not a second implementation that looks similar. So every
 * assertion here is about the JSON the provider's caller receives, and the container is
 * injected: the host is driven exactly as a transport would drive it, with no global Koin
 * and no Android runtime.
 *
 * `ScreenInspectorRuntimeTest` owns the behavior of a session (open → invoke → observe)
 * against a real database. This class owns the boundary: what a malformed or hostile
 * request gets, and that it happens **before** dispatch.
 *
 * @see ScreenInspectorHost
 */
@Epic("Agent")
@Feature("Headless screen inspector")
@Tag("fast")
class ScreenInspectorHostContractTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `a build with no running container fails closed instead of starting one`() {
        // The failure mode this pins: a host that bootstrapped its own container would be
        // inspecting a *different* application while reporting on this one. There is no
        // container here, so there is no host.
        val host = ScreenInspectorHost { null }
        val envelope = host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")

        assertEquals("internal", errorCode(envelope))
        assertFalse(host.isStarted(), "the host created a runtime without a container")
        host.close()
    }

    @Test
    fun `an unknown method is refused before any container is read`() {
        // Order matters: the refusal must not depend on a container being reachable,
        // otherwise "not compiled in" and "no graph" become the same answer.
        val host = ScreenInspectorHost { error("the container must not be read for an unknown method") }
        val envelope = host.handle("delete_everything", """{"op":"screens"}""")

        assertEquals("unsupported", errorCode(envelope))
        host.close()
    }

    @Test
    fun `malformed requests are refused with an argument error, never dispatched`() {
        val host = ScreenInspectorHost { error("no request should reach the container") }

        val cases =
            listOf(
                null to "",
                "" to "",
                "   " to "",
                "not json" to "",
                "[1,2,3]" to "",
                """{"op":"screens"""" to "",
                """{}""" to "",
                """{"op":""}""" to "",
                """{"op":42}""" to "",
            )

        cases.forEach { (argument, _) ->
            val envelope = host.handle(ScreenInspectorHost.METHOD, argument)
            assertEquals(
                "invalid_arguments",
                errorCode(envelope),
                "argument <$argument> was not refused as invalid input: $envelope",
            )
        }
        assertFalse(host.isStarted(), "an invalid request started a runtime")
        host.close()
    }

    @Test
    fun `an unknown op is refused rather than defaulted`() {
        val host = ScreenInspectorHost { error("an unknown op must not reach the container") }
        val envelope = host.handle(ScreenInspectorHost.METHOD, """{"op":"wipe_everything"}""")

        assertEquals("invalid_arguments", errorCode(envelope))
        assertTrue(
            envelope.contains("wipe_everything"),
            "the refusal does not name the op that was refused: $envelope",
        )
        host.close()
    }

    @Test
    fun `the envelope is the same shape the CLI and the MCP tools speak`() {
        // The contract version and the error envelope are what a client keys on. A host
        // that answered some other shape would be a second contract wearing the same name.
        val host = ScreenInspectorHost { null }
        val envelope = host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")

        val parsed = json.parseToJsonElement(envelope).jsonObject
        assertEquals("false", parsed["ok"]?.jsonPrimitive?.content)
        val error = parsed["error"]?.jsonObject ?: error("no error object in $envelope")
        assertTrue(error["code"]?.jsonPrimitive?.content?.isNotBlank() == true)
        assertTrue(error["message"]?.jsonPrimitive?.content?.isNotBlank() == true)
        host.close()
    }

    @Test
    fun `a request that cannot be served still returns a parseable envelope`() {
        // Nothing may cross a binder as an exception: the client would read it as the app
        // crashing, not as the request being refused.
        val host = ScreenInspectorHost { throw IllegalStateException("the container blew up") }
        val envelope = host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")

        val parsed = json.parseToJsonElement(envelope).jsonObject
        assertEquals("false", parsed["ok"]?.jsonPrimitive?.content)
        assertEquals("internal", errorCode(envelope))
        assertTrue(envelope.contains("blew up"), "the cause is not reported: $envelope")
        host.close()
    }

    @Test
    fun `closing the host releases the runtime and is safe to repeat`() {
        // The provider's caller is a lifecycle hook, not a protocol step, so a second stop
        // is not an error — and a process that never served a request has nothing to close.
        val host = ScreenInspectorHost { null }
        host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")
        host.close()
        host.close()

        assertFalse(host.isStarted())
        assertEquals("internal", errorCode(host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")))
    }

    @Test
    fun `a session opened in one request survives into the next`() {
        // The whole reason the runtime outlives a request. A per-request runtime would make
        // every session unusable one call after it was opened, which is why the CLI's
        // per-process model is not copied here.
        val app = koinApplication { modules(emptyList()) }
        val host = ScreenInspectorHost { app.koin }

        host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")
        host.handle(ScreenInspectorHost.METHOD, """{"op":"sessions"}""")

        assertTrue(host.isStarted(), "the runtime was created per request")
        host.close()
        app.close()
    }

    private fun errorCode(envelope: String): String? =
        json
            .parseToJsonElement(envelope)
            .jsonObject["error"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content
}
