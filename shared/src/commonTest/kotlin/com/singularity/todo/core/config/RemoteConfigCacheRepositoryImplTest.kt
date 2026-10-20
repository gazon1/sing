package com.singularity.todo.core.config

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.version.AppVersion
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeRemoteConfigApiClient
import com.singularity.todo.test.fakes.FakeRemoteConfigApiClient.FetchBehaviour
import com.singularity.todo.test.fakes.FakeRemoteConfigCacheDao
import com.singularity.todo.test.fakes.FakeRemoteConfigCacheDao.ReadBehaviour
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The deadline the tests inject.
 *
 * Small enough to be obvious. Virtual time makes it cost nothing: the deadline the port
 * enforces is measured on the caller's clock, so the test does not wait for it.
 */
private const val TEST_READ_TIMEOUT_MS = 50L

/**
 * The port's own bound on a remote-config read.
 *
 * The version gate has a deadline of its own, and it is the guarantee the UI sees. But a
 * deadline in a caller can only end a read that *honours cancellation*: a read that
 * blocks in a driver call — a Room database created on first launch, a locked
 * multi-instance database — keeps holding the caller past the deadline. The port is
 * where the read happens, so the port is where the bound belongs, and these tests pin
 * it there.
 *
 * The fake's blocking read ignores cancellation on purpose. A fake that merely suspended
 * for a long time would pass against an implementation that only looks bounded, because
 * a cancellable read is ended by any deadline at all.
 */
@Tag("fast")
class RemoteConfigCacheRepositoryImplTest {

    /**
     * The port under test.
     *
     * The read scope is injected rather than defaulted because the read runs *outside* the
     * caller's coroutine: a scope on the test's own scheduler keeps the whole exchange on
     * virtual time, so the test decides when the read answers. A scope of its own job, so
     * the read the port abandons at its deadline is not a leftover coroutine in the test's.
     */
    private fun TestScope.repo(
        dao: FakeRemoteConfigCacheDao = FakeRemoteConfigCacheDao(),
        api: FakeRemoteConfigApiClient = FakeRemoteConfigApiClient(),
        readTimeoutMs: Long = TEST_READ_TIMEOUT_MS,
    ) = RemoteConfigCacheRepositoryImpl(
        cacheDao = dao,
        syncApi = api,
        clock = FakeClock(),
        readTimeoutMs = readTimeoutMs,
        readScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
    )

    /**
     * A cached row with a minimum version, so a snapshot that carries one is visibly
     * different from the defaults.
     *
     * `maintenanceBanner` is in the JSON because the serializer has no default for it:
     * a nullable field is still a required field, and a missing one fails the whole
     * decode — which would silently hand back the defaults and make this row
     * indistinguishable from no row at all.
     */
    private fun cachedRow() = RemoteConfigCacheEntity(
        snapshotJson = """{"schemaVersion":1,"fetchedAtEpochMillis":0,""" +
            """"maintenanceBanner":null,"minSupportedVersion":{"name":"2.0.0","code":20}}""",
        fetchedAtEpochMillis = 0L,
    )

    @Test
    fun `a read that blocks ends at the port's deadline instead of hanging`() = runTest {
        // The read blocks and ignores cancellation — the shape a deadline in the
        // caller's own coroutine cannot end.
        val dao = FakeRemoteConfigCacheDao(
            cached = cachedRow(),
            readBehaviour = ReadBehaviour.Blocks,
        )
        val repo = repo(dao = dao)

        val snapshot = repo.snapshot()

        assertEquals(
            RemoteConfigSnapshot.defaults(),
            snapshot,
            "a read that missed the deadline must serve the last known good snapshot — " +
                "the defaults until the first successful read — and not wait for the row",
        )
    }

    @Test
    fun `the port gives up at its own deadline, not later and not never`() = runTest {
        // Without this, a test that waited for the production deadline would pass just as
        // happily: virtual time makes five seconds as instant as fifty milliseconds, so the
        // only way to know which deadline was honoured is to look at the clock.
        val dao = FakeRemoteConfigCacheDao(
            cached = cachedRow(),
            readBehaviour = ReadBehaviour.Blocks,
        )
        val repo = repo(dao = dao)

        repo.snapshot()

        assertEquals(
            TEST_READ_TIMEOUT_MS,
            testScheduler.currentTime,
            "the port must give up at its deadline: virtual time was ${testScheduler.currentTime} ms",
        )
    }

    @Test
    fun `the port bounds its read by the port's own deadline`() = runTest {
        // The bound and the deadline a caller imposes are one number, written down once.
        // This is the port's half of that: the default deadline it enforces is the port's
        // own constant, not a second literal that could drift away from it.
        val dao = FakeRemoteConfigCacheDao(
            cached = cachedRow(),
            readBehaviour = ReadBehaviour.Blocks,
        )
        val repo = RemoteConfigCacheRepositoryImpl(
            cacheDao = dao,
            syncApi = FakeRemoteConfigApiClient(),
            clock = FakeClock(),
            readScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        )

        repo.snapshot()

        assertEquals(
            RemoteConfigPort.READ_TIMEOUT_MS,
            testScheduler.currentTime,
            "the port's default deadline must be the port's own constant",
        )
    }

    @Test
    fun `a refresh that blocks fails with the port's timeout code`() = runTest {
        // `refresh` returns a Result, so a read that misses the deadline is a failure —
        // the shape every caller of this method already handles.
        val api = FakeRemoteConfigApiClient(fetchBehaviour = FetchBehaviour.Blocks)
        val repo = repo(api = api)

        val result = repo.refresh()

        assertTrue(result.isFailure, "a refresh that never came back must fail: $result")
        val error = result.exceptionOrNull()
        assertIs<AppError.Network>(error)
        assertEquals(
            RemoteConfigPort.READ_TIMEOUT_CODE,
            error.code,
            "the timeout needs its own code: it is a slow disk, not a broken config server",
        )
    }

    @Test
    fun `a read that comes back in time is served`() = runTest {
        // The bound must not cost a healthy read anything.
        val dao = FakeRemoteConfigCacheDao(cached = cachedRow())
        val repo = repo(dao = dao)

        val snapshot = repo.snapshot()

        assertEquals(AppVersion("2.0.0", code = 20), snapshot.minSupportedVersion)
        assertEquals(0L, testScheduler.currentTime, "a read that answers immediately waits for nothing")
    }

    @Test
    fun `a read that fails still reaches the caller`() = runTest {
        // The bound is not a licence to swallow failures: a read that throws is still a
        // failed read, and the caller is still the one that decides what to do about it.
        val dao = FakeRemoteConfigCacheDao(readBehaviour = ReadBehaviour.Throws)
        val repo = repo(dao = dao)

        assertFailsWith<IllegalStateException> { repo.snapshot() }
    }
}
