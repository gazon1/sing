@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.sync.work.FakeHlcFactory
import com.singularity.todo.test.helpers.MutableClock
import com.singularity.todo.core.sync.ThrowingSyncScopeProvider

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Dedicated tests for [OutboxService].
 *
 * Covers the behaviour extracted from `SyncEngine.enqueue`:
 * - Coalescing: two enqueues for the same entity produce one row (delete before insert)
 * - Null scope → silent no-op (Result.success without any write)
 * - Scope read failure → Result.failure(AppError.Persistence)
 * - Delete failure → Result.failure(AppError.Persistence)
 * - Insert failure → Result.failure(AppError.Persistence)
 *
 * The coalescing behaviour is the most important: 18 call sites depend on it without
 * knowing it. If the delete-before-insert pattern breaks, the outbox grows without
 * bound and the shadow's lost-race guard never fires.
 */
@Tag("fast")
class OutboxServiceTest {

    private val clock = MutableClock()
    private val log = Logger.withTag("OutboxServiceTest")

    /** Minimal [SyncableEntity] whose JSON the test controls field by field. */
    private class TestEntity(override val syncId: String) : SyncableEntity {
        override val docType: DocType = DocType.Task
        override val syncServerVersion: Long = 0
        override val syncHlc: Hlc? = null

        override fun toJson() = buildJsonObject { put("title", JsonPrimitive("title $syncId")) }
    }

    private fun TestScope.service(
        outbox: FakeSyncOutboxDao = FakeSyncOutboxDao(),
        shadow: FakeSyncShadowDao = FakeSyncShadowDao(),
        scopeProvider: SyncScopeProvider = FakeSyncScopeProvider(SyncScope("owner-1", "profile-1")),
    ): OutboxService = OutboxService(
        outboxDao = outbox,
        patchBuilder = fakeSyncPatchBuilder(shadow),
        scopeProvider = scopeProvider,
        engineState = SyncEngineState(
            log = log,
            crashReporter = NoOpCrashReportingPort(),
        ),
        monoNow = { clock.now().toEpochMilliseconds() },
    )

    // ─── success paths ─────────────────────────────────────────────────────────

    @Test
    fun `enqueue inserts one row for a new entity`() = runTest {
        val outbox = FakeSyncOutboxDao()
        val svc = service(outbox = outbox)

        val result = svc.enqueue(TestEntity("t-new"))
        advanceUntilIdle()

        assertTrue(result.isSuccess, "enqueue must succeed: $result")
        assertEquals(1, outbox.rows.size, "exactly one row must be inserted")
        assertEquals("t-new", outbox.rows.single().entityId)
    }

    @Test
    fun `two enqueues for the same entity coalesce into one row`() = runTest {
        val outbox = FakeSyncOutboxDao()
        val svc = service(outbox = outbox)

        svc.enqueue(TestEntity("t-coalesce"))
        advanceUntilIdle()
        val firstRowEntityId = outbox.rows.single().entityId

        svc.enqueue(TestEntity("t-coalesce"))
        advanceUntilIdle()

        // Coalescing: delete-before-insert means the second enqueue replaces the first.
        // Exactly one row must remain, with the same entityId.
        assertEquals(1, outbox.rows.size, "coalescing must leave exactly one row")
        assertEquals(firstRowEntityId, outbox.rows.single().entityId)
    }

    @Test
    fun `enqueue for different entities produces different rows`() = runTest {
        val outbox = FakeSyncOutboxDao()
        val svc = service(outbox = outbox)

        svc.enqueue(TestEntity("t-a"))
        svc.enqueue(TestEntity("t-b"))
        advanceUntilIdle()

        assertEquals(2, outbox.rows.size, "different entities must each get a row")
    }

    // ─── null scope → no-op ──────────────────────────────────────────────────

    @Test
    fun `null scope is a silent no-op`() = runTest {
        val outbox = FakeSyncOutboxDao()
        val svc = service(scopeProvider = FakeSyncScopeProvider(null))

        val result = svc.enqueue(TestEntity("t-null-scope"))
        advanceUntilIdle()

        assertTrue(result.isSuccess, "null scope must return success: $result")
        assertTrue(outbox.rows.isEmpty(), "no row may be inserted when scope is null")
    }

    // ─── error paths ──────────────────────────────────────────────────────────

    @Test
    fun `scope read failure propagates as Result-failure`() = runTest {
        val outbox = FakeSyncOutboxDao()
        val throwingProvider = ThrowingSyncScopeProvider()
        val svc = service(scopeProvider = throwingProvider)

        val result = svc.enqueue(TestEntity("t-scope-fail"))
        advanceUntilIdle()

        assertTrue(result.isFailure, "scope read failure must propagate: $result")
        // The error is wrapped as AppError.Persistence by OutboxService
        assertIs<com.singularity.todo.core.error.AppError.Persistence>(
            result.exceptionOrNull(),
        )
    }

    @Test
    fun `delete failure propagates as Result-failure`() = runTest {
        val outbox =
            object : FakeSyncOutboxDao() {
                override suspend fun deleteByEntity(ownerId: String, entityId: String) {
                    throw IllegalStateException("delete failed")
                }
            }
        val svc = service(outbox = outbox)

        val result = svc.enqueue(TestEntity("t-delete-fail"))
        advanceUntilIdle()

        assertTrue(result.isFailure, "delete failure must propagate: $result")
        assertSame(
            IllegalStateException::class,
            result.exceptionOrNull()?.cause?.let { it::class },
            "the original delete error must be preserved as cause",
        )
    }

    @Test
    fun `insert failure propagates as Result-failure`() = runTest {
        val outbox =
            object : FakeSyncOutboxDao() {
                override suspend fun insert(entity: SyncOutboxEntity) {
                    throw IllegalStateException("insert failed")
                }
            }
        val svc = service(outbox = outbox)

        val result = svc.enqueue(TestEntity("t-insert-fail"))
        advanceUntilIdle()

        assertTrue(result.isFailure, "insert failure must propagate: $result")
        assertSame(
            IllegalStateException::class,
            result.exceptionOrNull()?.cause?.let { it::class },
            "the original insert error must be preserved as cause",
        )
    }
}
