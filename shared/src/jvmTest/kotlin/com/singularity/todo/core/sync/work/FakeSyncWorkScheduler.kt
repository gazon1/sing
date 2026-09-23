package com.singularity.todo.core.sync.work

import com.singularity.todo.core.sync.work.SyncWorkScheduler

/**
 * In-memory fake for [SyncWorkScheduler] — records calls for assertions in tests.
 * Does not actually schedule any background work.
 */
class FakeSyncWorkScheduler : SyncWorkScheduler {

    private val _enqueueCalls = mutableListOf<Unit>()
    val enqueueCalls: List<Unit> get() = _enqueueCalls

    private val _cancelCalls = mutableListOf<Unit>()
    val cancelCalls: List<Unit> get() = _cancelCalls

    var enqueuePushCalled: Boolean = false
        private set

    var cancelPushCalled: Boolean = false
        private set

    override fun enqueuePush() {
        enqueuePushCalled = true
        _enqueueCalls.add(Unit)
    }

    override fun cancelPush() {
        cancelPushCalled = true
        _cancelCalls.add(Unit)
    }

    fun reset() {
        enqueuePushCalled = false
        cancelPushCalled = false
        _enqueueCalls.clear()
        _cancelCalls.clear()
    }
}
