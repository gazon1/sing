@file:Suppress(
    // The host is driven by a synchronous caller — a binder thread or a CLI process — that
    // has one request and one answer; runBlocking is the sync-to-async bridge at that
    // boundary, never a parked thread inside the app's own coroutine work.
    "NoRunBlocking",
    // A request may fail with anything (JSON, Room, reflection); every failure must become
    // a structured JSON error instead of a thrown exception crossing the binder, so the
    // catch is broad on purpose and the text is reported rather than discarded.
    "TooGenericExceptionCaught",
    // That same boundary must survive its own reporting: an encoding failure inside the
    // error path cannot be allowed to replace the error with a stack trace.
    "SwallowedException",
)

package com.singularity.todo.mcp.introspection

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.koin.core.Koin

/**
 * The screen-inspector contract, reachable from a running application process by a
 * same-device debug/test client.
 *
 * ## Why this exists
 *
 * The desktop host is the tool-server process, which builds its own Koin container and
 * therefore inspects *a* real session of the screen rather than the window the user has
 * open (ADR `2026-11-21-the-host-inspects-a-real-graph-session-not-the-window.md`). An
 * Android process has no such entry point: the app is the only process, and its graph is
 * created by `SingularityApp.onCreate`. On Android the host therefore attaches to the
 * graph that is actually running — the same objects the UI uses, because there is no
 * second graph to build.
 *
 * ## The contract, not the transport
 *
 * This class is transport-free on purpose: it takes a method name and a JSON argument and
 * returns the JSON envelope the CLI and the MCP tools already speak
 * (`{"ok":true,"result":…}` / `{"ok":false,"error":{…}}`). The debug `ContentProvider` in
 * `androidApp/src/debug` is one caller; the JVM tests are another. Adding a transport is
 * therefore not a change to the contract, which is what makes "the same logical contract
 * as the JVM Desktop host" a fact rather than a claim.
 *
 * ## Lifecycle
 *
 * One runtime per host instance, created on the first request and released by [stop].
 * Sessions live across requests on purpose: `open` in one call and `invoke` in the next is
 * the whole point, and a per-request runtime would make every session unusable one call
 * after it was opened. That is the opposite of the CLI's process model — correct there,
 * because each CLI invocation *is* its own process.
 *
 * No background thread, no service, no socket: the runtime does work only while a request
 * is being served, and [stop] releases the sessions it holds.
 *
 * ## Fail-closed
 *
 * The container is supplied by the caller ([koinProvider]), so a host built against a
 * container that is gone answers `internal` instead of silently building a second one:
 * a host that bootstrapped its own container would be inspecting a different application
 * while claiming to inspect this one.
 *
 * The release boundary is not a runtime check: the provider that calls the process host
 * lives in `androidApp/src/debug` and is absent from a release build
 * (`ScreenInspectorHostIsDebugOnlyTest`).
 */
class ScreenInspectorHost(private val koinProvider: () -> Koin?) : AutoCloseable {
    private val lock = Any()

    private var runtime: ScreenInspectorRuntime? = null

    private val requestJson =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    /**
     * Serves one request and returns the JSON envelope.
     *
     * [argument] is the request object: `{"op":"open","params":{"taskId":"…"}}`, whose
     * fields are the CLI's own (`op`, `screen`, `params`, `action`, `input`, `sessionId`,
     * `operationId`, `waitMs`, `timeoutMs`). It is read whole — an unknown `op` is refused,
     * never treated as a default — so a typo fails loudly instead of doing nothing.
     *
     * A request that cannot be served returns a structured error envelope with `ok:false`;
     * nothing is thrown across the boundary.
     */
    fun handle(
        method: String,
        argument: String?,
    ): String {
        val envelope =
            if (method != METHOD) {
                err(
                    InspectorErrorCode.UNSUPPORTED.wire,
                    "Unknown host method '$method'; this host serves '$METHOD'.",
                    details = buildJsonObject { put("method", method) },
                )
            } else {
                // The whole boundary, not just the dispatch: the container provider itself
                // can fail, and an exception escaping here crosses the binder as a crash of
                // the app rather than a refusal of the request.
                try {
                    serve(argument)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: InspectorException) {
                    e.toJson()
                } catch (e: Exception) {
                    err(InspectorErrorCode.INTERNAL.wire, e.message ?: "Screen inspector host failed.")
                }
            }
        return encode(envelope)
    }

    override fun close() {
        val closed = synchronized(lock) { runtime.also { runtime = null } }
        closed?.let { runCatching { it.close() } }
    }

    /** True once a request has created the runtime; the host's diagnostics read it. */
    fun isStarted(): Boolean = synchronized(lock) { runtime != null }

    private fun encode(element: JsonElement): String =
        try {
            requestJson.encodeToString(JsonElement.serializer(), element)
        } catch (e: Exception) {
            // An envelope that cannot be encoded is still an answer, and the caller gets a
            // parseable one: the alternative is an exception crossing the binder, which the
            // client would read as a crash of the *app* rather than a refusal of the request.
            val fallback =
                err(
                    InspectorErrorCode.INTERNAL.wire,
                    "The host could not encode its response: ${e.message ?: e.javaClass.simpleName}.",
                )
            requestJson.encodeToString(JsonElement.serializer(), fallback)
        }

    @Suppress("ReturnCount") // one refusal per malformed input, in the order they are checkable
    private fun serve(argument: String?): JsonObject {
        if (argument.isNullOrBlank()) {
            return err(
                InspectorErrorCode.INVALID_ARGUMENTS.wire,
                "The host request is empty; pass a JSON object such as {\"op\":\"screens\"}.",
            )
        }
        val request =
            try {
                requestJson.parseToJsonElement(argument)
            } catch (e: Exception) {
                return err(
                    InspectorErrorCode.INVALID_ARGUMENTS.wire,
                    "The host request is not valid JSON: ${e.message ?: "parse error"}.",
                )
            } as? JsonObject
                ?: return err(
                    InspectorErrorCode.INVALID_ARGUMENTS.wire,
                    "The host request must be a JSON object.",
                )

        // A string, and one of ours. `contentOrNull` would accept `{"op":42}` and read it
        // as "42" — a number is not a command name, and a host that coerced it would be
        // widening what it accepts to make a malformed request look valid.
        val opPrimitive = request["op"] as? JsonPrimitive
        val op =
            opPrimitive
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotBlank() }
                ?: return err(
                    InspectorErrorCode.INVALID_ARGUMENTS.wire,
                    "The host request needs 'op' as a non-empty string, such as {\"op\":\"screens\"}.",
                )

        // Refused before the container is read, so that "not a valid request" and "no
        // graph to drive" stay different answers: an unknown op must never look like a
        // host that failed to start.
        if (op !in OPS) {
            return err(
                InspectorErrorCode.INVALID_ARGUMENTS.wire,
                "Unknown host op '$op'.",
                details =
                    buildJsonObject {
                        put("op", op)
                        put("supportedOps", buildJsonArray { OPS.forEach { add(JsonPrimitive(it)) } })
                    },
            )
        }

        val koin =
            koinProvider()
                ?: return err(
                    InspectorErrorCode.INTERNAL.wire,
                    "No running application container: the screen-inspector host drives the " +
                        "application's own graph, and it must be started before a request arrives.",
                )

        // Dispatch failures are caught at `handle`, which is the boundary; catching twice
        // would only mean two places to keep in step.
        return ok(dispatch(op, request, koin))
    }

    @Suppress("LongMethod") // one branch per op, mirroring the CLI's dispatch table
    private fun dispatch(
        op: String,
        request: JsonObject,
        koin: Koin,
    ): JsonObject {
        val runtime = runtimeOrCreate(koin)
        return when (op) {
            "screens" -> {
                runtime.capabilities()
            }

            "sessions" -> {
                runtime.sessions()
            }

            "open" -> {
                runtime.open(request.string("screen"), request.obj("params"))
            }

            "describe" -> {
                runtime.describe(request.optString("sessionId"))
            }

            "snapshot", "inspect" -> {
                runtime.snapshot(request.optString("sessionId"))
            }

            "actions" -> {
                runtime.actions(request.optString("sessionId"))
            }

            "invoke" -> {
                val sessionId = request.optString("sessionId")
                val action = request.string("action")
                // Every field is validated BEFORE the dispatch. `waitMs` is only an
                // observation window, but reading it after `invoke` meant a request with a
                // negative `waitMs` performed the domain action and then answered
                // `invalid_arguments` — a real mutation reported as a refused request, and
                // the client has no way to know the write happened.
                val input = request.obj("input")
                val waitMs = request.nonNegativeLongOrNull("waitMs")
                val result = runBlocking { runtime.invoke(sessionId, action, input) }
                val operationId = result["operationId"]?.jsonPrimitive?.contentOrNull
                if (waitMs == null || operationId == null) {
                    result
                } else {
                    buildJsonObject {
                        result.forEach { (key, value) -> put(key, value) }
                        put("wait", runBlocking { runtime.wait(sessionId, operationId, waitMs) })
                    }
                }
            }

            "wait" -> {
                runBlocking {
                    runtime.wait(
                        request.optString("sessionId"),
                        request.string("operationId"),
                        request.nonNegativeLongOrNull("timeoutMs") ?: DEFAULT_WAIT_MS,
                    )
                }
            }

            "cancel" -> {
                runtime.cancel(request.optString("sessionId"), request.string("operationId"))
            }

            "close" -> {
                runtime.close(request.optString("sessionId"))
            }

            else -> {
                throw InspectorException(InspectorErrorCode.INVALID_ARGUMENTS, "Unknown host op '$op'.")
            }
        }
    }

    private fun runtimeOrCreate(koin: Koin): ScreenInspectorRuntime =
        synchronized(lock) {
            runtime
                ?: ScreenInspectorRuntime(koin).also { runtime = it }
        }

    private fun JsonObject.string(key: String): String {
        val element =
            this[key]
                ?: throw InspectorException(InspectorErrorCode.INVALID_ARGUMENTS, "Missing required field '$key'.")
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw InspectorException(InspectorErrorCode.INVALID_ARGUMENTS, "Field '$key' must be a JSON string.")
        }
        return primitive.content
    }

    private fun JsonObject.optString(key: String): String? {
        val element = this[key] ?: return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw InspectorException(InspectorErrorCode.INVALID_ARGUMENTS, "Field '$key' must be a JSON string.")
        }
        return primitive.content
    }

    private fun JsonObject.obj(key: String): JsonObject {
        val value = this[key] ?: return JsonObject(emptyMap())
        return value as? JsonObject
            ?: throw InspectorException(InspectorErrorCode.INVALID_ARGUMENTS, "Field '$key' must be a JSON object.")
    }

    /** A duration the caller supplied: absent, or a non-negative whole number. */
    private fun JsonObject.nonNegativeLongOrNull(key: String): Long? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        // A string is not a number, a boolean is not a number, and a negative duration is
        // not a duration. All three are the same refusal, so they are one branch.
        val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        return value?.takeIf { it >= 0L }
            ?: throw InspectorException(
                InspectorErrorCode.INVALID_ARGUMENTS,
                "Field '$key' must be a non-negative integer.",
            )
    }

    companion object {
        /** The request method the host serves; anything else is refused. */
        const val METHOD: String = "screen_inspector"

        /**
         * Every op, in one place.
         *
         * The dispatch `when` below still names each one — that is where the behavior
         * lives. This list exists so a request is refused *before* a container is touched,
         * which is what keeps "unknown op" from being reported as "no container".
         */
        private val OPS =
            setOf(
                "screens",
                "sessions",
                "open",
                "describe",
                "snapshot",
                "inspect",
                "actions",
                "invoke",
                "wait",
                "cancel",
                "close",
            )

        private const val DEFAULT_WAIT_MS = 30_000L
    }
}
