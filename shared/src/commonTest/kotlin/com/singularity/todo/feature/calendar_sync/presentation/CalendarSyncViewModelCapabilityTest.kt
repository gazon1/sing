package com.singularity.todo.feature.calendar_sync.presentation

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.test.helpers.awaitState
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarAppInfo
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncEvent
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.calendar_sync.sync.DirtyHashProvider
import com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.flow.Flow
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
