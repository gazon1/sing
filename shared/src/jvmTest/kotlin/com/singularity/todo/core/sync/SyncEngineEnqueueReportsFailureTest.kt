@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import com.singularity.todo.core.sync.work.FakeHlcFactory
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A change that never reached the outbox is reported (REQ-OS-028).
 *
 * ## What this is a control for
 *
 * The write pipeline is `assertCanWrite` → `dao.upsert` → `syncRepository.enqueue`,
 * and the local row is committed inside `unitOfWork.write` — **before** `enqueue`
 * runs. So a failed enqueue is not a failed write. It is a write that succeeded
 * locally and will never reach the server: the patch row was never inserted, so
 * no later cycle has anything to push and nothing to notice.
 *
 * `enqueue` returned `Result.failure` to 18 call sites that all discarded it, so
 * the failure reached no log, no crash report and no UI. The edit is in the
 * database and the sync screen says "up to date".
 *
 * This has cost an outage already. `2026-09-27-write-layer-soundness.md` MR-4
 * found `Note` lacked `@Serializable`, so `toJson()` threw for **every note
 * enqueue** — notes stopped syncing for every user, with no error anywhere — and
 * MR-5 found the same in `Project` and `Tag`. The silence did not merely hide the
 * bug; it hid the discovery of it.
 *
 * ## Why the assertion is on the port, not on the return value
 *
 * "It returns failure" was already true and proved nothing — that is why the bug
 * survived a sweep that read every call site. What was missing is the second
 * half: somebody finds out. So the assertion is that the reporting port received
 * the original throwable, by identity, under a stable issue key.
 *
 * The positive case is here too. A rule that reported unconditionally would pass
 * the first assertion and would be useless in production, so the second test
 * pins that a successful enqueue reports nothing.
 */
@Tag("fast")
class SyncEngineEnqueueReportsFailureTest {

    private val clock = MutableClock()
    private val log = Logger.withTag("SyncEngineEnqueueReportsFailureTest")

    private class TestEntity(override val syncId: String) : SyncableEntity {
        override val docType: DocType = DocType.Task
        override val syncServerVersion: Long = 0
        override val syncHlc: Hlc? = null
        override fun toJson() = buildJsonObject { put("title", JsonPrimitive("edited $syncId")) }
    }

    /** The outbox with [insert] refusing, which is what a full disk is. */
    private class UnwritableOutbox(private val delegate: FakeSyncOutboxDao, private val failure: Throwable) :
        SyncOutboxDao by delegate {
        override suspend fun insert(entity: SyncOutboxEntity) = throw failure
    }

    private fun engine(
        scope: TestScope,
        outbox: SyncOutboxDao,
        crashReporter: CrashReportingPort,
    ): SyncEngine = SyncEngine(
        log = log,
        api = FakeSyncApiClient(),
        authRepository = FakeSyncAuthRepository(
            Session.SignedIn(UserId.generate(), "t@x.com", "access", "refresh"),
        ),
        outboxDao = outbox,
        deadLetterDao = FakeSyncDeadLetterDao(),
        idGenerator = SequentialIdGenerator(),
        stateRepository = FakeSyncStateRepository(),
        shadowDao = FakeSyncShadowDao(),
        patchBuilder = fakeSyncPatchBuilder(FakeSyncShadowDao()),
        writerProvider = { fakeSyncDocumentWriter() },
        scopeProvider = FakeSyncScopeProvider(SyncScope("owner-enqueue", "profile-1")),
        scheduler = FakeSyncWorkScheduler(),
        clock = clock,
        scope = testScope(scope.backgroundScope),
        crashReporter = crashReporter,
            hlcFactory = FakeHlcFactory(),
    )

    @Test
    fun `a change that never reached the outbox is reported`() = runTest {
        val diskFull = IllegalStateException("database or disk full")
        val reporter = RecordingCrashReportingPort()
        val outbox = FakeSyncOutboxDao()
        val engine = engine(this, UnwritableOutbox(outbox, diskFull), reporter)

        val result = engine.enqueue(TestEntity("task-1"))

        assertTrue(result.isFailure, "the caller still gets a failure to react to")
        assertEquals(
            listOf("sync.enqueue_failed"),
            reporter.reports.map { it.second },
            "the issue key is the stable handle an operator greps for; an ad-hoc string " +
                "would make the failure unfindable in a report store",
        )
        assertSame(
            diskFull,
            reporter.reports.single().first,
            "the original throwable, not the AppError wrapper. runCatchingResult builds " +
                "every AppError in a catch block, so handing the wrapper over would hand " +
                "over a stack that ends where it was constructed — which is why " +
                "SyncPhaseReporter reports error.original() and so does this.",
        )
        assertTrue(
            outbox.rows.isEmpty(),
            "and nothing was queued, which is the whole reason this needs reporting",
        )
    }

    @Test
    fun `a change that did reach the outbox reports nothing`() = runTest {
        // The negative case. Without it, an enqueue that reported on every call
        // would pass the test above and be pure noise in production.
        val reporter = RecordingCrashReportingPort()
        val engine = engine(this, FakeSyncOutboxDao(), reporter)

        val result = engine.enqueue(TestEntity("task-1"))

        assertTrue(result.isSuccess)
        assertEquals(
            emptyList(),
            reporter.reports,
            "the happy path is the overwhelming majority of calls; reporting it would bury " +
                "the failures this requirement exists to surface",
        )
    }
}
