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
import java.util.concurrent.locks.ReentrantReadWriteLock

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
    /**
     * Readers are requests, the writer is the teardown.
     *
     * A plain monitor would have made every request wait for every other one, and a queue of
     * requests each holding a binder thread is the exhaustion the wait cap exists to
     * prevent — the cap would not have helped. Requests may therefore overlap; only
     * [close] excludes them, which is the one thing that actually must not run concurrently
     * with a dispatch.
     */
    private val gate = ReentrantReadWriteLock()

    private var runtime: ScreenInspectorRuntime? = null

    /** The container [runtime] was built from; a different one means a stale graph. */
    private var currentKoin: Koin? = null

    /**
     * Set once [close] has run. A closed host is dead, not recyclable.
     *
     * Without this, a request that obtained the host just before the process-wide reference
     * was cleared would resume after the teardown and build a *new* runtime on a host nobody
     * owns any more — sessions attached to an orphan, invisible to the next `sessions` call
     * and to any later `stop()`. Teardown has to be terminal for the object, not just for
     * the runtime it happened to hold.
     */
    private var closed = false

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

    /**
     * Releases the runtime, holding the write side of the lock [withRuntime] reads.
     *
     * Closing *inside* the lock is the point, not an accident: a teardown that released the
     * lock first would leave a window where a request obtains the runtime and then finds its
     * sessions closed underneath it — it would report failure for an action it had already
     * performed, which is the exact class of bug this host has already paid for once.
     */
    override fun close() {
        val write = gate.writeLock()
        write.lock()
        try {
            closed = true
            runCatching { runtime?.close() }
            runtime = null
            currentKoin = null
        } finally {
            write.unlock()
        }
    }

    /** True once a request has created the runtime; the host's diagnostics read it. */
    fun isStarted(): Boolean {
        val read = gate.readLock()
        read.lock()
        return try {
            runtime != null
        } finally {
            read.unlock()
        }
    }

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

    /**
     * Dispatches an already-validated op.
     *
     * Every branch reads and validates its own fields **before** the runtime is touched.
     * That order is the invariant, and it is why the runtime is obtained inside each branch
     * rather than once at the top: creating it first meant a request that was about to be
     * refused still initialized a runtime, which then kept the container it captured.
     */
    @Suppress("LongMethod") // one branch per op, mirroring the CLI's dispatch table
    private fun dispatch(
        op: String,
        request: JsonObject,
        koin: Koin,
    ): JsonObject =
        when (op) {
            "screens" -> {
                withRuntime(koin) { it.capabilities() }
            }

            "sessions" -> {
                withRuntime(koin) { it.sessions() }
            }

            "open" -> {
                val screen = request.string("screen")
                val params = request.obj("params")
                withRuntime(koin) { it.open(screen, params) }
            }

            "describe" -> {
                val sessionId = request.optString("sessionId")
                withRuntime(koin) { it.describe(sessionId) }
            }

            "snapshot", "inspect" -> {
                val sessionId = request.optString("sessionId")
                withRuntime(koin) { it.snapshot(sessionId) }
            }

            "actions" -> {
                val sessionId = request.optString("sessionId")
                withRuntime(koin) { it.actions(sessionId) }
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
                withRuntime(koin) { runtime ->
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
            }

            "wait" -> {
                val sessionId = request.optString("sessionId")
                val operationId = request.string("operationId")
                val timeoutMs = request.nonNegativeLongOrNull("timeoutMs") ?: DEFAULT_WAIT_MS
                withRuntime(koin) { runBlocking { it.wait(sessionId, operationId, timeoutMs) } }
            }

            "cancel" -> {
                val sessionId = request.optString("sessionId")
                val operationId = request.string("operationId")
                withRuntime(koin) { it.cancel(sessionId, operationId) }
            }

            "close" -> {
                val sessionId = request.optString("sessionId")
                withRuntime(koin) { it.close(sessionId) }
            }

            else -> {
                throw InspectorException(InspectorErrorCode.INVALID_ARGUMENTS, "Unknown host op '$op'.")
            }
        }

    /**
     * Runs [block] against the runtime, holding the read lock for its whole duration.
     *
     * Two properties come from that lock, and they are the reason it is held across the
     * call rather than only around the lookup:
     *
     * - **A closed runtime is never in use.** [close] holds the write lock, so a request
     *   cannot be mid-dispatch while its runtime is being torn down. Replacing the runtime
     *   under a live request was the defect an earlier revision of this host had: one
     *   request closed the runtime another was still using, and the second request reported
     *   failure for an action it had already performed.
     * - **A changed container fails closed.** The runtime captures the container it was
     *   built from, so a different container means the graph is not the one the sessions
     *   belong to. That is not something to paper over by swapping the runtime — it is a
     *   programming error, and the honest answer is `internal`, not a request silently
     *   dispatched against a graph nobody asked for.
     *
     * A *read* lock, so requests overlap. A plain monitor would have serialised every
     * request behind every other, and a queue of requests each holding a binder thread is
     * exactly the thread exhaustion the wait cap exists to prevent — the cap would not have
     * helped. Only [close] takes the write lock, which is the one thing that must not run
     * concurrently with a dispatch.
     *
     * **This is protected by construction, not by a test.** A test that races a close
     * against a request cannot fail here without a real container and a blocking op: the
     * runtime's `sessions` map is concurrent, so a torn teardown looks like an empty list
     * rather than an error, and an attempt at such a test passed with the close moved back
     * outside the lock. It was removed rather than kept as a green checkmark over nothing.
     * The property it would have pinned is visible in this file instead: every dispatch
     * branch goes through [withRuntime] under the read lock, and [close] holds the write
     * lock while it tears the runtime down.
     */
    private fun <T> withRuntime(
        koin: Koin,
        block: (ScreenInspectorRuntime) -> T,
    ): T {
        // Ownership is explicit: [admit] says whether this frame holds the read lock, and
        // only then is it released. The first version released it unconditionally in a
        // `finally` while [acquireRuntime] had already dropped it on the create path — a
        // double unlock, which threw `attempt to unlock read lock` on the very first request
        // to a fresh host. It survived the unit tests because they never made two calls on an
        // empty host in the same shape; the device found it immediately.
        // Every path out of [admit] holds the read lock — the create path drops it only to
        // take the write lock and then re-takes it. There is no boolean to get wrong: if
        // admit returns, this frame owns one read hold, and it is released here.
        val runtime = admit(koin)
        try {
            return block(runtime)
        } finally {
            gate.readLock().unlock()
        }
    }

    /**
     * Admits one request and returns its runtime, holding the read lock on return.
     *
     * The create path cannot keep the read lock through the create: a read-to-write upgrade is
     * not possible on a `ReentrantReadWriteLock` and attempting one deadlocks. It drops the
     * read lock, creates under the write lock, and then **re-takes** the read lock before
     * returning — otherwise the very first request on a fresh host would dispatch with no lock
     * held, and a concurrent `close()` could tear the runtime down underneath it. Both earlier
     * versions of this function got a different half of that wrong.
     */
    private fun admit(koin: Koin): ScreenInspectorRuntime {
        val read = gate.readLock()
        read.lock()
        // Decide while holding the read lock, then act. A throw from a refusal releases it
        // here first, so the caller's frame never sees a lock it did not take.
        when (val state = currentState(koin)) {
            is RuntimeState.Usable -> {
                return state.runtime
            }

            RuntimeState.Absent -> {
                read.unlock()
                // Create, then RE-TAKE the read lock before handing the runtime out. Without
                // that last step the first request on a fresh host would dispatch with no lock
                // held at all — a concurrent `close()` could tear the runtime down underneath
                // it, which is the very race this lock exists to prevent. Dropping the read
                // lock for the create is unavoidable (a read-to-write upgrade deadlocks);
                // running the request unlocked afterwards is not.
                createRuntime(koin)
                return admitAfterCreate(koin)
            }

            RuntimeState.Closed, RuntimeState.Replaced -> {
                refusal(state) { read.unlock() }
            }
        }
    }

    /**
     * Re-admits after a create, so the request runs under the read lock.
     *
     * The state is read again rather than assumed: a `close()` or a container swap can land
     * in the window between the create and this call, and both must fail closed rather than
     * hand out a runtime that is already gone.
     */
    private fun admitAfterCreate(koin: Koin): ScreenInspectorRuntime {
        val read = gate.readLock()
        read.lock()
        return when (val state = currentState(koin)) {
            is RuntimeState.Usable -> state.runtime

            // Any non-usable state here means a close or a container swap landed in the
            // window between the create and this call; the lock is already released by
            // `refusal` before it throws, so this frame never strands it.
            else -> refusal(state) { read.unlock() }
        }
    }

    /**
     * Throws the refusal [state] describes, releasing [read] first.
     *
     * One place for the three refusals so each caller stays under the throw-count rule, and so
     * the release-before-throw ordering is written once instead of three times.
     */
    private fun refusal(
        state: RuntimeState,
        release: () -> Unit,
    ): Nothing {
        release()
        throw InspectorException(
            InspectorErrorCode.INTERNAL,
            // One throw, with the message selected from the state — the three refusals differ
            // only in what they say, and three `throw`s in one function is the shape detekt's
            // throw-count rule (rightly) refuses.
            when (state) {
                is RuntimeState.Usable -> {
                    "refusal called for a usable runtime; that is a programming error"
                }

                RuntimeState.Absent -> {
                    "The screen-inspector host lost its runtime while creating it."
                }

                RuntimeState.Closed -> {
                    "This screen-inspector host has been closed; the process created a new one."
                }

                RuntimeState.Replaced -> {
                    "The application container was replaced while the screen inspector was " +
                        "running; the open sessions belong to the previous graph. Restart the " +
                        "host before inspecting again."
                }
            },
        )
    }

    /** What the host currently holds, under the caller's read lock. */
    private fun currentState(koin: Koin): RuntimeState {
        // `closed` is tested FIRST. After a teardown both `runtime` is null and `closed` is
        // set, and reading them the other way round reported `Absent` — which sent the caller
        // down the create path on a dead host, and (before this ordering) tried to release a
        // read lock it had already given up.
        if (closed) return RuntimeState.Closed
        val current = runtime
        return when {
            current == null -> RuntimeState.Absent
            currentKoin === koin -> RuntimeState.Usable(current)
            else -> RuntimeState.Replaced
        }
    }

    private sealed interface RuntimeState {
        class Usable(val runtime: ScreenInspectorRuntime) : RuntimeState

        data object Absent : RuntimeState

        data object Closed : RuntimeState

        data object Replaced : RuntimeState
    }

    /**
     * Creates the runtime under the write lock, or returns the one a racing request made.
     *
     * The re-check is the point: two requests arriving on an empty host both see `null`,
     * both release their read lock, and one of them must not throw away the other's runtime.
     */
    private fun createRuntime(koin: Koin): ScreenInspectorRuntime {
        val write = gate.writeLock()
        write.lock()
        try {
            // Two requests can both observe `Absent` and both arrive here; the loser takes
            // the winner's runtime instead of replacing it.
            when (val state = currentState(koin)) {
                is RuntimeState.Usable -> {
                    return state.runtime
                }

                RuntimeState.Absent -> {
                    return ScreenInspectorRuntime(koin).also {
                        runtime = it
                        currentKoin = koin
                    }
                }

                RuntimeState.Closed -> {
                    throw InspectorException(
                        InspectorErrorCode.INTERNAL,
                        "This screen-inspector host has been closed; the process created a new one.",
                    )
                }

                RuntimeState.Replaced -> {
                    throw InspectorException(
                        InspectorErrorCode.INTERNAL,
                        "The application container was replaced while the screen inspector was " +
                            "running; the open sessions belong to the previous graph. Restart " +
                            "the host before inspecting again.",
                    )
                }
            }
        } finally {
            write.unlock()
        }
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

    /**
     * A duration the caller supplied: absent, or a whole number within [MAX_WAIT_MS].
     *
     * The upper bound is not cosmetic. The provider serves the request on a binder thread,
     * and `Long.MAX_VALUE` is a legal JSON number — accepting it would park that thread for
     * essentially ever. Enough such requests exhaust the process's binder threads, and then
     * *unrelated* IPC in the app stops answering. A debug surface must not be able to do
     * that, so a value above [MAX_WAIT_MS] is refused before anything is dispatched.
     */
    private fun JsonObject.nonNegativeLongOrNull(key: String): Long? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        // A string is not a number, a boolean is not a number, a negative duration is not a
        // duration, and an unbounded one is not a bounded wait. All four are one refusal.
        val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        return value?.takeIf { it in 0L..MAX_WAIT_MS }
            ?: throw InspectorException(
                InspectorErrorCode.INVALID_ARGUMENTS,
                "Field '$key' must be an integer between 0 and $MAX_WAIT_MS.",
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

        /**
         * The longest a single request may occupy its binder thread.
         *
         * Above the default (30s) so a caller can ask for more observation time, low enough
         * that a handful of stalled requests cannot hold every binder thread the process
         * has. See [nonNegativeLongOrNull].
         */
        private const val MAX_WAIT_MS = 120_000L
    }
}
