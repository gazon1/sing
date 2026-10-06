package com.singularity.todo.feature.calendar_sync.presentation

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.ui.featureSlot.combineStates
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarAppInfo
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentialStore
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleCalendarSummary
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarEventSource
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.domain.port.GoogleCalendarSettingsRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator
import com.singularity.todo.feature.calendar_sync.sync.SyncSource
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant

/**
 * UI state for the calendar sync settings screen.
 */
data class CalendarSyncUiState(
    /**
     * Which calendar this tab is configuring.
     *
     * A selector rather than two tabs, because the two features are alternatives — the user
     * wants their tasks on *a* calendar, not on both — and because Google works on desktop
     * where the system calendar does not. Splitting into two settings entries would have had
     * a desktop user looking at an "Android only" panel beside a working one.
     */
    val provider: CalendarProvider = CalendarProvider.SystemCalendar,
    val isEnabled: Boolean = false,
    val availableCalendars: Map<String, String> = emptyMap(),
    val selectedCalendarId: String? = null,
    val status: CalendarSyncStatus = CalendarSyncStatus.Disabled,
    val lastSyncedAt: Long? = null,
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    /** List of installed calendar apps for the picker. */
    val availableApps: List<CalendarAppInfo> = emptyList(),
    /** Currently selected calendar app package (null = system default). */
    val selectedAppPackage: String? = null,
    /** Whether a Google account is connected for this profile. */
    val googleConnected: Boolean = false,
    /**
     * True when the connected Google grant has no refresh token, so background sync will
     * stop when the access token expires and the user has to re-authorise.
     */
    val googleCanRenew: Boolean = true,
    /** Calendars the account can write to, for the Google picker. */
    val googleCalendars: List<GoogleCalendarSummary> = emptyList(),
    val selectedGoogleCalendarId: String? = null,
    /** Whether to pull Google events the app did not create into the app. */
    val importFromGoogle: Boolean = false,
    /**
     * Why the last attempt to read the Google calendar list failed, if it did.
     *
     * Kept apart from an empty [googleCalendars] list on purpose: "no calendars" and
     * "could not reach Google" are different problems with different fixes, and collapsing
     * them into one empty picker is how a user ends up staring at a control that has
     * nothing in it and no explanation.
     */
    val googleError: String? = null,
    /**
     * Whether a Google sync pass is running right now.
     *
     * Separate from [isLoading], which is the *calendar list* loading. They are different
     * requests with different failure modes, and one flag for both meant the "Sync Now"
     * button had nothing to reflect: it either disabled itself while the picker loaded, or
     * did nothing at all while a pass ran.
     */
    val googleSyncing: Boolean = false,
    /**
     * Why the last Google sync pass failed, if it did.
     *
     * Distinct from [googleError] on purpose, and the distinction is the whole point of this
     * field. [googleError] means "I could not list your calendars", which happens while
     * connecting. This means "I tried to sync and it did not work", which is the failure a
     * user hits days later, when they have stopped expecting anything to happen — and it was
     * previously reported nowhere at all. A pass that fails silently is indistinguishable
     * from an app that has stopped syncing, and the user's only evidence is a calendar that
     * quietly stopped updating.
     */
    val googleSyncError: String? = null,
    /**
     * When the last successful Google pass finished, or null if there has not been one.
     *
     * Reported only after a pass that actually completed. Rendering the clock after a
     * *failed* pass would be the confidently-wrong answer this feature has to avoid.
     */
    val googleLastSyncedAt: Instant? = null,
    /**
     * Whether this platform can sync to a system calendar at all.
     *
     * Defaults to `true`, and flips to `false` when [CalendarProviderPort.getAvailableCalendars]
     * fails with an unsupported-platform error — which is what `NoopCalendarProvider` returns.
     *
     * Without this the screen lies. On Desktop, `setEnabled` wrote to a repository whose
     * `observeEnabled()` is `flowOf(false)`: the switch was flipped optimistically, nothing
     * corrected it, and the user was left looking at an enabled toggle that had never synced
     * anything. A capability flag is the honest signal, and the failure result already carries it.
     */
    val isSupported: Boolean = true,
) {
    /** True when the user has connected Google and picked a calendar. */
    val googleReady: Boolean get() = googleConnected && selectedGoogleCalendarId != null
}

/**
 * Which calendar the settings tab is configuring.
 *
 * The two are genuinely different features, not two implementations of one: the system
 * calendar is a one-way projection of tasks onto a device calendar, while Google is a
 * two-way peer. Modelling them as one provider with capability flags would have made the
 * honest answer to "can this platform do that?" a nullable field on every call.
 */
enum class CalendarProvider {
    /** Android's own calendar. One-way. Not available on desktop. */
    SystemCalendar,

    /** Google Calendar. Two-way, on every platform. */
    Google,
}

/**
 * User intents for the calendar sync settings screen.
 */
sealed interface CalendarSyncIntent : MviIntent {
    data object LoadCalendars : CalendarSyncIntent
    data class SetEnabled(val enabled: Boolean) : CalendarSyncIntent
    data class SelectCalendar(val calendarId: String) : CalendarSyncIntent
    data object SyncNow : CalendarSyncIntent

    /**
     * Run one Google pass now.
     *
     * A separate intent from [SyncNow] because the two drive different engines. [SyncNow]
     * goes to `CalendarSyncOrchestrator`, which projects tasks onto a *device* calendar; the
     * Google half is a two-way peer sync with its own coordinator and its own cursor. The
     * Google button used to dispatch [SyncNow], so pressing "Sync Now" under Google started a
     * system-calendar pass and reported its outcome — a button that worked, visibly, on the
     * wrong feature.
     */
    data object SyncGoogleNow : CalendarSyncIntent

    /** Handled by the UI layer (rememberLauncherForActivityResult). */
    data object RequestPermission : CalendarSyncIntent
    data class SetPermission(val granted: Boolean) : CalendarSyncIntent

    /** Select which calendar app to sync to (null = system default). */
    data class SelectAppPackage(val packageName: String?) : CalendarSyncIntent

    /** Switch the tab between the system calendar and Google. */
    data class SelectProvider(val provider: CalendarProvider) : CalendarSyncIntent

    /** Connect or disconnect the Google account. */
    data class SetGoogleConnected(val connected: Boolean) : CalendarSyncIntent

    data class SelectGoogleCalendar(val calendarId: String) : CalendarSyncIntent

    /** Whether to pull Google events the app did not create into the app. */
    data class SetImportFromGoogle(val enabled: Boolean) : CalendarSyncIntent
}

/**
 * Canonical ViewModel for calendar sync settings.
 *
 * Covers two providers — see [CalendarProvider] — which means it carries two sets of
 * dependencies. They are kept side by side rather than behind a common interface because
 * they have nothing in common beyond being calendars: the system half writes into a
 * device provider, the Google half reads a REST API as a peer.
 *
 * - [syncRepo] — system-calendar settings (DataStore-backed)
 * - [calendarProvider] — system calendar provider (ContentResolver on Android)
 * - [scheduler] — WorkManager scheduler (used for cancel only)
 * - [appQueries] — queries installed calendar apps for the picker
 * - [orchestrator] — debounced sync orchestrator (hands off to scheduler)
 * - [googleSettings] — the user's Google *choices* (which calendar, import on or off)
 * - [credentialStore] — the user's Google *credentials*, and the only honest source for
 *   "is an account connected"
 * - [currentUser] — the active profile; the credential store is keyed by it
 * - [eventSource] — a *factory*, resolved per call rather than captured once, so a profile
 *   switch mid-session cannot leave the screen talking to the previous profile's calendar
 * - [crashReporter] — see below
 * - [scope] — [AutoCloseableCoroutineScope] for launching concurrent operations. Derived from
 *   [crashReporter] unless a test supplies its own: a scope supplied here alongside
 *   [crashReporter] is chosen independently, so nothing would guarantee that a handled failure
 *   and an escaped background failure reach the same place.
 */
class CalendarSyncViewModel(
    private val syncRepo: CalendarSyncRepository,
    private val calendarProvider: CalendarProviderPort,
    private val scheduler: com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler,
    private val appQueries: CalendarAppQueries,
    private val orchestrator: CalendarSyncOrchestrator,
    private val googleSettings: GoogleCalendarSettingsRepository,
    private val credentialStore: GoogleCredentialStore,
    private val currentUser: CurrentUser,
    private val eventSource: () -> CalendarEventSource,
    /**
     * Runs one Google pass, for the Google half's "Sync Now".
     *
     * Injected rather than constructed so this ViewModel — which is where the user learns
     * whether Google sync is working — can be tested against a pass that succeeds, declines,
     * or fails, without a network client or a database. The three cases produce three
     * different things the user should see, and only the failure one was missing.
     */
    private val googleSync: GoogleSyncCoordinator,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<CalendarSyncUiState, CalendarSyncIntent, Nothing>(
        initialState = CalendarSyncUiState(),
        crashReporter = crashReporter,
        scope = scope,
    ) {
    // MviViewModel handles addCloseable(scope) — no manual call needed

    init {
        // The transform projects; the collector applies. Writing state from inside a
        // `combine` transform is the anti-pattern `NoCombineSideEffect` exists for — the
        // write re-fires on every upstream emission instead of being applied once per
        // emission to a value the transform returned. The transform returns the reducer
        // so the flow's value type stays `CalendarSyncUiState.Content` with no
        // hand-written intermediate class.
        vmScope.launch {
            combineStates(
                syncRepo.observeEnabled(),
                syncRepo.observeTargetCalendarId(),
                syncRepo.observeStatus(),
                syncRepo.observeLastSyncedAt(),
                syncRepo.observeTargetAppPackage(),
                googleSettings.observeSelectedCalendarId(),
                googleSettings.observeImportForeignEvents(),
            ) { enabled, calendarId, status, lastAt, appPkg, googleId, importForeign ->
                { content: CalendarSyncUiState ->
                    content.copy(
                        isEnabled = enabled,
                        selectedCalendarId = calendarId,
                        status = status,
                        lastSyncedAt = lastAt,
                        selectedAppPackage = appPkg,
                        selectedGoogleCalendarId = googleId,
                        importFromGoogle = importForeign,
                    )
                }
            }.collect { reduce -> updateState { reduce(it) } }
        }
    }

    override fun onIntent(intent: CalendarSyncIntent) {
        when (intent) {
            is CalendarSyncIntent.LoadCalendars -> loadCalendars()
            is CalendarSyncIntent.SetEnabled -> setEnabled(intent.enabled)
            is CalendarSyncIntent.SelectCalendar -> selectCalendar(intent.calendarId)
            CalendarSyncIntent.SyncNow -> syncNow()
            CalendarSyncIntent.SyncGoogleNow -> syncGoogleNow()
            is CalendarSyncIntent.RequestPermission -> { /* UI layer */ }
            is CalendarSyncIntent.SetPermission -> setPermission(intent.granted)
            is CalendarSyncIntent.SelectAppPackage -> selectAppPackage(intent.packageName)
            is CalendarSyncIntent.SelectProvider -> selectProvider(intent.provider)
            is CalendarSyncIntent.SetGoogleConnected -> setGoogleConnected(intent.connected)
            is CalendarSyncIntent.SelectGoogleCalendar -> selectGoogleCalendar(intent.calendarId)
            is CalendarSyncIntent.SetImportFromGoogle -> setImportFromGoogle(intent.enabled)
        }
    }

    /**
     * Switching provider resets the loaded data rather than keeping both.
     *
     * The two lists come from different sources and describe different calendars, so leaving
     * the system calendar's rows visible while Google is selected would show a picker full
     * of ids the next request will not match.
     */
    private fun selectProvider(provider: CalendarProvider) {
        updateState {
            it.copy(
                provider = provider,
                availableCalendars = emptyMap(),
                availableApps = emptyList(),
                googleCalendars = emptyList(),
                googleError = null,
            )
        }
        if (provider == CalendarProvider.Google) {
            vmScope.launch { refreshGoogleConnection() }
        }
    }

    /**
     * Disconnecting clears the account's calendar choice too.
     *
     * Leaving a stale id behind would mean a later reconnect silently syncs to a calendar
     * the user has not looked at since, which is the kind of surprise that makes people
     * distrust a sync feature entirely.
     */
    private fun setGoogleConnected(connected: Boolean) {
        vmScope.launch {
            if (!connected) {
                credentialStore.clear(currentUser.current.value)
                googleSettings.setSelectedCalendarId(null)
                updateState {
                    it.copy(
                        googleConnected = false,
                        googleCanRenew = true,
                        googleCalendars = emptyList(),
                        selectedGoogleCalendarId = null,
                        googleError = null,
                    )
                }
                return@launch
            }
            updateState { it.copy(googleConnected = true, googleError = null) }
            refreshGoogleConnection()
        }
    }

    private fun selectGoogleCalendar(calendarId: String) {
        vmScope.launch {
            googleSettings.setSelectedCalendarId(calendarId)
            if (currentState.isEnabled) {
                orchestrator.requestSync(SyncSource.ConfigChanged)
            }
        }
    }

    /**
     * Import can be turned on before an account is connected.
     *
     * The setting is kept regardless, because the user's answer does not change based on
     * whether they have finished signing in — and requiring the order would make the toggle
     * appear to do nothing when it is pressed first.
     */
    private fun setImportFromGoogle(enabled: Boolean) {
        vmScope.launch {
            googleSettings.setImportForeignEvents(enabled)
            if (enabled && currentState.isEnabled) {
                orchestrator.requestSync(SyncSource.ConfigChanged)
            }
        }
    }

    /**
     * Re-reads the credential, then the calendar list.
     *
     * Connected-ness is read from the credential store and *not* assumed from the fact that
     * the screen is open. An access token that expired an hour ago with no refresh token to
     * renew it leaves the account "connected" in every other sense while being unable to
     * make a single call, and the screen has to be able to say so.
     */
    private suspend fun refreshGoogleConnection() {
        updateState { it.copy(isLoading = true, googleError = null) }
        val credentials = credentialStore.load(currentUser.current.value)
        if (credentials == null) {
            updateState { it.copy(isLoading = false, googleConnected = false) }
            return
        }
        updateState { it.copy(googleConnected = true, googleCanRenew = credentials.canRenew) }

        val result = runCatchingResult { eventSource().listCalendars() }
        updateState {
            result.fold(
                onSuccess = { calendars -> it.copy(googleCalendars = calendars, isLoading = false) },
                onFailure = { error ->
                    Logger.w(error) { "Failed to list Google calendars" }
                    it.copy(
                        isLoading = false,
                        googleCalendars = emptyList(),
                        googleError = error.message ?: "Could not reach Google Calendar",
                    )
                },
            )
        }
    }

    private fun loadGoogleCalendars() {
        vmScope.launch { refreshGoogleConnection() }
    }

    private fun loadCalendars() {
        vmScope.launch {
            updateState { it.copy(isLoading = true) }

            // Load calendar apps and calendars in parallel
            val apps = try {
                appQueries.listInstalled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Swallowed into an empty list, so the picker silently shows no apps and the
                // only trace was a warn line nothing reads.
                Logger.w(e) { "Failed to list installed calendar apps" }
                crashReporter.report(e, LIST_APPS_FAILED)
                emptyList()
            }
            val calendarsResult = calendarProvider.getAvailableCalendars()

            calendarsResult
                .onSuccess { calendars ->
                    updateState {
                        it.copy(
                            availableCalendars = calendars,
                            availableApps = apps,
                            isLoading = false,
                        )
                    }
                }
                .onFailure {
                    updateState {
                        it.copy(
                            availableApps = apps,
                            isLoading = false,
                            // The provider is the authority on whether this platform can sync at
                            // all; its failure is the capability signal. Read it here so the UI
                            // can say so instead of presenting controls that do nothing.
                            isSupported = false,
                        )
                    }
                }
        }
    }

    private fun setEnabled(enabled: Boolean) {
        vmScope.launch {
            syncRepo.setEnabled(enabled)
            // No `updateState { it.copy(isEnabled = enabled) }` here. The collector in
            // `init` already projects `isEnabled` from `syncRepo.observeEnabled()`, so an
            // optimistic write is redundant on a working repository and actively wrong on
            // a no-op one: it made the toggle report a state the repository never
            // accepted. Over a `NoopCalendarSyncRepository` the switch snapped on, then
            // off again on the next emission — a control that appears to work and does not.
            if (enabled) {
                orchestrator.requestSync(SyncSource.ConfigChanged)
            } else {
                scheduler.cancelSync()
            }
        }
    }

    private fun selectCalendar(calendarId: String) {
        vmScope.launch {
            syncRepo.setTargetCalendarId(calendarId)
            updateState { it.copy(selectedCalendarId = calendarId) }
            if (currentState.isEnabled) {
                orchestrator.requestSync(SyncSource.ConfigChanged)
            }
        }
    }

    private fun syncNow() {
        orchestrator.requestSync(SyncSource.Manual)
    }

    /**
     * One Google pass, and — the part that was missing — a report either way.
     *
     * The three outcomes are three different things to tell the user, and reporting only two
     * of them is how a sync stops working without anyone finding out:
     *
     * - [Outcome.Completed] — record the time. A clock that only advances on success.
     * - [Outcome.Declined] — say why nothing ran, because "nothing happened" and "we chose
     *   not to act" are different to the person watching the screen.
     * - [Outcome.Failed] — say it failed. Before this, a failed pass rendered as an
     *   untouched button: indistinguishable from an app that had quietly stopped syncing.
     *
     * The failure text goes in [CalendarSyncUiState.googleSyncError] rather than into a log,
     * because a log is not somewhere a user can look.
     */
    private fun syncGoogleNow() {
        vmScope.launch {
            updateState { it.copy(googleSyncing = true, googleSyncError = null) }
            val outcome = googleSync.syncNow()
            updateState {
                when (outcome) {
                    is GoogleSyncCoordinator.Outcome.Completed -> it.copy(
                        googleSyncing = false,
                        googleSyncError = null,
                        // Converted here, at the one seam where the two Instant
                        // types meet, rather than making either side carry a
                        // conversion it has no other use for.
                        googleLastSyncedAt = Instant.fromEpochMilliseconds(
                            googleSync.completedAt().toEpochMilliseconds(),
                        ),
                    )

                    is GoogleSyncCoordinator.Outcome.Declined -> it.copy(
                        googleSyncing = false,
                        googleSyncError = outcome.reason,
                    )

                    is GoogleSyncCoordinator.Outcome.Failed -> it.copy(
                        googleSyncing = false,
                        googleSyncError = outcome.reason,
                    )
                }
            }
        }
    }

    private fun setPermission(granted: Boolean) {
        updateState { it.copy(hasPermission = granted) }
        if (granted) {
            loadCalendars()
        }
    }

    private fun selectAppPackage(packageName: String?) {
        vmScope.launch {
            syncRepo.setTargetAppPackage(packageName)
            updateState { it.copy(selectedAppPackage = packageName) }
            if (currentState.isEnabled) {
                orchestrator.requestSync(SyncSource.ConfigChanged)
            }
        }
    }

    private companion object {
        // Machine-shaped grouping keys — these leave the device.
        const val LIST_APPS_FAILED = "calendar_sync.list_apps_failed"
    }
}
