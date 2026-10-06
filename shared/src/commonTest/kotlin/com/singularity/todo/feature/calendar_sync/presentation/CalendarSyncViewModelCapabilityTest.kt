package com.singularity.todo.feature.calendar_sync.presentation

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.test.helpers.awaitState
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentialStore
import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentials
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarAppInfo
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncEvent
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.domain.port.GoogleCalendarSettingsRepository
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.calendar_sync.sync.DirtyHashProvider
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator
import com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Desktop calendar-sync screen used to lie.
 *
 * `NoopCalendarProvider.getAvailableCalendars()` fails on Desktop, but nothing read that
 * failure as a capability signal. The consequences were all downstream of the same silence:
 *
 * - the "Enable Sync" switch was live, and flipping it wrote to a repository whose
 *   `observeEnabled()` is `flowOf(false)` — so the optimistic `updateState` was never
 *   corrected and the toggle stayed ON;
 * - "Sync Now" pushed into a CONFLATED channel nobody consumes, because
 *   `CalendarSyncOrchestrator.start()` has exactly one call site and it is in Android's
 *   `Application`;
 * - the calendar list showed a red "No calendars available", which reads as "you have no
 *   calendars" rather than "this platform has no calendar support at all".
 *
 * The provider failure is the honest signal and it already exists. These tests pin that it
 * reaches the UI state, because the next change to that `onFailure` branch would otherwise
 * silently restore the lie.
 */
@Tag("fast")
class CalendarSyncViewModelCapabilityTest {

    private class FakeSyncRepo(private val enabled: Boolean = false) : CalendarSyncRepository {
        override fun observeEnabled(): Flow<Boolean> = flowOf(enabled)
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

    /** Mirrors `NoopCalendarProvider`: every call reports the platform cannot do this. */
    private object UnsupportedProvider : CalendarProviderPort {
        override suspend fun getAvailableCalendars(): Result<Map<String, String>> =
            Result.failure(UnsupportedOperationException("Calendar sync is not available on this platform"))

        override suspend fun insertEvent(event: CalendarSyncEvent): Result<Long> =
            Result.failure(UnsupportedOperationException("not available"))

        override suspend fun updateEvent(eventId: Long, event: CalendarSyncEvent): Result<Long> =
            Result.failure(UnsupportedOperationException("not available"))

        override suspend fun deleteEvent(eventId: Long): Result<Unit> =
            Result.failure(UnsupportedOperationException("not available"))

        override suspend fun queryEvents(calendarId: String?, fromMs: Long, toMs: Long): Result<Map<String, Long>> =
            Result.success(emptyMap())
    }

    private object WorkingProvider : CalendarProviderPort {
        override suspend fun getAvailableCalendars(): Result<Map<String, String>> =
            Result.success(mapOf("work" to "Work"))

        override suspend fun insertEvent(event: CalendarSyncEvent): Result<Long> =
            Result.success(1L)

        override suspend fun updateEvent(eventId: Long, event: CalendarSyncEvent): Result<Long> =
            Result.success(1L)

        override suspend fun deleteEvent(eventId: Long): Result<Unit> = Result.success(Unit)

        override suspend fun queryEvents(calendarId: String?, fromMs: Long, toMs: Long): Result<Map<String, Long>> =
            Result.success(emptyMap())
    }

    private object FakeScheduler : CalendarSyncWorkScheduler {
        override fun enqueueSync() = Unit
        override fun cancelSync() = Unit
    }

    private object FakeAppQueries : CalendarAppQueries {
        override suspend fun listInstalled(): List<CalendarAppInfo> = emptyList()
    }

    /**
     * The Google half of the ViewModel's dependencies, absent from this screen's behaviour.
     *
     * These exist only because [CalendarSyncViewModel] now also serves the Google provider, so
     * constructing one requires them. Every value here is the disconnected, unconfigured
     * state — which is exactly the state a Desktop user with no Google account is in, and the
     * one these tests are about. Nothing in this class reads them.
     */
    private object DisconnectedGoogleSettings : GoogleCalendarSettingsRepository {
        override fun observeSelectedCalendarId(): Flow<String?> = flowOf(null)
        override suspend fun setSelectedCalendarId(calendarId: String?) = Unit
        override fun observeImportForeignEvents(): Flow<Boolean> = flowOf(false)
        override suspend fun setImportForeignEvents(enabled: Boolean) = Unit
    }

    /** No grant stored, so `load` answers "not connected" without touching storage. */
    private object EmptyCredentialStore : GoogleCredentialStore {
        override suspend fun load(userId: String): GoogleCredentials? = null
        override suspend fun save(userId: String, credentials: GoogleCredentials) = Unit
        override suspend fun clear(userId: String) = Unit
    }

    /**
     * Only [CurrentUser]'s `authRepository` constructor argument is consulted for its live id,
     * and these tests never read it. Anonymous is the honest value: it is what a signed-out
     * user has, which is the state a Desktop user reading this screen is in.
     */
    private val anonymousAuth = object : AuthRepository {
        override val currentSession: StateFlow<Session> =
            MutableStateFlow(Session.Anonymous(UserId.anonymous))
        override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun signUp(email: String, password: String): Result<Unit> = unsupported()
        override suspend fun signIn(email: String, password: String): Result<Unit> = unsupported()
        override suspend fun signInAnonymously(): Result<Unit> = unsupported()
        override suspend fun signOut(): Result<Unit> = unsupported()
        override suspend fun migrateAnonymousTo(email: String, password: String): Result<Unit> = unsupported()

        private fun unsupported(): Result<Unit> =
            Result.failure(UnsupportedOperationException("no auth flow is exercised by this test"))
    }

    private fun viewModel(
        provider: CalendarProviderPort,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<CalendarSyncViewModel, AutoCloseableCoroutineScope> {
        val vmScope = testScope(scope)
        return CalendarSyncViewModel(
            syncRepo = FakeSyncRepo(),
            calendarProvider = provider,
            scheduler = FakeScheduler,
            appQueries = FakeAppQueries,
            orchestrator = CalendarSyncOrchestrator(
                scheduler = FakeScheduler,
                scope = scope,
                dirtyHashProvider = DirtyHashProvider(FakeTaskRepository(), FakeSyncRepo()),
                crashReporter = NoOpCrashReportingPort(),
            ),
            googleSettings = DisconnectedGoogleSettings,
            credentialStore = EmptyCredentialStore,
            // vmScope, not the test scope: CurrentUser collects its session flow forever in
            // init, so a scope this test does not close leaves a child job running and
            // runTest times out after a minute waiting for it. vmScope.close() ends it.
            currentUser = CurrentUser(anonymousAuth, vmScope),
            eventSource = { error("this test never reaches the Google event source") },
            // A pass that declines, matching the disconnected state above. This test is
            // about the *system* calendar's capability flag, so the Google half has to be
            // inert — but it still has to be inert through the real coordinator, or the
            // guard would be exercised only by this wiring rather than by its own tests.
            googleSync = GoogleSyncCoordinator(
                engineProvider = { error("a declined profile must never reach an engine") },
                googleSettings = DisconnectedGoogleSettings,
                credentialStore = EmptyCredentialStore,
                currentUser = UserId.anonymous,
                clock = kotlin.time.Clock.System,
            ),
            crashReporter = NoOpCrashReportingPort(),
            scope = vmScope,
        ) to vmScope
    }

    @Test
    fun `an unsupported provider marks the feature unsupported`() = runTest {
        val (vm, vmScope) = viewModel(UnsupportedProvider, this)
        try {
            vm.onIntent(CalendarSyncIntent.LoadCalendars)
            // Await the outcome, not the transient isLoading flag: isLoading starts false,
            // so a predicate on it passes before the launch has even set it true.
            awaitState { !vm.state.value.isSupported }

            assertFalse(
                vm.state.value.isSupported,
                "a provider that reports the platform unsupported must not leave the UI supported",
            )
        } finally {
            vmScope.close()
        }
    }

    @Test
    fun `a working provider leaves the feature supported`() = runTest {
        val (vm, vmScope) = viewModel(WorkingProvider, this)
        try {
            vm.onIntent(CalendarSyncIntent.LoadCalendars)
            // Same reason: wait for the calendars to land, not for a flag that is
            // momentarily false both before and after the load.
            awaitState { vm.state.value.availableCalendars.isNotEmpty() }

            assertTrue(
                vm.state.value.isSupported,
                "Android must not be capability-gated: a working provider reports success",
            )
            assertEquals(
                mapOf("work" to "Work"),
                vm.state.value.availableCalendars,
            )
        } finally {
            vmScope.close()
        }
    }

    @Test
    fun `the default state is supported so Android is unaffected`() {
        assertTrue(CalendarSyncUiState().isSupported, "default must not gate a working platform")
    }
}
