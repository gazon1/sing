package com.singularity.todo.core.ui.mvi

/**
 * Strategy for state updates in [com.singularity.todo.core.ui.StatefulViewModel].
 *
 * Determines how concurrent read-modify-write operations are serialized.
 * [Atomic] is added in MR-3 for VMs with genuine TOCTOU race conditions
 * (e.g. editor VMs with background sync + user edits).
 */
sealed interface StateStrategy {
    /**
     * Direct [kotlinx.coroutines.flow.MutableStateFlow.update].
     *
     * Fast path — no locking overhead. Suitable for VMs where state updates
     * are serialized by coroutine dispatchers (no concurrent access from multiple coroutines).
     */
    data object Direct : StateStrategy
}
