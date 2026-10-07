package com.singularity.todo.test.helpers

import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentialStore
import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentials
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleCalendarSummary
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.ImportWindow
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncEngine
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncPass
import com.singularity.todo.test.fakes.TestUsers
import org.koin.core.module.Module
import org.koin.dsl.module
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarEventSource
import com.singularity.todo.feature.calendar_sync.domain.port.GoogleCalendarSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory fakes for the four Google seams the Settings → Calendar screen reads.
 *
 * ## Why these exist rather than a `TestPlatformModule` default
 *
 * The screen's behaviour is entirely about what it *shows for a given account
 * state*, and that state comes from four ports. With production bindings (a
 * keystore, a DataStore, an HTTPS client) every arrangement is either
 * unreachable or would attempt a network call from a JVM test. So a carrier for
 * `CAL-SYNC-*` could not exist at all.
 *
 * The alternatives were both worse: mocking the ViewModel would have tested the
 * ViewModel's own mock, and a Maestro-only carrier would have left the whole
 * feature's user-visible behaviour with no desktop verification — which is how
 * this feature ended up with 23 unit-test classes and zero carriers in the first
 * place.
 *
 * ## What each fake deliberately *does not* fake
 *
 * [GoogleCredentials.canRenew] is derived from the real `GoogleCredentials`
 * rather than a flag, because the screen's most important warning ("this
 * connection cannot be renewed") is a rendering of exactly that property. A fake
 * that returned a boolean would let the test pass while the production
 * derivation was wrong.
 */

/**
 * Credential store backed by a map.
 *
 * @param initial Credentials present before the test touches the screen. Empty
 *   means "not connected", which is the state a fresh profile is in. Build an entry
 *   with [connectedCredential] rather than by hand — [GoogleCredentials.canRenew] is
 *   derived from `refreshToken`, so that is the only thing the screen's renewal
 *   warning reads.
 */
class FakeGoogleCredentialStore(
    initial: Map<String, GoogleCredentials> = emptyMap(),
) : GoogleCredentialStore {
    private val byUser = initial.toMutableMap()

    /** Seeds or replaces the grant so a test can start from "connected". */
    fun connect(userId: String, canRenew: Boolean = true) {
        byUser[userId] = connectedCredential(canRenew)
    }

    override suspend fun load(userId: String): GoogleCredentials? = byUser[userId]

    override suspend fun save(userId: String, credentials: GoogleCredentials) {
        byUser[userId] = credentials
    }

    override suspend fun clear(userId: String) {
        byUser.remove(userId)
    }
}

/**
 * The user's Google *choices*, in memory.
 *
 * Real implementation is DataStore-backed, which is exercised by the shared
 * suite. What the screen needs from this port is the observable pair, and the
 * persistence-across-reopen half of `CAL-SYNC-CONNECT-01` is deliberately *not*
 * asserted here — see the carrier's KDoc, which records that as the gap this
 * fake leaves rather than papering over it.
 */
class FakeGoogleCalendarSettingsRepository(
    selectedCalendarId: String? = null,
    importForeignEvents: Boolean = false,
) : GoogleCalendarSettingsRepository {
    private val selected = MutableStateFlow(selectedCalendarId)
    private val import = MutableStateFlow(importForeignEvents)

    override fun observeSelectedCalendarId(): Flow<String?> = selected.asStateFlow()

    override suspend fun setSelectedCalendarId(calendarId: String?) {
        selected.value = calendarId
    }

    override fun observeImportForeignEvents(): Flow<Boolean> = import.asStateFlow()

    override suspend fun setImportForeignEvents(enabled: Boolean) {
        import.value = enabled
    }
}

/**
 * Calendar source that returns a fixed listing, or throws.
 *
 * @param calendars What [listCalendars] returns. Empty models an account with no
 *   calendars, which the screen renders differently from an error — the whole
 *   point of keeping [GoogleCalendarEventSource.failing] separate.
 * @param failing When true, [listCalendars] throws. This is the path that
 *   produces `googleError`, and it must never render as "no calendars found":
 *   collapsing the two is exactly the defect `GOOGLE_LIST_ERROR` exists to catch.
 */
class FakeCalendarEventSource(
    private val calendars: List<GoogleCalendarSummary> = DEFAULT_CALENDARS,
    val failing: Boolean = false,
) : CalendarEventSource {
    override suspend fun listCalendars(): List<GoogleCalendarSummary> {
        if (failing) throw IllegalStateException("Google Calendar is unreachable (test)")
        return calendars
    }

    override suspend fun fetchChanges(
        calendarId: String,
        syncToken: String?,
    ) = error("Not needed by a settings-screen carrier: a carrier never reads changes.")

    override suspend fun insert(calendarId: String, event: GoogleEvent) =
        error("Not needed by a settings-screen carrier: a carrier never writes an event.")

    override suspend fun patch(
        calendarId: String,
        eventId: GoogleEventId,
        etag: String?,
        event: GoogleEvent,
    ) = error("Not needed by a settings-screen carrier: a carrier never writes an event.")

    override suspend fun get(calendarId: String, eventId: GoogleEventId) =
        error("Not needed by a settings-screen carrier.")

    override suspend fun cancel(calendarId: String, eventId: GoogleEventId, etag: String?) =
        error("Not needed by a settings-screen carrier.")

    companion object {
        /**
         * Two writable calendars, one primary and one not.
         *
         * Two rather than one so a test can tell "the row I clicked is selected"
         * from "the only row is selected" — with a single row a selector that
         * silently matched the wrong element would still pass.
         */
        val DEFAULT_CALENDARS = listOf(
            GoogleCalendarSummary(id = "primary-cal", summary = "Max Mustermann", isPrimary = true),
            GoogleCalendarSummary(id = "work-cal", summary = "Work"),
        )
    }
}
/**
 * A renewable credential for the profile `TestPlatformModule` signs in as.
 *
 * `TestUsers.DEFAULT` rather than a literal, because the credential store is keyed
 * by user id and a hand-typed string would silently miss the key: the store would
 * report "not connected", and the screen would render the *disconnected* branch
 * while the test asserted on the connected one. A test that fails for that reason
 * looks like a UI bug, which is the expensive way to learn where the id came from.
 */
fun connectedCredential(canRenew: Boolean = true): GoogleCredentials = GoogleCredentials(
    accessToken = "test-access-token",
    refreshToken = if (canRenew) "test-refresh-token" else null,
    expiresAtEpochMs = Long.MAX_VALUE,
)

/**
 * The Koin overrides a `CAL-SYNC-*` carrier needs, as one bundle.
 *
 * ## Why this is a function and not four `single` lines per test
 *
 * The four seams have to agree with each other for the panel to render a coherent
 * state, and a carrier that got one of them wrong does not fail — it renders the
 * *other* branch and then fails on an assertion about a tag that is legitimately
 * absent. Collecting them here means a carrier states the account situation it
 * wants ("connected, no calendar chosen") instead of four unrelated bindings.
 *
 * ## What it overrides, and what it deliberately leaves alone
 *
 * Only the Google seams plus [importWindow]. The system-calendar half is already
 * inert on desktop via production classes (`NoopCalendarProvider`,
 * `NoopCalendarSyncRepositoryImpl`, `NoopCalendarSyncWorkScheduler`), so faking
 * them would add a second source of truth for a branch the JVM cannot exercise
 * anyway.
 *
 * The pass is faked through [GoogleSyncPass] — the coordinator's own seam — rather
 * than by replacing the coordinator. The coordinator is a concrete class holding
 * real logic that this scenario is about: that a failure reaches the user as a
 * failure, and that declining is a different answer. Replacing it would delete the
 * thing under test.
 *
 * @param passFailure When non-null, every pass throws with this reason. Null runs a
 *   pass that completes and reports success.
 */
fun googleCalendarModule(
    credentials: GoogleCredentialStore,
    settings: GoogleCalendarSettingsRepository,
    eventSource: CalendarEventSource,
    importWindow: ImportWindow = ImportWindow.DEFAULT,
    passFailure: String? = null,
): Module = module {
    single<GoogleCredentialStore> { credentials }
    single<GoogleCalendarSettingsRepository> { settings }
    // The ViewModel takes `eventSource` as a *factory*, so it must be re-bound as a
    // factory too — binding a single instance here and capturing it would defeat the
    // per-profile resolution the parameter exists for.
    factory<CalendarEventSource> { eventSource }

    // The **coordinator**, not the pass. This was the first version's mistake and it is
    // worth recording: binding `GoogleSyncPass` alone left the production
    // `factory { GoogleSyncCoordinator(engineProvider = { get<GoogleSyncEngine>() }, …) }`
    // in place, so the ViewModel still built a real engine out of four DAOs and a
    // network client. The button then sat on "Syncing…" forever and the test failed on a
    // *missing failure message* — the symptom pointed at the presentation layer while the
    // cause was three layers down in a binding nobody had replaced.
    //
    // `factory`, matching production: the coordinator is resolved per cycle so a profile
    // switch is picked up by the next pass. The engine seam is faked from the inside,
    // which keeps the coordinator's own logic — which is what this scenario is about:
    // that a throwing pass becomes `Outcome.Failed` rather than a silent decline.
    factory<GoogleSyncCoordinator> {
        GoogleSyncCoordinator(
            engineProvider = { GoogleSyncPass { passOutcome(passFailure) } },
            googleSettings = settings,
            credentialStore = credentials,
            currentUser = TestUsers.DEFAULT,
            clock = get(),
        )
    }
    // Bound last so it beats the production `ImportWindow` in `calendarSyncModule()`.
    single { importWindow }
}

private suspend fun passOutcome(failure: String?): GoogleSyncEngine.PassResult {
    if (failure != null) throw IllegalStateException(failure)
    return GoogleSyncEngine.PassResult(seen = 0, pushed = 0, tasksCreated = 0, tasksUpdated = 0)
}
