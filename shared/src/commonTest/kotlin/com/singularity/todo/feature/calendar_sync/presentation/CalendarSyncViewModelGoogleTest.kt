package com.singularity.todo.feature.calendar_sync.presentation

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentialStore
import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentials
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarAppInfo
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncEvent
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.model.ChangePage
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleCalendarSummary
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.ImportWindow
import kotlin.time.Duration.Companion.days
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarEventSource
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.domain.port.GoogleCalendarSettingsRepository
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.calendar_sync.sync.DirtyHashProvider
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncEngine
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncPass
import com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.helpers.awaitState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The Google half of the calendar-sync screen, and the three answers a pass can give.
 *
 * ## What was broken
 *
 * Two defects, both the shape "looks wired, is not":
 *
 * 1. The Google panel's "Sync Now" button dispatched [CalendarSyncIntent.SyncNow], which
 *    goes to `CalendarSyncOrchestrator` — the *device*-calendar projection. Pressing it ran
 *    a system-calendar pass and reported that pass's outcome. A working button, on the wrong
 *    feature, saying something true about something else.
 * 2. A pass that failed reported nothing at all. The screen looked exactly as it had before
 *    the press, which is indistinguishable from an app that has stopped syncing. The only
 *    evidence was a calendar that had quietly stopped updating.
 *
 * These drive the *intent*, not the coordinator, because the first defect was in the
 * routing. A test of the coordinator alone would have passed the whole time.
 */
@Tag("fast")
class CalendarSyncViewModelGoogleTest {

    private object FakeAppQueries : CalendarAppQueries {
        override suspend fun listInstalled(): List<CalendarAppInfo> = emptyList()
    }

    private object FakeScheduler : CalendarSyncWorkScheduler {
        override fun enqueueSync() = Unit
        override fun cancelSync() = Unit
    }

    /** Fails every call: nothing in this test exercises the device calendar. */
    private object UnsupportedProvider : CalendarProviderPort {
        override suspend fun getAvailableCalendars(): Result<Map<String, String>> =
            Result.failure(UnsupportedOperationException("not used by this test"))

        override suspend fun insertEvent(event: CalendarSyncEvent): Result<Long> =
            Result.failure(UnsupportedOperationException("not used by this test"))

        override suspend fun updateEvent(eventId: Long, event: CalendarSyncEvent): Result<Long> =
            Result.failure(UnsupportedOperationException("not used by this test"))

        override suspend fun deleteEvent(eventId: Long): Result<Unit> =
            Result.failure(UnsupportedOperationException("not used by this test"))

        override suspend fun queryEvents(
            calendarId: String?,
            fromMs: Long,
            toMs: Long,
        ): Result<Map<String, Long>> =
            Result.failure(UnsupportedOperationException("not used by this test"))
    }

    private class FakeSyncRepo : CalendarSyncRepository {
        override fun observeEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override fun observeTargetCalendarId(): Flow<String?> = flowOf(null)
        override suspend fun setTargetCalendarId(calendarId: String) = Unit
        override fun observeLastSyncedAt(): Flow<Long?> = flowOf(null)
        override suspend fun setLastSyncedAt(ts: Long) = Unit
        override fun observeStatus(): Flow<CalendarSyncStatus> = flowOf(CalendarSyncStatus.Disabled)
        override suspend fun setStatus(status: CalendarSyncStatus) = Unit
        override fun observeTargetAppPackage(): Flow<String?> = flowOf(null)
        override suspend fun setTargetAppPackage(packageName: String?) = Unit
    }

    private fun settings(calendarId: String?) = object : GoogleCalendarSettingsRepository {
        override fun observeSelectedCalendarId(): Flow<String?> = flowOf(calendarId)
        override suspend fun setSelectedCalendarId(calendarId: String?) = Unit
        override fun observeImportForeignEvents(): Flow<Boolean> = flowOf(false)
        override suspend fun setImportForeignEvents(enabled: Boolean) = Unit
    }

/**
     * A credential store that can lose its grant.
     *
     * Mutable because a revoked grant is only reachable *after* connecting: a profile that
     * never had a credential never reaches the ready state, so there is no Sync button to
     * press and the decline cannot be observed. One instance is shared with the coordinator
     * and the ViewModel, so `revoke()` from a test is visible to both — which is the point:
     * it models the two disagreeing about the same stored token, exactly as they do when a
     * grant really is revoked mid-session.
     */
    private class FakeCredentialStore(private var present: Boolean) : GoogleCredentialStore {
        override suspend fun load(userId: String): GoogleCredentials? =
            if (present) GoogleCredentials("access", "refresh", Long.MAX_VALUE) else null

        override suspend fun save(userId: String, credentials: GoogleCredentials) {
            present = true
        }

        override suspend fun clear(userId: String) {
            present = false
        }

        /** Simulates the grant being revoked outside the app — no intent dispatches this. */
        fun revoke() {
            present = false
        }
    }

    private val anonymousAuth = object : AuthRepository {
        override val currentSession: StateFlow<Session> =
            MutableStateFlow(Session.Anonymous(UserId("user-1")))
        override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun signUp(email: String, password: String): Result<Unit> = unsupported()
        override suspend fun signIn(email: String, password: String): Result<Unit> = unsupported()
        override suspend fun signInAnonymously(): Result<Unit> = unsupported()
        override suspend fun signOut(): Result<Unit> = unsupported()
        override suspend fun migrateAnonymousTo(email: String, password: String): Result<Unit> =
            unsupported()

        private fun unsupported(): Result<Unit> =
            Result.failure(UnsupportedOperationException("no auth flow is exercised by this test"))
    }

    /**
     * A calendar list that succeeds with nothing in it.
     *
     * Not a throwing stub: `SetGoogleConnected(true)` calls `refreshGoogleConnection()`, which
     * lists calendars. A throwing source takes the VM down at `ready()` rather than at the thing
     * under test, and the failure then reads as "the Google sync tests are broken" instead of
     * "the test double was wrong".
     */
    private fun emptyEventSource() = object : CalendarEventSource {
        override suspend fun listCalendars(): List<GoogleCalendarSummary> = emptyList()

        override suspend fun fetchChanges(calendarId: String, syncToken: String?) =
            ChangePage(events = emptyList(), nextSyncToken = null)

        override suspend fun insert(calendarId: String, event: GoogleEvent) = event

        override suspend fun patch(
            calendarId: String,
            eventId: GoogleEventId,
            etag: String?,
            event: GoogleEvent,
        ) = event

        override suspend fun get(calendarId: String, eventId: GoogleEventId) =
            error("no test reads a single Google event")

        override suspend fun cancel(calendarId: String, eventId: GoogleEventId, etag: String?) = Unit
    }

    /**
     * Builds a VM whose Google half is fully connected, so the only thing under test is what
     * the Sync Now button does. [pass] throws to model a failing pass; [selectedCalendarId]
     * and [hasCredential] move the coordinator between its three outcomes.
     *
     * The returned store is shared with the coordinator, so a test can revoke the grant
     * mid-session — the only way to reach the "grant revoked" decline through the UI.
     */
    private fun viewModel(
        pass: GoogleSyncPass,
        scope: CoroutineScope,
        selectedCalendarId: String? = "primary",
        hasCredential: Boolean = true,
        importWindow: ImportWindow = ImportWindow.DEFAULT,
    ): Triple<CalendarSyncViewModel, AutoCloseableCoroutineScope, FakeCredentialStore> {
        val vmScope = testScope(scope)
        // One store, shared: the ViewModel and the coordinator must see the same grant, or
        // the test is asserting two fakes rather than one scenario.
        val store = FakeCredentialStore(hasCredential)
        val coordinator = GoogleSyncCoordinator(
            engineProvider = { pass },
            googleSettings = settings(selectedCalendarId),
            credentialStore = store,
            currentUser = UserId("user-1"),
            clock = kotlin.time.Clock.System,
        )
        val vm = CalendarSyncViewModel(
            syncRepo = FakeSyncRepo(),
            calendarProvider = UnsupportedProvider,
            scheduler = FakeScheduler,
            appQueries = FakeAppQueries,
            orchestrator = CalendarSyncOrchestrator(
                scheduler = FakeScheduler,
                scope = scope,
                dirtyHashProvider = DirtyHashProvider(FakeTaskRepository(), FakeSyncRepo()),
                crashReporter = NoOpCrashReportingPort(),
            ),
            googleSettings = settings(selectedCalendarId),
            credentialStore = store,
            // vmScope, not `scope`: CurrentUser collects its session flow forever in init, so
            // a scope the test does not close leaves a child job and runTest times out.
            currentUser = CurrentUser(anonymousAuth, vmScope),
            eventSource = { emptyEventSource() },
            googleSync = coordinator,
            importWindow = importWindow,
            crashReporter = NoOpCrashReportingPort(),
            scope = vmScope,
        )
        return Triple(vm, vmScope, store)
    }

    /**
     * Puts the VM into the connected-and-ready state the Google Sync button requires.
     *
     * Takes the [TestScope] rather than calling `awaitState` itself, because that helper is
     * an extension on `TestScope` — a plain `suspend fun` has no receiver to resolve it on.
     */
    private fun TestScope.ready(vm: CalendarSyncViewModel) {
        vm.onIntent(CalendarSyncIntent.SetGoogleConnected(true))
        awaitState { vm.stateFlow.value.googleReady }
    }

    /** The routing defect: the Google intent must reach the *Google* pass. */
    @Test
    fun `the Google Sync Now button runs a Google pass`() = runTest {
        var runs = 0
        val (vm, vmScope, _) = viewModel(
            GoogleSyncPass {
                runs++
                GoogleSyncEngine.PassResult(seen = 2)
            },
            this,
        )
        try {
            ready(vm)

            vm.onIntent(CalendarSyncIntent.SyncGoogleNow)
            awaitState { vm.stateFlow.value.googleLastSyncedAt != null }

            assertEquals(1, runs, "the Google intent must reach the Google engine")
        } finally {
            vmScope.close()
        }
    }

    /** A completed pass reports when it ran — a clock that only advances on success. */
    @Test
    fun `a completed pass records when it ran`() = runTest {
        val (vm, vmScope, _) = viewModel(GoogleSyncPass { GoogleSyncEngine.PassResult() }, this)
        try {
            ready(vm)

            vm.onIntent(CalendarSyncIntent.SyncGoogleNow)
            awaitState { vm.stateFlow.value.googleLastSyncedAt != null }

            assertNotNull(vm.stateFlow.value.googleLastSyncedAt)
            assertNull(vm.stateFlow.value.googleSyncError, "a pass that worked has nothing to report")
        } finally {
            vmScope.close()
        }
    }

    /**
     * The defect that mattered. A failure has to reach the screen: before this, the pass ran,
     * failed, and left the UI byte-identical to its pre-press state.
     */
    @Test
    fun `a failed pass is reported to the user`() = runTest {
        val (vm, vmScope, _) = viewModel(
            GoogleSyncPass { throw IllegalStateException("Google returned 503") },
            this,
        )
        try {
            ready(vm)

            vm.onIntent(CalendarSyncIntent.SyncGoogleNow)
            awaitState { vm.stateFlow.value.googleSyncError != null }

            assertEquals("Google returned 503", vm.stateFlow.value.googleSyncError)
            assertNull(
                vm.stateFlow.value.googleLastSyncedAt,
                "a failed pass must not move the success clock",
            )
        } finally {
            vmScope.close()
        }
    }

    /**
     * Declining is not failing, and the two must render differently.
     *
     * Reachable by *losing* the grant after connecting, which is a real state: a revoked or
     * expired token clears the store while the chosen calendar id stays in settings, and the
     * next press declines with "no Google account connected".
     *
     * Not reachable by never having connected: `refreshGoogleConnection()` reads the same
     * store, finds nothing, and leaves `googleConnected = false`, so the Sync button does not
     * render. That is correct behaviour — a profile with no grant has nothing to sync — and
     * it is why the sequence here is connect-then-revoke rather than revoke-then-connect.
     *
     * The assertion that matters is the pair: a decline reports a reason, a failure reports a
     * reason, and neither moves the success clock. Collapsing them was defect 1.
     */
    @Test
    fun `a revoked grant declines with its reason and no success timestamp`() = runTest {
        val (vm, vmScope, store) = viewModel(
            pass = GoogleSyncPass { error("a declined profile must never reach an engine") },
            scope = this,
        )
        try {
            ready(vm)

            // The grant goes away outside the app. No intent models this, so the store is
            // asked directly rather than the test pretending the screen can cause it.
            store.revoke()

            vm.onIntent(CalendarSyncIntent.SyncGoogleNow)
            awaitState { vm.stateFlow.value.googleSyncError != null }

            assertEquals("no Google account connected", vm.stateFlow.value.googleSyncError)
            assertNull(vm.stateFlow.value.googleLastSyncedAt)
        } finally {
            vmScope.close()
        }
    }

    /**
     * The screen must describe the pass that will run, not a second copy of its default.
     *
     * The window used to be a default argument on the engine, the same default on the event
     * source, and a direct `ImportWindow.DEFAULT` read in the screen — three sites that could
     * disagree with no compiler error. The symptom is a sentence describing a 30/90-day window
     * over a pass configured for something else, and nothing anywhere would report it.
     *
     * So the display follows the configuration rather than restating it: a non-default window
     * given to the ViewModel has to come back out on the state the screen renders.
     */
    @Test
    fun `the screen's window is the configured one, not the constant`() = runTest {
        val configured = ImportWindow(past = 7.days, future = 14.days)
        val (vm, vmScope, _) = viewModel(
            pass = GoogleSyncPass { GoogleSyncEngine.PassResult() },
            scope = this,
            importWindow = configured,
        )
        try {
            assertEquals(
                configured,
                vm.stateFlow.value.importWindow,
                "the screen must render the window the pass is configured with",
            )
            assertNotEquals(
                ImportWindow.DEFAULT,
                vm.stateFlow.value.importWindow,
                "this test is only meaningful while the two differ",
            )
        } finally {
            vmScope.close()
        }
    }
}
