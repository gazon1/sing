package com.singularity.todo.mcp.introspection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class OperationStatus(val wire: String) {
    ACCEPTED("accepted"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
}

internal data class OperationRecord(
    val id: String,
    val action: String,
    val status: OperationStatus,
    val message: String? = null,
)

internal class OperationState(val id: String, val action: String) {
    private val done = CompletableDeferred<OperationRecord>()

    @Volatile
    var current: OperationRecord = OperationRecord(id, action, OperationStatus.ACCEPTED)
        internal set

    fun complete(record: OperationRecord) {
        current = record
        if (!done.isCompleted) done.complete(record)
    }

    fun isDone(): Boolean = done.isCompleted

    suspend fun await(timeoutMs: Long): OperationRecord {
        if (timeoutMs <= 0) return current
        if (done.isCompleted) return current
        val result = withTimeoutOrNull(timeoutMs) { done.await() }
        if (result != null) return result
        val timeoutRecord =
            OperationRecord(
                id = id,
                action = action,
                status = OperationStatus.TIMEOUT,
                message = "Timed out after ${timeoutMs}ms waiting for operation completion.",
            )
        if (!done.isCompleted) complete(timeoutRecord)
        return current
    }
}

internal class OperationTracker {
    private val ops = ConcurrentHashMap<String, OperationState>()

    fun create(action: String): OperationState {
        val state = OperationState(UUID.randomUUID().toString(), action)
        ops[state.id] = state
        return state
    }

    fun get(id: String): OperationState? = ops[id]

    fun cancel(id: String): OperationState? {
        val op = ops[id] ?: return null
        if (!op.isDone()) {
            op.complete(
                OperationRecord(
                    id = id,
                    action = op.action,
                    status = OperationStatus.CANCELLED,
                    message = "Operation cancelled.",
                ),
            )
        }
        return op
    }
}
