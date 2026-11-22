package com.singularity.todo.mcp.introspection

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.Koin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal class ScreenInspectorRuntime(private val koin: Koin) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, ScreenSession>()
    private val currentSessionId = AtomicReference<String?>(null)

    fun capabilities(): JsonObject = ScreenRegistry.list()

    fun open(
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
