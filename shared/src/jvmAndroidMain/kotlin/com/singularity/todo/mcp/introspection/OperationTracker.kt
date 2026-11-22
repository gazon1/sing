package com.singularity.todo.mcp.introspection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
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

    /**
     * Waits for a terminal operation record, or returns a timeout observation for this wait.
     *
     * A caller wait deadline expiring is not the operation finishing: the operation may still
     * complete later, so the timeout is reported to this waiter without marking the operation done.
     */
    suspend fun await(timeoutMs: Long): OperationRecord {
        if (done.isCompleted) return current
        if (timeoutMs <= 0) {
            return OperationRecord(
                id = id,
                action = action,
                status = OperationStatus.TIMEOUT,
                message = "Timed out after ${timeoutMs}ms waiting for operation completion.",
            )
        }
        val result = withTimeoutOrNull(timeoutMs) { done.await() }
        if (result != null) return result
        return OperationRecord(
            id = id,
            action = action,
            status = OperationStatus.TIMEOUT,
            message = "Timed out after ${timeoutMs}ms waiting for operation completion.",
        )
    }
}

internal class OperationTracker(private val maxCompleted: Int = 128) {
    private val ops = ConcurrentHashMap<String, OperationState>()
    private val lock = Any()
    private val completedOrder = ArrayDeque<String>()

    fun create(action: String): OperationState {
        val state = OperationState(UUID.randomUUID().toString(), action)
        ops[state.id] = state
        return state
    }

    fun get(id: String): OperationState? = ops[id]

    fun cancel(id: String): OperationState? = ops[id]

    fun hasInFlight(): Boolean = ops.values.any { !it.isDone() }

    fun complete(
        state: OperationState,
        record: OperationRecord,
    ) {
        state.complete(record)
        pruneTerminal(state.id)
    }

    private fun pruneTerminal(id: String) {
        synchronized(lock) {
            val state = ops[id] ?: return
            if (!state.isDone()) return
            if (!completedOrder.contains(id)) {
                completedOrder.addLast(id)
            }
            while (completedOrder.size > maxCompleted) {
                val old = completedOrder.removeFirst() ?: break
                val oldState = ops[old]
                if (oldState != null && oldState.isDone()) {
                    ops.remove(old)
                }
            }
        }
    }
}
