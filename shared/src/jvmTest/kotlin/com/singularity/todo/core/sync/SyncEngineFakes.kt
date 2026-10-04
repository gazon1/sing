package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory [SyncOutboxDao].
 *
 * A fake rather than a Room database because these tests are about the engine's
 * decisions, not about SQL, and a real database would put a process boundary — and
 * a `slow` tag — in the middle of every assertion.
 */
class FakeSyncOutboxDao : SyncOutboxDao {
    val rows = mutableListOf<SyncOutboxEntity>()

    override fun watchPending(): Flow<List<SyncOutboxEntity>> = MutableStateFlow(snapshot())

    /** Honours [SyncOutboxDao.getPending]'s backoff filter, or a test asserts on a
     *  fake that has no backoff to begin with. */
    override suspend fun getPending(now: Long): List<SyncOutboxEntity> =
        snapshot().filter { it.nextAttemptAt == null || it.nextAttemptAt <= now }

    override suspend fun insert(entity: SyncOutboxEntity) {
        rows.removeAll { it.patchId == entity.patchId }
        rows += entity
    }

    override suspend fun delete(id: String) {
        rows.removeAll { it.patchId == id }
    }

    override suspend fun markFailed(id: String, error: String, nextAttemptAt: Long) {
        val index = rows.indexOfFirst { it.patchId == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(
                attempts = rows[index].attempts + 1,
                lastError = error,
                nextAttemptAt = nextAttemptAt,
            )
        }
    }

    override suspend fun attemptsOf(id: String): Int? = rows.firstOrNull { it.patchId == id }?.attempts

    override suspend fun deleteByEntity(entityId: String) {
        rows.removeAll { it.entityId == entityId }
    }

    override suspend fun clearAll() {
        rows.clear()
    }

    private fun snapshot(): List<SyncOutboxEntity> = rows.sortedBy { it.createdAt }
}

/**
 * [AuthRepository] that reports a session the test can change.
 */
class FakeSyncAuthRepository(session: Session) : AuthRepository {
    // Not `_session`: there is no `session` property, and a backing field only earns
    // the underscore when it backs a matching name.
    private val sessions = MutableStateFlow(session)

    override val currentSession: StateFlow<Session> = sessions

    /** Never loading: the sync engine never sets it, and a test that needs it can
     *  assert on the session instead. */
    override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun signUp(email: String, password: String): Result<Unit> = notImplemented()

    override suspend fun signIn(email: String, password: String): Result<Unit> = notImplemented()

    override suspend fun signInAnonymously(): Result<Unit> = notImplemented()

    override suspend fun signOut(): Result<Unit> = notImplemented()

    override suspend fun migrateAnonymousTo(newUserId: UserId): Result<Unit> = notImplemented()

    private fun notImplemented(): Result<Unit> =
        Result.failure(UnsupportedOperationException("not used by sync engine tests"))

    fun signIn(userId: UserId = UserId.generate()) {
        sessions.value = Session.SignedIn(userId, "test@x.com", "access", "refresh")
    }
}

/**
 * Deterministic [IdGenerator] for assertions.
 */
class SequentialIdGenerator(private val prefix: String = "id") : IdGenerator {
    private var counter = 0
    override fun next(): String = "$prefix-${counter++}"
}

/**
 * In-memory [SyncDeadLetterDao].
 */
class FakeSyncDeadLetterDao : SyncDeadLetterDao {
    val rows = mutableListOf<SyncDeadLetterEntity>()

    override fun watch(): Flow<List<SyncDeadLetterEntity>> = MutableStateFlow(snapshot())

    override fun watchCount(): Flow<Int> = MutableStateFlow(rows.size)

    override suspend fun getAll(): List<SyncDeadLetterEntity> = snapshot()

    override suspend fun insert(entity: SyncDeadLetterEntity) {
        rows.removeAll { it.patchId == entity.patchId }
        rows += entity
    }

    override suspend fun delete(id: String): Int = if (rows.removeAll { it.patchId == id }) 1 else 0

    override suspend fun clearAll() {
        rows.clear()
    }

    private fun snapshot(): List<SyncDeadLetterEntity> = rows.sortedByDescending { it.failedAt }
}
