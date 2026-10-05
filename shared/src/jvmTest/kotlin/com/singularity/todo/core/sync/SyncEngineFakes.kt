package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.work.FakeHlcFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration

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

    override suspend fun migrateAnonymousTo(email: String, password: String): Result<Unit> = notImplemented()

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

/**
 * In-memory [SyncStateRepository], keyed by scope.
 *
 * Keyed by [SyncScope] for the same reason the table is: a fake that stored one
 * cursor per test would let the very mistake the per-scope table exists to prevent
 * pass its own tests. A test that reads `lastLsn` without naming a scope is asking
 * the question the interface refuses to answer.
 */
class FakeSyncStateRepository : SyncStateRepository {

    private val states = MutableStateFlow<Map<SyncScope, SyncState>>(emptyMap())

    override fun observe(scope: SyncScope): Flow<SyncState> =
        states.map { it[scope] ?: SyncState() }

    override suspend fun get(scope: SyncScope): SyncState = states.value[scope] ?: SyncState()

    override suspend fun setLastLsn(scope: SyncScope, lsn: Long) = update(scope) { it.copy(lastLsn = lsn) }

    override suspend fun recordSuccessfulSync(scope: SyncScope, at: Long) =
        update(scope) { it.copy(lastSuccessfulSyncAt = at) }

    override suspend fun setAutoSyncEnabled(scope: SyncScope, enabled: Boolean) =
        update(scope) { it.copy(autoSyncEnabled = enabled) }

    override suspend fun setScheduledInterval(scope: SyncScope, interval: Duration) =
        update(scope) { it.copy(scheduledInterval = interval) }

    override suspend fun setEnabledTriggers(scope: SyncScope, triggers: Set<SyncTrigger>) =
        update(scope) { it.copy(enabledTriggers = triggers) }

    override suspend fun isSeedCompleted(scope: SyncScope): Boolean = get(scope).seedCompleted

    override suspend fun setSeedCompleted(scope: SyncScope, completed: Boolean) =
        update(scope) { it.copy(seedCompleted = completed) }

    override suspend fun clearAll() {
        states.value = emptyMap()
    }

    /** Seeds a row without going through a setter — "the user already had this". */
    fun seed(scope: SyncScope, state: SyncState) {
        states.value = states.value + (scope to state)
    }

    fun lastLsn(scope: SyncScope): Long = states.value[scope]?.lastLsn ?: 0L

    fun snapshot(): Map<SyncScope, SyncState> = states.value

    private fun update(scope: SyncScope, transform: (SyncState) -> SyncState) {
        states.value = states.value + (scope to transform(states.value[scope] ?: SyncState()))
    }
}

/**
 * [SyncScopeProvider] a test can point at a scope — and, importantly, a test can
 * point at `null`, which is the state that has no coverage without it.
 */
class FakeSyncScopeProvider(scope: SyncScope? = null) : SyncScopeProvider {
    private val scopes = MutableStateFlow(scope)
    override val current: Flow<SyncScope?> = scopes

    fun set(scope: SyncScope?) {
        scopes.value = scope
    }
}

/**
 * In-memory [SyncShadowDao] for the sync-engine tests.
 *
 * A second copy of the one inside `FakeAppDatabase`, which is private there because
 * the database owns it. This one exists because these tests build an engine without
 * a database, and the shadow's `in_flight_patch_id` guard is behaviour worth having
 * a handle on rather than only asserting through.
 */
class FakeSyncShadowDao : SyncShadowDao {

    private data class Key(val ownerId: String, val profileId: String, val entityType: String, val entityId: String)

    private val rows = mutableMapOf<Key, SyncShadowEntity>()

    fun state(ownerId: String, profileId: String, entityType: String, entityId: String): SyncShadowEntity? =
        rows[Key(ownerId, profileId, entityType, entityId)]

    override suspend fun get(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
    ): SyncShadowEntity? = state(ownerId, profileId, entityType, entityId)

    override suspend fun upsert(entity: SyncShadowEntity) {
        rows[Key(entity.ownerId, entity.profileId, entity.entityType, entity.entityId)] = entity
    }

    override suspend fun confirm(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
        patchId: String,
        json: String,
    ): Int = mutateIfOwned(ownerId, profileId, entityType, entityId, patchId) {
        it.copy(confirmedJson = json, inFlightJson = null, inFlightPatchId = null)
    }

    override suspend fun release(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
        patchId: String,
    ): Int = mutateIfOwned(ownerId, profileId, entityType, entityId, patchId) {
        it.copy(inFlightJson = null, inFlightPatchId = null)
    }

    override suspend fun clearScope(ownerId: String, profileId: String) {
        rows.keys.filter { it.ownerId == ownerId && it.profileId == profileId }.forEach(rows::remove)
    }

    override suspend fun clearAll() {
        rows.clear()
    }

    private fun mutateIfOwned(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
        patchId: String,
        transform: (SyncShadowEntity) -> SyncShadowEntity,
    ): Int {
        val key = Key(ownerId, profileId, entityType, entityId)
        val row = rows[key] ?: return 0
        if (row.inFlightPatchId != patchId) return 0
        rows[key] = transform(row)
        return 1
    }
}

/**
 * A real [SyncPatchBuilder] over [FakeSyncShadowDao] and [FakeHlcFactory].
 *
 * Real rather than a stub, because the diff is the thing under test in more than
 * one place, and a stubbed builder would let every engine test stay green with a
 * broken diff — the same rule as the deleted `SyncRepositoryCoalescingTest`, in its
 * second form.
 */
internal fun fakeSyncPatchBuilder(shadowDao: SyncShadowDao = FakeSyncShadowDao()): SyncPatchBuilder = SyncPatchBuilder(
    shadowDao = shadowDao,
    hlcFactory = FakeHlcFactory(),
    idGenerator = SequentialIdGenerator(),
)
