package com.singularity.todo.feature.calendarSync.presentation

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.core.text.StaticText
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.ui.featureSlot.combineStates
import com.singularity.todo.feature.calendarSync.domain.model.CalendarAppInfo
import com.singularity.todo.feature.calendarSync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendarSync.auth.GoogleCredentialStore
import com.singularity.todo.feature.calendarSync.auth.GoogleAuthorizer
import com.singularity.todo.feature.calendarSync.auth.GoogleAuthorizationRequest
import com.singularity.todo.feature.calendarSync.auth.GoogleGrant
import com.singularity.todo.feature.calendarSync.auth.PendingGoogleAuthorization
import com.singularity.todo.feature.calendarSync.domain.model.GoogleCalendarSummary
import com.singularity.todo.feature.calendarSync.domain.model.ImportWindow
import com.singularity.todo.feature.calendarSync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendarSync.domain.port.CalendarEventSource
import com.singularity.todo.feature.calendarSync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendarSync.domain.port.CalendarSyncRepository
import com.singularity.todo.feature.calendarSync.domain.port.GoogleCalendarSettingsRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.feature.calendarSync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.calendarSync.sync.GoogleSyncCoordinator
import com.singularity.todo.feature.calendarSync.sync.SyncSource
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
    val availableApps: ImmutableList<CalendarAppInfo> = persistentListOf(),
    /** Currently selected calendar app package (null = system default). */
    val selectedAppPackage: String? = null,
    /** Whether a Google account is connected for this profile. */
    val googleConnected: Boolean = false,
    /**
     * Whether the system calendar can be reached on this platform.
     *
     * The desktop has no calendar this app may write to, and the ports it gets there
     * are no-ops — so the screen has to say "needs Android" instead of offering a
     * switch that does nothing. The ports are the authority: each of them knows what
     * it can do, and the panel needs all three — the one that writes, the one that
     * stores the preference, and the one that lists the apps to write into.
     */
    val deviceSyncSupported: Boolean = true,
    /**
     * The OAuth client id the field holds — the one the user is typing.
     *
     * It is a [FieldOwnedText] so that every write to it says what kind of write it
     * was: the field's own text (`echoed`), the value that was on the disk when the
     * panel opened (`seeded`), or an empty field (`cleared`). A bare string could be
     * written from anywhere, and the write would say nothing about what it was —
     * and this value is the field's, so the promise has to live in its type.
     *
     * A client id is not a secret — it ships inside the APK — but the project behind
     * it decides whose calendar the sync writes into, and that is worth being visible.
     */
    val googleClientId: FieldOwnedText = FieldOwnedText.empty(),
    /**
     * True while the consent step is running.
     *
     * The sign-in happens in a browser, so the app is not doing anything visible for
     * a while. Without this the button looks broken for the whole of it.
     */
    val googleAuthorizing: Boolean = false,
    /**
     * The sign-in the screen has to carry out, or null when there is none.
     *
     * The consent is a platform activity, so the screen owns the launcher that runs
     * it and the ViewModel owns the state — the same split the calendar permission
     * request uses. The request carries the *public* half of the PKCE pair; the
     * secret half stays in the ViewModel and never reaches the screen.
     */
    val googleAuthorizationRequest: GoogleAuthorizationRequest? = null,
    /**
     * The client id the sign-in would use, or null when none is configured.
     *
     * Separate from [googleClientId], which is what the user is *typing*: the field
     * holds a half-finished id most of the time, and the consent needs the id that
     * is actually configured.
     */
    val googleConfiguredClientId: String? = null,
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
     * Why the sign-in did not finish, if it did not.
     *
     * Separate from [googleError], which means "I could not list your calendars".
     * A sign-in failure belongs to the *account* section, which is the only section
     * rendered while the account is not connected — a sign-in error written into
     * [googleError] would land in the calendar picker, and that picker is not rendered
     * at all until the account is connected. The failure would then be invisible.
     */
    val googleSignInError: String? = null,
    /**
     * Why the credential was not revoked, if it was not.
     *
     * "Disconnect" clears the credential on this device and asks the server to cancel the
     * grant. The two can come apart: the device forgets the token, and the server keeps
     * renewing it. The person is then disconnected *here* while a live grant sits on a
     * server they cannot see — which is the promise the button did not keep.
     *
     * It belongs to the account section, like [googleSignInError]: that is the only
     * section rendered while the account is not connected, and this failure only ever
     * happens on the way to disconnected.
     */
    val googleDisconnectError: String? = null,
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
     * How far the Google listing reaches, as the engine is actually configured.
     *
     * Not `ImportWindow.DEFAULT` read again here. That constant was the display's
     * source and the engine's source, and the screen's copy could drift from the
     * engine's without anything noticing — the screen would confidently describe a
     * window the pass does not use. So the window is bound once in DI, injected into
     * the engine and the event source, and read from state here: one value, and the
     * sentence on screen describes the pass that will actually run.
     *
     * When this becomes user-editable it moves to [GoogleCalendarSettingsRepository]
     * and is observed rather than injected — the same shape item 1's fix took, and
     * the reason that fix is the template for this one.
     */
    val importWindow: ImportWindow = ImportWindow.DEFAULT,
) {
    /**
     * The client id as the field's source.
     *
     * The ViewModel holds the id the user typed and hands it back unchanged; the
     * configured id replaces it with one the field never produced, which the field
     * takes as it stands. So the value is never rewritten with one the field
     * produced: the field's echo guard needs no lifting, and there is no counter to
     * read.
     *
     * The promise is in the type of the value, not in this sentence: the client id is
     * a [FieldOwnedText], and the only ways to write one are the field's own text, a
     * seed and a clear.
     */
    val googleClientIdSource: StaticText get() = StaticText(googleClientId.text)

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

    /**
     * Disconnect the Google account.
     *
     * Connecting is [ConnectGoogle] and not this intent: connecting means running
     * an OAuth consent step in a browser, and folding that into a flag called
     * "connected" is what made the button a no-op — it set a boolean that the
     * credential store then contradicted, and the user was left with a control
     * that did nothing and no explanation.
     */
    data class SetGoogleConnected(val connected: Boolean) : CalendarSyncIntent

    /**
     * Run the Google consent step and store the credential.
     *
     * The consent is a platform activity, so this intent does not sign anybody in by
     * itself: it prepares the request, and the screen carries the consent out and
     * reports back with [GoogleAuthorizationCompleted] or [GoogleAuthorizationFailed].
     */
    data object ConnectGoogle : CalendarSyncIntent

    /**
     * The consent produced a grant.
     *
     * What the grant is depends on the platform: an authorization code the device
     * redeems, or a one-time code the server redeems. Either way the ViewModel does
     * not care — it hands it over and stores what comes back.
     */
    data class GoogleAuthorizationCompleted(val grant: GoogleGrant) : CalendarSyncIntent

    /**
     * The consent did not produce a grant.
     *
     * The reason is the user's: "no Google account on this device" and "Play services
     * are out of date" are different problems with different fixes, and a screen that
     * says "sign-in failed" for both has thrown that away.
     */
    data class GoogleAuthorizationFailed(val reason: String) : CalendarSyncIntent

    /**
     * The client id the user is typing.
     *
     * The value is kept in the state and *not* written to the secure store on
     * every keystroke: the store is a keystore, and a paste followed by a
     * backspace is two writes of a value nobody asked to keep. [SaveGoogleClientId]
     * is what persists it.
     */
    data class SetGoogleClientId(val clientId: String) : CalendarSyncIntent

    /**
     * Persists the client id from the field.
     *
     * A blank value is refused rather than stored: a stored blank and a stored
     * client id look the same at the next read, and the failure would surface
     * as an OAuth error from Google several screens later.
     */
    data object SaveGoogleClientId : CalendarSyncIntent

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
// 19 functions: the screen drives two providers from one state holder — the system
// calendar and Google — and each of them needs its own connect/refresh/load path.
@Suppress("TooManyFunctions")
class CalendarSyncViewModel(
    private val syncRepo: CalendarSyncRepository,
    private val calendarProvider: CalendarProviderPort,
    private val scheduler: com.singularity.todo.feature.calendarSync.work.CalendarSyncWorkScheduler,
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
    /**
     * The window the Google pass actually uses, so the settings screen describes
     * that pass rather than a second copy of its default.
     *
     * Injected from DI, where the same binding feeds the engine and the event source.
     * A default argument would have kept this silent: the ViewModel would compile, the
     * screen would render a plausible window, and the two could disagree exactly the
     * way the three hard-coded `ImportWindow.DEFAULT` references did.
     */
    private val importWindow: ImportWindow,
    /**
     * The Google sign-in itself: consent, token exchange, and the write of the
     * credential.
     *
     * Injected rather than constructed so the screen can be tested against a
     * sign-in that succeeds, is refused, or fails — three outcomes the user
     * sees differently, and only one of which used to be reachable at all.
     */
    private val googleAuthorization: GoogleAuthorizer,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<CalendarSyncUiState, CalendarSyncIntent, Nothing>(
        initialState = CalendarSyncUiState(
            importWindow = importWindow,
            // The panel needs four seams, and it is offered only when all four can
            // do their job here. Each port answers for itself, so the answer does not
            // depend on which one the screen happened to ask first: the provider that
            // talks to the calendar, the repository that stores the preference, the
            // queries that list the apps to write into, and the scheduler that runs
            // the sync afterwards.
            deviceSyncSupported = calendarProvider.isSupported &&
                syncRepo.isSupported &&
                appQueries.isSupported &&
                scheduler.isSupported,
        ),
        crashReporter = crashReporter,
        scope = scope,
    ) {
    // MviViewModel handles addCloseable(scope) — no manual call needed

    /**
     * The sign-in that is under way, and the secret half of its PKCE pair.
     *
     * Kept here rather than in the state: the verifier must not travel to the
     * screen, and the state is what the screen reads. It is dropped as soon as the
     * grant comes back or the consent fails. A verifier that outlives its request is
     * a verifier that can be replayed.
     */
    private var pendingAuthorization: PendingGoogleAuthorization? = null

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
        // The client id is read once, on open: it is a configuration, not
        // something that changes under the screen, and the screen needs it to
        // say which Google project the sync writes into.
        vmScope.launch {
            val clientId = googleAuthorization.configuredClientId()
            updateState {
                // The value came from the disk, not from the field: it is a seed,
                // and the field takes it as it stands. Nothing configured is
                // nothing to seed — the field stays empty until the person types.
                it.copy(
                    googleClientId = clientId?.let { id -> it.googleClientId.seeded(id) }
                        ?: it.googleClientId,
                    googleConfiguredClientId = clientId,
                )
            }
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
            CalendarSyncIntent.ConnectGoogle -> connectGoogle()
            is CalendarSyncIntent.GoogleAuthorizationCompleted -> completeGoogleAuthorization(intent.grant)
            is CalendarSyncIntent.GoogleAuthorizationFailed -> failGoogleAuthorization(intent.reason)
            is CalendarSyncIntent.SetGoogleClientId -> setGoogleClientId(intent.clientId)
            CalendarSyncIntent.SaveGoogleClientId -> saveGoogleClientId()
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
                availableApps = persistentListOf(),
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
                // The credential has to stop working, not only be forgotten here. The
                // refresh token lives on the server — or in the device's keystore on the
                // desktop — and it keeps working until somebody cancels it. The revoke
                // goes *first*: a device that has already cleared its own copy cannot
                // cancel what it handed out.
                val revoked = googleAuthorization.revoke(currentUser.current.value)
                credentialStore.clear(currentUser.current.value)
                googleSettings.setSelectedCalendarId(null)
                updateState {
                    it.copy(
                        googleConnected = false,
                        googleCanRenew = true,
                        googleCalendars = emptyList(),
                        selectedGoogleCalendarId = null,
                        googleError = null,
                        // The device is disconnected either way — the person asked for
                        // that, and the local credential is gone. What failed is the
                        // server's half, and the user is the only one who can cancel it.
                        googleDisconnectError = revoked.exceptionOrNull()?.let { error ->
                            "The account was disconnected on this device, but the grant was not " +
                                "revoked: ${error.message ?: error::class.simpleName}"
                        },
                    )
                }
                return@launch
            }
            updateState {
                it.copy(
                    googleConnected = true,
                    googleError = null,
                    googleDisconnectError = null,
                )
            }
            refreshGoogleConnection()
        }
    }

    /**
     * Prepares the consent step and hands it to the screen.
     *
     * The failure path is the one that matters here: a sign-in that fails leaves
     * the account disconnected and says why, because the alternative — a button
     * that does nothing — is what this feature looked like before the consent
     * step existed.
     */
    private fun connectGoogle() {
        vmScope.launch {
            updateState { it.copy(googleAuthorizing = true, googleSignInError = null) }
            googleAuthorization.beginAuthorization()
                .onSuccess { pending ->
                    pendingAuthorization = pending
                    updateState { it.copy(googleAuthorizationRequest = pending.request) }
                }
                .onFailure { error ->
                    Logger.w(error) { "The Google sign-in could not be prepared" }
                    updateState {
                        it.copy(
                            googleAuthorizing = false,
                            googleConnected = false,
                            googleSignInError = error.message ?: "The Google sign-in did not finish",
                        )
                    }
                }
        }
    }

    /**
     * The consent came back with a grant: redeem it and store the credential.
     *
     * A grant without a prepared request is a grant this ViewModel cannot redeem —
     * the secret half of the PKCE pair is gone, and the code would be redeemed
     * against the wrong client. It is reported rather than ignored: a silent
     * no-op here is exactly the failure this feature had.
     */
    private fun completeGoogleAuthorization(grant: GoogleGrant) {
        val pending = pendingAuthorization
        if (pending == null) {
            updateState {
                it.copy(
                    googleAuthorizing = false,
                    googleAuthorizationRequest = null,
                    googleSignInError = "The Google sign-in came back without a request to answer",
                )
            }
            return
        }
        vmScope.launch {
            googleAuthorization.redeem(pending, grant, currentUser.current.value)
                .onSuccess { credentials ->
                    pendingAuthorization = null
                    updateState {
                        it.copy(
                            googleAuthorizing = false,
                            googleAuthorizationRequest = null,
                            googleConnected = true,
                            googleCanRenew = credentials.canRenew,
                            googleError = null,
                            googleSignInError = null,
                        )
                    }
                    refreshGoogleConnection()
                }
                .onFailure { error ->
                    Logger.w(error) { "The Google grant could not be redeemed" }
                    pendingAuthorization = null
                    updateState {
                        it.copy(
                            googleAuthorizing = false,
                            googleAuthorizationRequest = null,
                            googleConnected = false,
                            googleSignInError = error.message ?: "The Google sign-in did not finish",
                        )
                    }
                }
        }
    }

    /**
     * The consent failed. The account stays disconnected and the reason is shown.
     */
    private fun failGoogleAuthorization(reason: String) {
        pendingAuthorization = null
        updateState {
            it.copy(
                googleAuthorizing = false,
                googleAuthorizationRequest = null,
                googleConnected = false,
                googleSignInError = reason,
            )
        }
    }

    /**
     * Keeps the client id the user is typing.
     *
     * Nothing is written here — the value lives in the state until
     * [saveGoogleClientId] persists it. The field is the editor, the state is
     * its draft, and the keystore is written once, on the button.
     */
    private fun setGoogleClientId(clientId: String) {
        updateState {
            // The field's own text came back: the field already shows it, and the
            // bridge's echo guard is there for exactly this.
            it.copy(googleClientId = it.googleClientId.echoed(clientId), googleError = null)
        }
    }

    /**
     * Writes the client id from the field into the secure store.
     *
     * A blank value is refused here rather than in the resolver, so the screen
     * can say why nothing happened instead of watching the field clear itself.
     */
    private fun saveGoogleClientId() {
        vmScope.launch {
            val trimmed = currentState.googleClientId.text.trim()
            if (trimmed.isEmpty()) {
                updateState { it.copy(googleError = "A client id cannot be empty") }
                return@launch
            }
            runCatchingCancellable { googleAuthorization.storeClientId(trimmed) }
                .onSuccess {
                    updateState {
                        it.copy(
                            // The user's own text, trimmed: the field's text, and
                            // the field's echo.
                            googleClientId = it.googleClientId.echoed(trimmed),
                            googleConfiguredClientId = trimmed,
                            googleError = null,
                        )
                    }
                }.onFailure { error ->
                    Logger.w(error) { "The Google client id could not be saved" }
                    updateState {
                        it.copy(googleError = error.message ?: "The client id could not be saved")
                    }
                }
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
            if (_state.value.isLoading) return@launch
            updateState { it.copy(isLoading = true) }

            // Load calendar apps and calendars in parallel
            val apps = if (appQueries.isSupported) {
                try {
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
            } else {
                // The port says it cannot do its job here. Asking anyway would answer
                // "you have no calendar apps" — an artifact of the platform, not a fact
                // about the user.
                emptyList()
            }
            val calendarsResult = calendarProvider.getAvailableCalendars()

            calendarsResult
                .onSuccess { calendars ->
                    updateState {
                        it.copy(
                            availableCalendars = calendars,
                            availableApps = apps.toImmutableList(),
                            isLoading = false,
                        )
                    }
                }
                .onFailure {
                    // Nothing is set to "unsupported" here, and deliberately: the platform
                    // gate is the conjunction of the seams' own `isSupported` flags,
                    // which the screen reads and which know the answer without making a
                    // call. Deciding it from a call result means a transient failure —
                    // a revoked permission, a sick content provider — reads as "this
                    // device cannot", and the one panel that would have told the user
                    // apart is the one that goes dark.
                    //
                    // A failed read is not a capability verdict, and `hasPermissions`
                    // already covers the permission case with a dialog to fix it.
                    updateState {
                        it.copy(
                            availableApps = apps.toImmutableList(),
                            isLoading = false,
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
