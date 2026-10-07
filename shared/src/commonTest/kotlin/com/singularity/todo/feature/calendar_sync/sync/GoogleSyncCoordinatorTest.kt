package com.singularity.todo.feature.calendar_sync.sync

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentialStore
import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentials
import com.singularity.todo.feature.calendar_sync.domain.port.GoogleCalendarSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The coordinator's preconditions, and the difference between declining and failing.
 *
 * ## Why this test exists at all
 *
 * The `UserId.anonymous` guard is the one line in this feature that must never be wrong:
 * every id-bearing store is keyed by user, and anonymous is a value a genuinely signed-out
 * session holds. It was untested because the coordinator took a concrete `GoogleSyncEngine`,
 * whose construction needs four DAOs. [GoogleSyncPass] is the seam that made it testable —
 * five lines of interface instead of a database.
 *
 * The second half of the class pins the bug that seam was extracted alongside: a pass that
 * ran and *threw* used to report `ran = false`, so `GoogleSyncWorker` logged "did not run",
 * answered WorkManager with `success()`, and stopped syncing with no retry and nothing on
 * screen. The failure case is asserted here because that is the only place it is decided.
 */
@Tag("fast")
class GoogleSyncCoordinatorTest {

    private var passes = 0
    private var passFailure: Throwable? = null

    /** A fake that counts calls, so "did it run?" is observable without a database. */
    private val countingPass = GoogleSyncPass { _ ->
        passes++
        passFailure?.let { throw it }
        GoogleSyncEngine.PassResult(seen = 3, pushed = 1)
    }

    private fun coordinator(
        selectedCalendarId: String? = "primary",
        hasCredential: Boolean = true,
        currentUser: UserId = UserId("user-1"),
    ) = GoogleSyncCoordinator(
        engineProvider = { countingPass },
        googleSettings = FakeGoogleSettings(selectedCalendarId),
        credentialStore = FakeCredentialStore(hasCredential),
        currentUser = currentUser,
        clock = kotlin.time.Clock.System,
    )

    /**
     * The guard. A signed-out session must not sync the anonymous profile's credential —
     * which would mean reading whatever account happens to sit under that key.
     */
    @Test
    fun `a signed-out session runs no pass at all`() = runTest {
        val outcome = coordinator(currentUser = UserId.anonymous).syncNow()

        assertIs<GoogleSyncCoordinator.Outcome.Declined>(outcome)
        assertEquals("not signed in", outcome.reason)
        assertEquals(0, passes, "the anonymous profile must not reach the engine, whatever it holds")
    }

    /** The same guard, reached the way it is reached in production: no argument passed. */
    @Test
    fun `the default user is the guard's subject`() = runTest {
        val outcome = coordinator(currentUser = UserId.anonymous).syncNow()

        assertIs<GoogleSyncCoordinator.Outcome.Declined>(outcome)
    }

    @Test
    fun `no calendar selected declines without running a pass`() = runTest {
        val outcome = coordinator(selectedCalendarId = null).syncNow()

        assertIs<GoogleSyncCoordinator.Outcome.Declined>(outcome)
        assertEquals("no Google calendar selected", outcome.reason)
        assertEquals(0, passes)
    }

    @Test
    fun `a blank calendar id is not a calendar`() = runTest {
        val outcome = coordinator(selectedCalendarId = "  ").syncNow()

        assertIs<GoogleSyncCoordinator.Outcome.Declined>(outcome)
        assertEquals(0, passes)
    }

    @Test
    fun `no connected account declines without running a pass`() = runTest {
        val outcome = coordinator(hasCredential = false).syncNow()

        assertIs<GoogleSyncCoordinator.Outcome.Declined>(outcome)
        assertEquals("no Google account connected", outcome.reason)
        assertEquals(0, passes)
    }

    @Test
    fun `a configured profile runs the pass and reports what it did`() = runTest {
        val outcome = coordinator().syncNow()

        val completed = assertIs<GoogleSyncCoordinator.Outcome.Completed>(outcome)
        assertEquals(3, completed.result.seen)
        assertEquals(1, completed.result.pushed)
        assertEquals(1, passes)
    }

    /**
     * The regression. A pass that ran and threw must be [Outcome.Failed] — not
     * [Outcome.Declined] — because the worker retries one and accepts the other. Reporting a
     * failure as a decline is what silently ended background sync.
     */
    @Test
    fun `a pass that throws is Failed, not Declined`() = runTest {
        passFailure = IllegalStateException("Google returned 503")

        val outcome = coordinator().syncNow()

        val failed = assertIs<GoogleSyncCoordinator.Outcome.Failed>(outcome)
        assertEquals("Google returned 503", failed.reason)
        assertEquals(1, passes, "the pass did run — that is what makes this a failure")
    }

    /** A failure with no message still has to be a failure, not a silent decline. */
    @Test
    fun `a failure with no message is still Failed`() = runTest {
        passFailure = IllegalStateException()

        val outcome = coordinator().syncNow()

        assertIs<GoogleSyncCoordinator.Outcome.Failed>(outcome)
    }

    /**
     * The scheduler's question, which decides whether a periodic pass is armed at all.
     * A user who connects an account should get background sync without restarting.
     */
    @Test
    fun `isConfigured follows the account, not the process`() = runTest {
        assertTrue(coordinator().isConfigured())
        assertTrue(!coordinator(hasCredential = false).isConfigured())
        assertTrue(!coordinator(selectedCalendarId = null).isConfigured())
        assertTrue(!coordinator(currentUser = UserId.anonymous).isConfigured())
    }

    private class FakeGoogleSettings(private val selected: String?) : GoogleCalendarSettingsRepository {
        override fun observeSelectedCalendarId(): Flow<String?> = flowOf(selected)
        override suspend fun setSelectedCalendarId(calendarId: String?) = Unit
        override fun observeImportForeignEvents(): Flow<Boolean> = flowOf(false)
        override suspend fun setImportForeignEvents(enabled: Boolean) = Unit
    }

    private class FakeCredentialStore(private val present: Boolean) : GoogleCredentialStore {
        override suspend fun load(userId: String): GoogleCredentials? =
            if (present) GoogleCredentials("access", "refresh", Long.MAX_VALUE) else null

        override suspend fun save(userId: String, credentials: GoogleCredentials) = Unit
        override suspend fun clear(userId: String) = Unit
    }
}
