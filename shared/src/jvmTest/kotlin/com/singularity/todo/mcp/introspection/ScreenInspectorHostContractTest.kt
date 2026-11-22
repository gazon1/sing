package com.singularity.todo.mcp.introspection

import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.Koin
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
    fun `the runtime outlives a request instead of being rebuilt for each one`() {
        // The reason the host holds one runtime: `open` in one `content call` and `invoke`
        // in the next is the whole workflow, so a per-request runtime would make every
        // session unusable one call after it was opened.
        //
        // What this asserts is exactly that — the runtime is created once and reused, which
        // is the property a per-request implementation would break. What it does NOT
        // assert is that a *session* survives, because a session needs a real task and a
        // real container; `ScreenInspectorRuntimeTest` owns that against a real database.
        // The name says what is checked, so the gap is visible instead of implied.
        val app = koinApplication { modules(emptyList()) }
        val host = ScreenInspectorHost { app.koin }

        host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")
        assertTrue(host.isStarted(), "the first request did not create a runtime")

        host.handle(ScreenInspectorHost.METHOD, """{"op":"sessions"}""")
        assertTrue(host.isStarted(), "the runtime was rebuilt for the second request")

        host.close()
        assertFalse(host.isStarted(), "closing the host left the runtime alive")
        app.close()
    }

    @Test
    fun `an invalid waitMs is refused before the action is dispatched`() {
        // The defect both reviewers found: `waitMs` was read AFTER `runtime.invoke`, so a
        // request carrying a negative or non-numeric waitMs performed the domain action and
        // then answered `invalid_arguments`. The client saw a refusal for a write that had
        // already happened, with no way to tell.
        //
        // The container is real but holds no session, and that is what makes the assertion
        // sharp: if the request is refused *before* dispatch the message names `waitMs`; if
        // it were dispatched first, the failure would be the runtime's "no screen session is
        // open" instead. The message, not just the error code, is what tells the two apart —
        // both are `invalid_arguments`.
        val app = koinApplication { modules(emptyList()) }
        val host = ScreenInspectorHost { app.koin }

        val badWaits = listOf("-1", "\"soon\"", "true", "{}", "[]")
        badWaits.forEach { waitMs ->
            val envelope =
                host.handle(
                    ScreenInspectorHost.METHOD,
                    """{"op":"invoke","action":"set_priority","input":{"priority":"high"},"waitMs":$waitMs}""",
                )
            assertEquals(
                "invalid_arguments",
                errorCode(envelope),
                "waitMs=$waitMs was not refused as invalid input: $envelope",
            )
            assertTrue(
                envelope.contains("waitMs"),
                "waitMs=$waitMs was rejected by the runtime rather than by the request " +
                    "validation, which means the action had already been dispatched: $envelope",
            )
        }
        host.close()
        app.close()
    }

    @Test
    fun `a request that is refused does not leave a runtime holding its container`() {
        // The invariant is "validate before anything is created", not merely "before the
        // action is dispatched". Creating the runtime at the top of the dispatch meant a
        // request that was about to be refused still initialized one — and that runtime
        // kept the container it captured, so a later container swap would be invisible.
        val app = koinApplication { modules(emptyList()) }
        val host = ScreenInspectorHost { app.koin }

        val envelope = host.handle(ScreenInspectorHost.METHOD, """{"op":"open"}""")
        assertEquals("invalid_arguments", errorCode(envelope))
        assertFalse(
            host.isStarted(),
            "a refused request created a runtime; it now holds the container it captured",
        )

        host.close()
        app.close()
    }

    @Test
    fun `a replaced container fails closed instead of dispatching through the old graph`() {
        // The runtime captures the container it was built from, so a *different* container
        // means the open sessions belong to a graph that is gone. That is a programming
        // error, and the honest answer is `internal` — not a silent swap, and certainly not
        // a request dispatched against a graph nobody asked for.
        //
        // Both containers hold a container-shaped object and neither holds any binding, so
        // the assertion cannot pass by the *content* of the containers differing. The
        // message is what distinguishes the paths: a host that reused the stale runtime
        // would have reached `ScreenRegistry` and produced a session or a registry error,
        // never this refusal.
        val first = koinApplication { modules(emptyList()) }
        val second = koinApplication { modules(emptyList()) }
        var current: Koin = first.koin
        val host = ScreenInspectorHost { current }

        host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")
        assertTrue(host.isStarted(), "the first request did not create a runtime")

        current = second.koin
        val envelope = host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")

        val parsed = json.parseToJsonElement(envelope).jsonObject
        assertEquals("false", parsed["ok"]?.jsonPrimitive?.content, "a replaced container was not refused: $envelope")
        assertEquals("internal", errorCode(envelope))
        assertTrue(
            envelope.contains("container was replaced"),
            "the refusal did not come from the container-change check, so the host reused the " +
                "stale runtime instead of failing closed: $envelope",
        )

        host.close()
        assertFalse(host.isStarted())
        first.close()
        second.close()
    }

    @Test
    fun `an unbounded wait is refused before anything is dispatched`() {
        // The provider answers on a binder thread. `Long.MAX_VALUE` is a legal JSON number,
        // and accepting it parks that thread for effectively ever; enough of them exhaust
        // the process's binder threads and unrelated IPC in the app stops answering.
        val app = koinApplication { modules(emptyList()) }
        val host = ScreenInspectorHost { app.koin }

        // `waitMs` belongs to `invoke` and `timeoutMs` to `wait`; each is bounded by the
        // same rule, and the refusal names the field that was out of range — which is also
        // how the assertion knows the bound fired rather than some later failure.
        val requests =
            mapOf(
                "waitMs" to
                    """{"op":"invoke","action":"set_priority","input":{"priority":"high"},"waitMs":9223372036854775807}""",
                "timeoutMs" to
                    """{"op":"wait","operationId":"op-1","timeoutMs":9223372036854775807}""",
            )
        requests.forEach { (field, request) ->
            val envelope = host.handle(ScreenInspectorHost.METHOD, request)
            assertEquals("invalid_arguments", errorCode(envelope), "$field was not bounded: $envelope")
            assertTrue(envelope.contains(field), "the refusal does not name $field: $envelope")
        }

        host.close()
        app.close()
    }

    @Test
    fun `a closed host stays closed instead of building a new runtime`() {
        // Teardown has to be terminal for the *object*, not just for the runtime it held. A
        // request that obtained the host just before the process-wide reference was cleared
        // would otherwise resume after the teardown and build a new runtime on a host nobody
        // owns — sessions attached to an orphan, invisible to the next `sessions` call and to
        // any later `stop()`.
        val app = koinApplication { modules(emptyList()) }
        val host = ScreenInspectorHost { app.koin }

        host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")
        assertTrue(host.isStarted(), "the first request did not create a runtime")

        host.close()
        assertFalse(host.isStarted())

        val envelope = host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")
        assertEquals("internal", errorCode(envelope), "a closed host served a request: $envelope")
        assertTrue(
            envelope.contains("has been closed"),
            "the refusal did not come from the closed-host check: $envelope",
        )
        assertFalse(
            host.isStarted(),
            "a request on a closed host built a new runtime — the teardown was not terminal",
        )
        app.close()
    }

    @Test
    fun `the first request to a fresh host succeeds rather than unlocking a lock it let go`() {
        // The device found this in one command; the suite did not, because no test asserted
        // that the *first* request on an empty host returned a success envelope. The create
        // path drops the read lock, and the caller then released it a second time — so the
        // dispatch ran unlocked and the envelope came back `internal`, while the runtime was
        // created correctly and every later request worked. That is exactly the shape that
        // hides from a test which only checks `isStarted()`.
        val app = koinApplication { modules(emptyList()) }
        val host = ScreenInspectorHost { app.koin }

        val envelope = host.handle(ScreenInspectorHost.METHOD, """{"op":"screens"}""")
        val parsed = json.parseToJsonElement(envelope).jsonObject
        assertEquals(
            "true",
            parsed["ok"]?.jsonPrimitive?.content,
            "the first request on a fresh host did not succeed: $envelope",
        )
        assertTrue(
            parsed["result"]?.jsonObject?.get("screens") != null,
            "the first request was answered without the screen list: $envelope",
        )

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
