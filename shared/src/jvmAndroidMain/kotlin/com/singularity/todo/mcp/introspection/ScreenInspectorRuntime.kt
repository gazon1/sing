package com.singularity.todo.mcp.introspection

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.core.Koin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal class ScreenInspectorRuntime(
    private val koin: Koin,
    /**
     * How `open` waits for a fresh session to settle; `null` means the real wait. Injected so
     * a test can hold the wait open — the real screen settles too fast to cancel reliably,
     * which is exactly the race the cancellation test must not depend on. `null` rather than a
     * default lambda because the real wait is a member, which cannot be referenced before the
     * instance exists.
     */
    private val settle: (suspend (ScreenSession) -> Unit)? = null,
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, ScreenSession>()
    private val currentSessionId = AtomicReference<String?>(null)

    fun capabilities(): JsonObject = ScreenRegistry.list()

    suspend fun open(
        screenId: String,
        params: JsonObject,
    ): JsonObject {
        val capability =
            ScreenRegistry.capability(screenId)
                ?: throw InspectorException(
                    InspectorErrorCode.UNKNOWN_SCREEN,
                    "Unknown or unsupported screen '$screenId'.",
                    details =
                        buildJsonObject {
                            put("screen", screenId)
                            put(
                                "supportedScreens",
                                buildJsonArray {
                                    ScreenRegistry.capabilities().forEach { add(JsonPrimitive(it.id)) }
                                },
                            )
                        },
                )
        val session = ScreenRegistry.open(screenId, params, koin)
        // The session is registered *after* the wait, and closed if the wait does not finish.
        // `awaitSettled` suspends, which is a cancellation point; registering first would
        // leave a session nobody can name — still running its state collector — whenever a
        // caller cancels (the MCP host rethrows `CancellationException`). Registering after
        // also means `sessions()` never lists a screen that is not yet answerable.
        //
        // `settled` is the flag because the cleanup is the same whether the wait was
        // cancelled or threw: either way the caller never receives this session, so it must
        // not outlive the call. `finally` also keeps the `catch` free of statements before
        // `throw`, which `NoSwallowedCancellation` requires of a cancellation branch.
        var settled = false
        try {
            (settle ?: { awaitSettled(it) })(session)
            settled = true
        } finally {
            if (!settled) closeQuietly(session)
        }
        sessions[session.sessionId] = session
        currentSessionId.set(session.sessionId)
        return buildJsonObject {
            put("sessionId", session.sessionId)
            put("screen", session.screenId)
            put("params", session.params)
            put("description", capability.description)
            put("snapshot", session.snapshot())
            put("actions", session.actions())
        }
    }

    /**
     * Waits for a freshly opened session to leave `loading`.
     *
     * The session is created and its state collector starts on another dispatcher, so an
     * `open` that returned immediately reported `loading` — always, in the CLI, where the
     * process has nothing else to occupy it. That made the documented
     * inspect → invoke → verify sequence unusable: the caller had to guess how many
     * redundant `snapshot` calls stood between `open` and a state it could act on. Waiting
     * here is what makes the first observation mean something.
     *
     * A screen that never settles answers `loading` at the deadline rather than failing:
     * the state is genuinely unknown then, and `error` is a different answer.
     */
    private suspend fun awaitSettled(session: ScreenSession) {
        awaitSettledStatus(
            status = { session.snapshot()["status"]?.jsonPrimitive?.contentOrNull },
            timeoutMs = OPEN_SETTLE_TIMEOUT_MS,
        )
    }

    fun describe(sessionId: String?): JsonObject = session(sessionId).describe()

    fun snapshot(sessionId: String?): JsonObject = session(sessionId).snapshot()

    fun actions(sessionId: String?): JsonObject = session(sessionId).actions()

    suspend fun invoke(
        sessionId: String?,
        action: String,
        input: JsonObject,
    ): JsonObject = session(sessionId).invoke(action, input)

    suspend fun wait(
        sessionId: String?,
        operationId: String,
        timeoutMs: Long,
    ): JsonObject {
        if (timeoutMs < 0L) {
            throw InspectorException(
                InspectorErrorCode.INVALID_ARGUMENTS,
                "timeoutMs must be a non-negative integer.",
            )
        }
        return session(sessionId).wait(operationId, timeoutMs)
    }

    fun cancel(
        sessionId: String?,
        operationId: String,
    ): JsonObject = session(sessionId).cancel(operationId)

    fun close(sessionId: String?): JsonObject {
        val id = resolveSessionId(sessionId)
        val removed =
            sessions.remove(id)
                ?: throw sessionNotFound(id)
        try {
            removed.close()
        } finally {
            // Closing the current session must not silently retarget implicit operations to an
            // arbitrary remaining session; require explicit sessionId when multiple remain.
            currentSessionId.compareAndSet(id, null)
        }
        return buildJsonObject {
            put("sessionId", id)
            put("closed", true)
        }
    }

    fun sessions(): JsonObject =
        buildJsonObject {
            put("currentSessionId", currentSessionId.get()?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "sessions",
                buildJsonArray {
                    sessions.values.sortedBy { it.sessionId }.forEach { session ->
                        add(
                            buildJsonObject {
                                put("sessionId", session.sessionId)
                                put("screen", session.screenId)
                                put("params", session.params)
                            },
                        )
                    }
                },
            )
        }

    override fun close() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        currentSessionId.set(null)
    }

    private fun session(sessionId: String?): ScreenSession {
        val id = resolveSessionId(sessionId)
        return sessions[id] ?: throw sessionNotFound(id)
    }

    private fun resolveSessionId(sessionId: String?): String {
        val explicit = sessionId?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit
        val current = currentSessionId.get()
        if (current != null && sessions.containsKey(current)) return current
        val only = sessions.keys.singleOrNull()
        if (only != null) return only
        throw InspectorException(
            InspectorErrorCode.INVALID_ARGUMENTS,
            if (sessions.isEmpty()) {
                "No screen session is open. Open a screen first."
            } else {
                "Multiple screen sessions are open; specify 'sessionId'."
            },
            details = sessions(),
        )
    }

    private fun sessionNotFound(id: String): InspectorException =
        InspectorException(
            InspectorErrorCode.SESSION_NOT_FOUND,
            "Unknown screen session '$id'.",
            details =
                buildJsonObject {
                    put("sessionId", id)
                    put("openSessions", sessions())
                },
        )
}

/**
 * Waits until [status] reports something other than `loading`, or [timeoutMs] elapses.
 *
 * Extracted from [ScreenInspectorRuntime.open] so the deadline branch is testable without a
 * screen that refuses to settle: the caller passes the clock reading it already has, and the
 * test passes a status that never changes.
 *
 * Returns normally when the deadline passes. A screen still `loading` at that point is the
 * observed state, not a failure — `error` is a different answer and only the screen can give
 * it.
 */
internal suspend fun awaitSettledStatus(
    status: () -> String?,
    timeoutMs: Long,
    pollMs: Long = SETTLE_POLL_MS,
    now: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
) {
    val deadline = now() + timeoutMs
    while (now() < deadline) {
        if (status() != "loading") return
        sleep(pollMs)
    }
}

/** How long `open` waits for the screen to leave `loading` before reporting it as it is. */
private const val OPEN_SETTLE_TIMEOUT_MS = 5_000L

/**
 * Closes a session whose `open` was cancelled, without letting a close failure escape.
 *
 * A plain function, not an inline `try` in `open`: `close()` cannot suspend, so nothing here
 * needs to re-throw `CancellationException` — and putting the `try` outside the suspend
 * function is what keeps the cancellation catch in `open` the first one, which is the
 * ordering `NoSwallowedCancellation` requires.
 */
private fun closeQuietly(session: ScreenSession) {
    try {
        session.close()
    } catch (_: Exception) {
        // The cancellation is the outcome that matters; a close failure is noise here.
    }
}

/** How often that wait re-reads the snapshot. */
private const val SETTLE_POLL_MS = 20L
