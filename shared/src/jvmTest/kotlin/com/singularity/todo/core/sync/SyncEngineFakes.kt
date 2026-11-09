package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.work.FakeHlcFactory
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagGroupRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
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
 *
 * Open so tests can create throwing subclasses for error-path coverage.
 */
open class FakeSyncOutboxDao : SyncOutboxDao {
    val rows = mutableListOf<SyncOutboxEntity>()

    override fun watchPending(): Flow<List<SyncOutboxEntity>> = MutableStateFlow(snapshot())

    /**
     * Honours both of [SyncOutboxDao.getPending]'s filters, or a test asserts on a fake
     * that has neither.
     *
     * The owner filter is the one this fake exists to be honest about. An earlier
     * version implemented only the backoff filter, and so would have kept passing a
     * test that put two accounts' work in one table — which is the whole thing
     * REQ-UA-019 says must not happen.
     */
    override suspend fun getPending(
        now: Long,
        ownerId: String,
        profileId: String,
    ): List<SyncOutboxEntity> =
        snapshot()
            .filter { it.ownerId == ownerId && it.profileId == profileId }
            .filter { it.claimToken == null }
            .filter {
                val nextAttemptAt = it.nextAttemptAt
                nextAttemptAt == null || nextAttemptAt <= now
            }

    override suspend fun claim(
        ids: List<String>,
        ownerId: String,
        token: String,
    ) {
        ids.forEach { id ->
            val index = rows.indexOfFirst { it.patchId == id && it.ownerId == ownerId && it.claimToken == null }
            if (index >= 0) rows[index] = rows[index].copy(claimToken = token)
        }
    }

    override suspend fun release(
        ids: List<String>,
        ownerId: String,
        token: String,
    ) {
        ids.forEach { id ->
            val index = rows.indexOfFirst { it.patchId == id && it.ownerId == ownerId }
            if (index >= 0 && rows[index].claimToken == token) {
                rows[index] = rows[index].copy(claimToken = null)
            }
        }
    }

    override suspend fun countPendingFor(ownerId: String): Int = rows.count { it.ownerId == ownerId }

    override suspend fun insert(entity: SyncOutboxEntity) {
        rows.removeAll { it.patchId == entity.patchId }
        rows += entity
    }

    override suspend fun delete(id: String) {
        rows.removeAll { it.patchId == id }
    }

    override suspend fun markFailed(
        id: String,
        error: String,
        nextAttemptAt: Long,
        claimToken: String?,
    ) {
        val index = rows.indexOfFirst { it.patchId == id }
        if (index >= 0 && (rows[index].claimToken == null || rows[index].claimToken == claimToken)) {
            rows[index] =
                rows[index].copy(
                    attempts = rows[index].attempts + 1,
                    lastError = error,
                    nextAttemptAt = nextAttemptAt,
                    claimToken = null,
                )
        }
    }

    override suspend fun attemptsOf(id: String): Int? = rows.firstOrNull { it.patchId == id }?.attempts

    override suspend fun deleteByEntity(
        ownerId: String,
        profileId: String,
        entityId: String,
    ) {
        rows.removeAll { it.ownerId == ownerId && it.profileId == profileId && it.entityId == entityId }
    }

    override suspend fun deleteForOwner(ownerId: String): Int {
        val doomed = rows.filter { it.ownerId == ownerId }
        rows.removeAll { it.ownerId == ownerId }
        return doomed.size
    }

    override suspend fun countForOwner(ownerId: String): Int = rows.count { it.ownerId == ownerId }

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

    override suspend fun signUp(
        email: String,
        password: String,
    ): Result<Unit> = notImplemented()

    override suspend fun signIn(
        email: String,
        password: String,
    ): Result<Unit> = notImplemented()

    override suspend fun startLocalSession(): Result<Unit> = notImplemented()

    override suspend fun signOut(): Result<Unit> = notImplemented()

    override suspend fun updatePassword(newPassword: String): Result<Unit> = notImplemented()

    override suspend fun sendMagicLink(email: String): Result<Unit> = notImplemented()

    override suspend fun verifyMagicLink(
        email: String,
        token: String,
    ): Result<Unit> = notImplemented()

    private fun notImplemented(): Result<Unit> =
        Result.failure(UnsupportedOperationException("not used by sync engine tests"))

    fun signIn(userId: UserId = UserId.generate()) {
        sessions.value = Session.SignedIn(userId, "test@x.com", "access", "refresh")
    }

    /**
     * Any session at all, including signing out and the account-less one.
     *
     * Not three named transitions, because a name per case is a name to keep in step
     * with the sealed hierarchy — and `signOut` is already taken by [AuthRepository],
     * where it means "ask the provider too", which is the opposite of what these tests
     * want. One setter, and the test says which session it means.
     *
     * The assignment to `sessions.value` also notifies [currentSession]'s collectors —
     * which is what makes `SyncEngine.syncOnce`'s `authRepository.currentSession.value`
     * read reflect the new session immediately.
     */
    fun set(session: Session) {
        sessions.value = session
    }

    /**
     * The same account with a new access and refresh token.
     *
     * A token refresh replaces [Session] wholesale while leaving the account alone,
     * and a push response that arrived across one must still be applied. A test that
     * could not express this would be satisfied by a re-check comparing whole
     * sessions — which is the wrong rule, and one that discards nearly every push.
     */
    fun refreshToken(userId: UserId = UserId.generate()) {
        sessions.value = Session.SignedIn(userId, "test@x.com", "access-2", "refresh-2")
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
 *
 * ## The guard set it mirrors
 *
 * The same one [SyncDeadLetterDao] wears, and the same one the outbox wears. The
 * guards are reproduced here rather than approximated, because a fake that drops a
 * condition is a fake that lets a test assert the condition holds:
 *
 * - **global reads** — [watch], [getAll], [watchCount]. The shelf holds several
 *   accounts' work at once (REQ-UA-006 keeps work across a sign-out), so a read
 *   that is scoped to one account is a different DAO, not a stricter one.
 * - **owner-scoped erase and count** — [deleteForOwner], [countForOwner]. These two
 *   are the ones that must never leak: without the owner filter an account's erase
 *   would take another account's shelved work with it, and the count that precedes
 *   it would report rows the erase is about to destroy.
 * - **patch-id-keyed** — [delete], which names one row by its patch id.
 * - **global drain** — [clearAll], for a reset with no owner to name. No production
 *   path calls it: a sign-out keeps local data (REQ-UA-006, `SignOutKeepsLocalDataTest`),
 *   so it reaches no sync table, and the account switch erases one owner through
 *   [deleteForOwner] (`OwnerScopedEraserTest`). It is the counterpart of the outbox's
 *   own `clearAll`, not a substitute for the scoped erase.
 *
 * [SyncDaoGuardContractTest] runs the same scenarios against this fake, against the
 * fake inside `FakeAppDatabase` and against Room, so a fake that loses a guard fails
 * the contract rather than quietly passing a test written against it.
 */
class FakeSyncDeadLetterDao : SyncDeadLetterDao {
    private val store = MutableStateFlow<List<SyncDeadLetterEntity>>(emptyList())

    /**
     * The shelved rows, in the order they were shelved.
     *
     * A view of the shelf rather than the shelf itself: the shelf is a [MutableStateFlow]
     * so that [watch] and [watchCount] can report a change, and a list the tests mutate
     * behind the flow's back would leave the flow describing a shelf that no longer
     * exists.
     */
    val rows: List<SyncDeadLetterEntity> get() = store.value

    /**
     * The shelf as Room reports it: a cold flow over the current contents, so a flow
     * taken before a row was shelved reports that row once it is.
     *
     * The earlier version captured a snapshot when `watch()` was called and handed it
     * back frozen. A test that took the flow first and shelved a patch afterwards saw
     * an empty shelf forever — a fake that disagreed with the DAO it stands in for,
     * and one that could not show the very thing the shelf exists to show.
     *
     * Global, as Room's is: every account's shelved work, newest first.
     */
    override fun watch(): Flow<List<SyncDeadLetterEntity>> = store.map { snapshot(it) }

    /** Global, as [watch] is. */
    override fun watchCount(): Flow<Int> = store.map { it.size }

    /** Global, as [watch] is. */
    override suspend fun getAll(): List<SyncDeadLetterEntity> = snapshot(store.value)

    /** The row keeps the owner it was shelved under — the erase is scoped by it. */
    override suspend fun insert(entity: SyncDeadLetterEntity) {
        store.value = store.value.filterNot { it.patchId == entity.patchId } + entity
    }

    /**
     * `DELETE FROM sync_dead_letter WHERE patch_id = :id` — patch-id-keyed, and
     * therefore not owner-scoped: a patch id names one row, so the key is the guard.
     */
    override suspend fun delete(id: String): Int {
        val present = store.value.any { it.patchId == id }
        store.value = store.value.filterNot { it.patchId == id }
        return if (present) 1 else 0
    }

    /**
     * `DELETE FROM sync_dead_letter WHERE owner_id = :ownerId` — the owner filter is
     * the guard, and it is the one that keeps one account's erase from taking
     * another account's shelved work with it.
     */
    override suspend fun deleteForOwner(ownerId: String): Int {
        val doomed = store.value.filter { it.ownerId == ownerId }
        store.value = store.value.filterNot { it.ownerId == ownerId }
        return doomed.size
    }

    /** `SELECT COUNT(*) ... WHERE owner_id = :ownerId` — the owner's rows only. */
    override suspend fun countForOwner(ownerId: String): Int = store.value.count { it.ownerId == ownerId }

    /**
     * `DELETE FROM sync_dead_letter` — the wholesale drain, for a reset with no owner
     * to name.
     *
     * No production path calls it. A sign-out keeps local data (REQ-UA-006,
     * `SignOutKeepsLocalDataTest`) and so reaches no sync table; the account switch
     * erases one owner through [deleteForOwner], which is what `OwnerScopedEraserTest`
     * pins. A switch that reached for this instead would take the incoming account's
     * shelved work with the outgoing one's.
     */
    override suspend fun clearAll() {
        store.value = emptyList()
    }

    private fun snapshot(shelf: List<SyncDeadLetterEntity>): List<SyncDeadLetterEntity> =
        shelf.sortedByDescending { it.failedAt }
}

/**
 * In-memory [SyncStateRepository], keyed by scope.
 *
 * Keyed by [SyncScope] for the same reason the table is: a fake that stored one
 * cursor per test would let the very mistake the per-scope table exists to prevent
 * pass its own tests. A test that reads `lastLsn` without naming a scope is asking
 * the question the interface refuses to answer.
 */
open class FakeSyncStateRepository : SyncStateRepository {
    private val states = MutableStateFlow<Map<SyncScope, SyncState>>(emptyMap())

    override fun observe(scope: SyncScope): Flow<SyncState> = states.map { it[scope] ?: SyncState() }

    override suspend fun get(scope: SyncScope): SyncState = states.value[scope] ?: SyncState()

    override suspend fun setLastLsn(
        scope: SyncScope,
        lsn: Long,
    ) = update(scope) { it.copy(lastLsn = lsn) }

    override suspend fun recordSuccessfulSync(
        scope: SyncScope,
        at: Long,
    ) = update(scope) { it.copy(lastSuccessfulSyncAt = at) }

    override suspend fun setAutoSyncEnabled(
        scope: SyncScope,
        enabled: Boolean,
    ) = update(scope) { it.copy(autoSyncEnabled = enabled) }

    override suspend fun setScheduledInterval(
        scope: SyncScope,
        interval: Duration,
    ) = update(scope) { it.copy(scheduledInterval = interval) }

    override suspend fun setEnabledTriggers(
        scope: SyncScope,
        triggers: Set<SyncTrigger>,
    ) = update(scope) { it.copy(enabledTriggers = triggers) }

    override suspend fun setAttachmentsSyncEnabled(
        scope: SyncScope,
        enabled: Boolean,
    ) = update(scope) { it.copy(attachmentsSyncEnabled = enabled) }

    override suspend fun isSeedCompleted(scope: SyncScope): Boolean = get(scope).seedCompleted

    override suspend fun setSeedCompleted(
        scope: SyncScope,
        completed: Boolean,
    ) = update(scope) { it.copy(seedCompleted = completed) }

    override suspend fun clearAll() {
        states.value = emptyMap()
    }

    /** Seeds a row without going through a setter — "the user already had this". */
    fun seed(
        scope: SyncScope,
        state: SyncState,
    ) {
        states.value = states.value + (scope to state)
    }

    fun lastLsn(scope: SyncScope): Long = states.value[scope]?.lastLsn ?: 0L

    fun snapshot(): Map<SyncScope, SyncState> = states.value

    private fun update(
        scope: SyncScope,
        transform: (SyncState) -> SyncState,
    ) {
        states.value = states.value + (scope to transform(states.value[scope] ?: SyncState()))
    }
}

/**
 * [SyncScopeProvider] a test can point at a scope — and, importantly, a test can
 * point at `null`, which is the state that has no coverage without it.
 */
open class FakeSyncScopeProvider(scope: SyncScope? = null) : SyncScopeProvider {
    private val scopes = MutableStateFlow(scope)
    override val current: Flow<SyncScope?> = scopes

    fun set(scope: SyncScope?) {
        scopes.value = scope
    }
}

/**
 * A [SyncScopeProvider] that throws when [current] is collected.
 *
 * Used to simulate a scope read failure in tests of error paths.
 */
class ThrowingSyncScopeProvider(private val cause: Throwable = IllegalStateException("scope unavailable")) :
    SyncScopeProvider {
    override val current: Flow<SyncScope?> =
        kotlinx.coroutines.flow.flow {
            throw cause
        }
}

/**
 * In-memory [SyncShadowDao] for the sync-engine tests.
 *
 * A second copy of the one inside `FakeAppDatabase`, which is private there because
 * the database owns it. This one exists because these tests build an engine without
 * a database, and the shadow's `in_flight_patch_id` guard is behaviour worth having
 * a handle on rather than only asserting through.
 *
 * ## The guard set it mirrors
 *
 * Every operation is scoped by `(owner_id, profile_id)`, and two of them carry a
 * second guard on `in_flight_patch_id`:
 *
 * - [get], [updateConfirmed], [confirm], [release] and [upsert] address one row of
 *   one account's one profile — the composite key *is* the scope, so a fake that
 *   stored rows in a list keyed by entity id could not tell two profiles' rows
 *   apart, and would let a test assert an isolation that the fake never had.
 * - [confirm] and [release] additionally require the patch id to still own the
 *   in-flight marker, so a response for a superseded patch promotes nothing.
 * - [deleteForOwner] and [countForOwner] are scoped by the owner alone: a switch
 *   must take every profile the account has, not the one that happens to be active.
 * - [clearScope] is scoped by owner *and* profile; [clearAll] is the wholesale drain,
 *   for a reset with no owner to name. No production path calls [clearAll]: a sign-out
 *   keeps local data (REQ-UA-006), and the account switch erases one owner through
 *   [deleteForOwner] (`OwnerScopedEraserTest`).
 *
 * [SyncDaoGuardContractTest] runs the same scenarios against this fake, against the
 * fake inside `FakeAppDatabase` and against Room, so a fake that loses a guard fails
 * the contract rather than quietly passing a test written against it.
 */
class FakeSyncShadowDao : SyncShadowDao {
    private data class Key(val ownerId: String, val profileId: String, val entityType: String, val entityId: String)

    private val rows = mutableMapOf<Key, SyncShadowEntity>()

    fun state(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
    ): SyncShadowEntity? = rows[Key(ownerId, profileId, entityType, entityId)]

    /** `WHERE owner_id = :ownerId AND profile_id = :profileId AND ...` — the whole key. */
    override suspend fun get(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
    ): SyncShadowEntity? = state(ownerId, profileId, entityType, entityId)

    /** The row keeps the owner and profile it was written under. */
    override suspend fun upsert(entity: SyncShadowEntity) {
        rows[Key(entity.ownerId, entity.profileId, entity.entityType, entity.entityId)] = entity
    }

    /** The whole key, and only the confirmed column — the queued patch's base is untouched. */
    override suspend fun updateConfirmed(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
        json: String,
    ): Int {
        val key = Key(ownerId, profileId, entityType, entityId)
        val row = rows[key] ?: return 0
        rows[key] = row.copy(confirmedJson = json)
        return 1
    }

    /** The whole key, plus the in-flight marker guard. */
    override suspend fun confirm(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
        patchId: String,
        json: String,
        serverVersion: Long?,
    ): Int =
        mutateIfOwned(ownerId, profileId, entityType, entityId, patchId) {
            // The COALESCE of the real query, reproduced: a response carrying no version
            // must leave the stored one alone rather than reset it to 0.
            it.copy(
                confirmedJson = json,
                inFlightJson = null,
                inFlightPatchId = null,
                serverVersion = serverVersion ?: it.serverVersion,
            )
        }

    /** The whole key, plus the in-flight marker guard. */
    override suspend fun release(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
        patchId: String,
    ): Int =
        mutateIfOwned(ownerId, profileId, entityType, entityId, patchId) {
            it.copy(inFlightJson = null, inFlightPatchId = null)
        }

    /**
     * `DELETE FROM sync_shadow WHERE owner_id = :ownerId` — the owner alone, and not
     * the owner *and* profile: a switch must take every profile the account has.
     */
    override suspend fun deleteForOwner(ownerId: String): Int {
        val keys = rows.keys.filter { it.ownerId == ownerId }
        keys.forEach(rows::remove)
        return keys.size
    }

    /** `SELECT COUNT(*) ... WHERE owner_id = :ownerId` — the owner's rows only. */
    override suspend fun countForOwner(ownerId: String): Int = rows.count { it.key.ownerId == ownerId }

    /** `DELETE ... WHERE owner_id = :ownerId AND profile_id = :profileId` — one scope only. */
    override suspend fun clearScope(
        ownerId: String,
        profileId: String,
    ) {
        rows.keys.filter { it.ownerId == ownerId && it.profileId == profileId }.forEach(rows::remove)
    }

    /**
     * `DELETE FROM sync_shadow` — the wholesale drain, for a reset with no owner to
     * name.
     *
     * No production path calls it, for the same reason as the shelf's: a sign-out keeps
     * local data (REQ-UA-006) and the account switch erases through [deleteForOwner].
     */

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
internal fun fakeSyncPatchBuilder(shadowDao: SyncShadowDao = FakeSyncShadowDao()): SyncPatchBuilder =
    SyncPatchBuilder(
        shadowDao = shadowDao,
        hlcFactory = FakeHlcFactory(),
        idGenerator = SequentialIdGenerator(),
    )

/**
 * A [SyncDocumentWriter] over the shared in-memory repositories.
 *
 * Passed to the engine because the engine writes documents for a reason that is not
 * the pull: resolving a lost race returns the row to the state the server holds. A
 * test that only pushes needs the writer wired and never exercises it, so the default
 * is real repositories rather than a stub — a stub here would let the lost-race
 * behaviour pass in a system whose per-type write does not work.
 */
internal fun fakeSyncDocumentWriter(
    // Interface-typed, not the fakes: a caller that wants a repository which throws on
    // write must be able to pass it, and a stub that cannot fail would hide exactly the
    // case the revert-failure path exists to handle.
    tasks: TaskRepository = FakeTaskRepository(),
    notes: NotesRepository = FakeNotesRepository(),
    projects: ProjectsRepository = FakeProjectsRepository(),
    tags: TagsRepository = FakeTagsRepository(),
    tagGroups: TagGroupRepository = FakeTagGroupRepository(),
    timeTracking: TimeTrackingRepository = FakeTimeTrackingRepository(FakeClock()),
    currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser(),
): SyncDocumentWriter =
    SyncDocumentWriter(
        taskRepo = tasks,
        noteRepo = notes,
        projectRepo = projects,
        tagRepo = tags,
        tagGroupRepo = tagGroups,
        timeTrackingRepo = timeTracking,
        currentUser = currentUser,
    )
