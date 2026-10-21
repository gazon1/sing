@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.helpers.awaitState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The restore, and the bound around it.
 *
 * ## What is under test
 *
 * The repository's session restore reads storage, and storage can be unreachable. The
 * bound around that read used to be a `withTimeoutOrNull` in the caller's own
 * coroutine — a *cooperative* deadline, which ends the wait only if the read honours
 * cancellation. A read blocked inside a driver call ignores cancellation completely, so
 * the bound was decoration and the caller waited anyway.
 *
 * These tests pin the bound that replaced it: the restore runs in a scope of its own and
 * the caller waits on a promise of the answer, so a restore that ignores cancellation
 * cannot hold the caller. The observable answer of the repository stays the same — a
 * restore that could not be completed means signed out *and* a published restore failure
 * — and a failure that arrives after the caller gave up is reported rather than dropped.
 *
 * ## Why the fakes block the way they block
 *
 * A fake that merely suspended for a long time would pass against an implementation
 * that only looks bounded: a cancellable read is ended by any deadline at all. So the
 * blocking fake ignores cancellation altogether, which is what a blocking driver call
 * does, and the test decides when the read is allowed to answer.
 */
@Tag("fast")
class AuthRepositoryRestoreBoundTest {

    /**
     * The deadline the repository enforces on its restore.
     *
     * The repository keeps one number for it, and this is that number — the test has to
     * know it, because the deadline is measured on the caller's clock and the caller's
     * clock here is virtual: the only way to tell *which* deadline was honoured is to
     * look at the clock. Change the repository's budget and this test has to change with
     * it.
     */
    private val restoreDeadlineMs = 10_000L

    /** How long the caller waits for the session to settle: the budget plus a margin. */
    private val settleBudgetMs = restoreDeadlineMs + 5_000L

    /** The log the repository writes to, and the test reads. */
    private val logWriter = CapturingLogWriter()

    private fun logger(): Logger = Logger(loggerConfigInit(logWriter), "auth-restore-test")

    /**
     * The repository under test.
     *
     * The scope is the test's own, so the whole exchange is on virtual time. The
     * repository gives the restore a scope of its own — that is the bound — and the
     * read it abandons at the deadline is not a leftover coroutine in the test's.
     */
    private fun TestScope.repository(
        storage: SecureStorage,
        gateway: AuthGateway = FakeAuthGateway(),
        preferences: DataStore<Preferences> = FakePreferencesStore(),
    ): SupabaseAuthRepository {
        val legacy = DataStoreSessionStore(preferences, SequentialIds())
        return SupabaseAuthRepository(
            log = logger(),
            gateway = gateway,
            sessionStore = SecureSessionStore(log = logger(), secure = storage, legacy = legacy),
            localOwnerStore = legacy,
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )
    }

    @Test
    fun `a restore that answers in time publishes the session it restored`() = runTest {
        val storage = FakeSecureStorage(
            values = mapOf(
                SecureSessionStore.KEY_ACCESS to "access-1",
                SecureSessionStore.KEY_REFRESH to "refresh-1",
                SecureSessionStore.KEY_USER_ID to "owner-1",
                SecureSessionStore.KEY_EMAIL to "a@b.c",
            ),
        )
        val gateway = FakeAuthGateway(
            refreshResult = Result.success(
                RemoteSession("owner-1", "a@b.c", "access-2", "refresh-2", isAnonymous = false),
            ),
        )
        val repository = repository(storage, gateway)

        repository.restoreFailures.test {
            awaitState(timeoutMs = settleBudgetMs) { repository.currentSession.value !is Session.Loading }
            advanceUntilIdle()

            val session = assertIs<Session.SignedIn>(repository.currentSession.value)
            assertEquals(UserId.fromString("owner-1"), session.userId)
            assertEquals("access-2", session.accessToken, "the refreshed token is the one the device keeps")
            // The failure window closed with the restore, so the stream completed
            // without an event: a reader is told there was no failure.
            awaitComplete()
        }
    }

    @Test
    fun `a restore that ignores cancellation cannot hold the caller past the deadline`() = runTest {
        // The read blocks in a driver call and ignores cancellation: the shape a
        // deadline in the caller's own coroutine cannot end.
        val storage = FakeSecureStorage(behaviour = ReadBehaviour.Block)
        val repository = repository(storage)

        repository.restoreFailures.test {
            awaitState(timeoutMs = settleBudgetMs) { repository.currentSession.value !is Session.Loading }
            advanceUntilIdle()

            assertEquals(
                restoreDeadlineMs,
                testScheduler.currentTime,
                "the caller must be released at the deadline: virtual time was ${testScheduler.currentTime} ms",
            )
            assertFalse(
                storage.readFinished,
                "the restore must still be running: the caller was released, not the read finished",
            )
            assertEquals(Session.SignedOut, repository.currentSession.value)
            awaitItem()
            awaitComplete()
        }
    }

    @Test
    fun `a restore that fails is answered the same way as one that misses the deadline`() = runTest {
        // The conflation the callers' contract depends on: a throwing store and a
        // hanging store mean the same thing to the user — this install has no session
        // it can prove — so they get the same answer.
        val storage = FakeSecureStorage(behaviour = ReadBehaviour.Throw)
        val repository = repository(storage)

        repository.restoreFailures.test {
            awaitState(timeoutMs = settleBudgetMs) { repository.currentSession.value !is Session.Loading }
            advanceUntilIdle()

            assertEquals(Session.SignedOut, repository.currentSession.value)
            awaitItem()
            awaitComplete()
        }
    }

    @Test
    fun `a restore that fails after the caller gave up is reported, not dropped`() = runTest {
        // The restore is abandoned at the deadline and fails afterwards. Nobody is left
        // to handle that failure, so the repository has to report it — the same shape
        // the remote-config port uses for a read it abandoned.
        val storage = FakeSecureStorage(behaviour = ReadBehaviour.BlockThenFail)
        val repository = repository(storage)

        repository.restoreFailures.test {
            awaitState(timeoutMs = settleBudgetMs) { repository.currentSession.value !is Session.Loading }

            assertTrue(
                logWriter.entries.none { it.throwable is IllegalStateException },
                "the failure happens after the deadline, not before it",
            )

            // The abandoned restore is still running: it fails only once it is allowed
            // to answer.
            storage.releaseRead()
            advanceUntilIdle()

            val reported = logWriter.entries.lastOrNull { it.throwable is IllegalStateException }
            assertNotNull(reported, "a failure nobody is left to handle must be reported, not dropped")
            assertEquals(Severity.Warn, reported.severity)
            assertEquals(
                "the store failed after the caller gave up",
                reported.throwable?.message,
                "the report must carry the failure it is about",
            )
            awaitItem()
            awaitComplete()
        }
    }

    /**
     * The bound on the *reader* of the failure stream.
     *
     * The stream used to stay open for the lifetime of the repository, so a reader
     * waiting for a report that would never come — the view model's
     * `restoreFailures.firstOrNull()` — waited for a report that cannot exist, forever.
     * The restore publishes the report at most once and then closes the window, so the
     * absence of a failure is something the reader is told.
     *
     * The reader in this test arrives after the restore is over: it is answered at once,
     * without spending a millisecond of the caller's time waiting for a window that has
     * already closed.
     */
    @Test
    fun `a reader that arrives after the restore is answered without waiting`() = runTest {
        val storage = FakeSecureStorage(
            values = mapOf(
                SecureSessionStore.KEY_ACCESS to "access-1",
                SecureSessionStore.KEY_REFRESH to "refresh-1",
                SecureSessionStore.KEY_USER_ID to "owner-1",
                SecureSessionStore.KEY_EMAIL to "a@b.c",
            ),
        )
        val repository = repository(storage)

        awaitState(timeoutMs = settleBudgetMs) { repository.currentSession.value !is Session.Loading }
        advanceUntilIdle()
        val before = testScheduler.currentTime

        val failure = repository.restoreFailures.firstOrNull()

        assertNull(failure, "a restore that succeeded has no failure to report")
        assertEquals(
            before,
            testScheduler.currentTime,
            "the reader must be answered at once: the window it waits for has already closed",
        )
    }

    /**
     * How a secure storage behaves when it is read.
     *
     * The interesting one is [Block]: it models the read the deadline has to survive —
     * a call that blocks in the driver. It does not merely suspend for a long time; it
     * ignores cancellation altogether, which is what a blocking driver call does.
     */
    private enum class ReadBehaviour {
        /** Answers the stored values. */
        Answer,

        /** Blocks until the test lets it answer. */
        Block,

        /** Blocks until the test lets it answer, and then fails. */
        BlockThenFail,

        /** Fails. */
        Throw,
    }

    /**
     * A [SecureStorage] whose reads can be told to block or to fail.
     *
     * [readFinished] is how a test tells "the read answered" from "the read is still
     * running": the difference between a caller that was released and a caller that
     * waited for the work it gave up on.
     */
    private class FakeSecureStorage(
        private val values: Map<String, String> = emptyMap(),
        private val behaviour: ReadBehaviour = ReadBehaviour.Answer,
    ) : SecureStorage {

        /** The gate a blocked read waits on. */
        private val gate = CompletableDeferred<Unit>()

        /** Whether the read has finished. */
        var readFinished: Boolean = false
            private set

        /** What was written, by key. */
        val written: MutableMap<String, String> = mutableMapOf()

        /** Lets a blocked read continue — and, for [ReadBehaviour.BlockThenFail], fail. */
        fun releaseRead() {
            gate.complete(Unit)
        }

        /**
         * Blocks the read the way a blocking driver call does.
         *
         * No cancellation reaches it: the deadline the caller enforces cannot end a
         * read that is stuck inside the driver.
         */
        private suspend fun blockUntilReleased() {
            withContext(NonCancellable) { gate.await() }
        }

        override suspend fun read(key: String): String? {
            when (behaviour) {
                ReadBehaviour.Answer -> Unit
                ReadBehaviour.Throw -> throw IllegalStateException("the store cannot be read")
                ReadBehaviour.Block, ReadBehaviour.BlockThenFail -> blockUntilReleased()
            }
            readFinished = true
            if (behaviour == ReadBehaviour.BlockThenFail) {
                throw IllegalStateException("the store failed after the caller gave up")
            }
            return values[key]
        }

        override suspend fun write(key: String, value: String) {
            written[key] = value
        }

        override suspend fun delete(key: String) {
            written.remove(key)
        }
    }

    /** A gateway that answers the refresh the test scripted, and nothing else. */
    private class FakeAuthGateway(private val refreshResult: Result<RemoteSession?> = Result.success(null)) :
        AuthGateway {
        override suspend fun signUp(email: String, password: String): Result<RemoteSession> =
            Result.failure(IllegalStateException("not scripted"))

        override suspend fun signIn(email: String, password: String): Result<RemoteSession> =
            Result.failure(IllegalStateException("not scripted"))

        override suspend fun signOut(): Result<Unit> = Result.success(Unit)

        override suspend fun updatePassword(newPassword: String): Result<Unit> = Result.success(Unit)

        override suspend fun sendMagicLink(email: String): Result<Unit> = Result.success(Unit)

        override suspend fun verifyMagicLink(email: String, token: String): Result<RemoteSession> =
            Result.failure(IllegalStateException("not scripted"))

        override suspend fun refresh(accessToken: String, refreshToken: String): Result<RemoteSession?> =
            refreshResult
    }

    /** A preferences store that answers with what it was given. */
    private class FakePreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    /** Ids a test can predict. */
    private class SequentialIds : IdGenerator {
        private var n = 0
        override fun next(): String = "id-${n++}"
    }

    /**
     * A log writer the test reads.
     *
     * The repository reports an abandoned failure through its own logger, and the
     * logger is a constructor argument — so the report can be observed without
     * touching any global state.
     */
    private class CapturingLogWriter : LogWriter() {

        /** One captured log entry. */
        data class Entry(val severity: Severity, val message: String, val tag: String, val throwable: Throwable?)

        /** Everything the repository logged, in order. */
        val entries: MutableList<Entry> = mutableListOf()

        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            entries += Entry(severity, message, tag, throwable)
        }
    }
}
