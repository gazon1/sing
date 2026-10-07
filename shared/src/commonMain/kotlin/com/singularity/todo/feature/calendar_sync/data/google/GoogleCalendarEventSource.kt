package com.singularity.todo.feature.calendar_sync.data.google

import com.singularity.todo.core.network.createHttpClient
import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentialStore
import com.singularity.todo.feature.calendar_sync.domain.model.ChangePage
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleCalendarSummary
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.ImportWindow
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarEventSource
import com.singularity.todo.feature.calendar_sync.error.CalendarSyncException
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/**
 * Talks to the Google Calendar v3 REST API.
 *
 * ## Where the boundaries are
 *
 * Google-shaped concerns stop here. The DTO parsing, the status-code mapping and the
 * query-parameter rules live in this file; the merge, the shadow and the cursor are the
 * caller's. A test drives this class against a canned response body, which is why nothing
 * here reads a clock or a database — the two things that would make it untestable are
 * injected.
 *
 * ## Why errors are mapped rather than thrown
 *
 * Several Google status codes are *expected* outcomes with a correct response, and treating
 * them as failures would turn ordinary paths into exceptions:
 *
 * - **410** — the incremental token expired. The correct action is a full resync, and the
 *   caller needs to know that specifically, not "something went wrong".
 * - **412** — the etag is stale, so someone else wrote first. Re-read and re-plan.
 * - **403 with `rateLimitExceeded`** — back off and retry, honouring `Retry-After`.
 *
 * Each maps to a specific [CalendarSyncException] subtype, so the retry policy is a property
 * of the type rather than of the string that came back.
 */
class GoogleCalendarEventSource(
    private val httpClient: HttpClient = createHttpClient(),
    private val credentials: GoogleCredentialStore,
    private val userId: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /**
     * The window a *full* listing reads, used as the `timeMin`/`timeMax` range.
     *
     * Incremental listings never send this: a sync token already bounds the window, and
     * Google answers `400` if `timeMin`/`timeMax` accompany one. That asymmetry is why this
     * is a separate parameter rather than a field on the request.
     *
     * No default, for the same reason as the engine's: this and the engine and the
     * settings screen must not each hold a copy of the choice. Bound once in
     * `calendarSyncModule()`.
     */
    private val importWindow: ImportWindow,
    /**
     * Injected with no default.
     *
     * A default of `Clock.System` would make every test that forgot to pass one silently
     * depend on the day it runs, which is the class of defect the ban exists to stop: the
     * window's `timeMin` would move with the clock and a test asserting a fixed range would
     * pass today and fail next month. Forcing the argument makes "now" an input.
     */
    private val clock: Clock,
) : CalendarEventSource {

    override suspend fun listCalendars(): List<GoogleCalendarSummary> {
        val response = getJson("$BASE/users/me/calendarList?minAccessRole=writer")
        val body = response.requireSuccess()
        return body.asJsonArray().mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj.stringOrNull("id") ?: return@mapNotNull null
            GoogleCalendarSummary(
                id = id,
                summary = obj.stringOrNull("summary") ?: "(no name)",
                isPrimary = obj.booleanOrNull("primary") == true,
                canWrite = obj.stringOrNull("accessRole") in WRITER_ROLES,
            )
        }
    }

    override suspend fun fetchChanges(calendarId: String, syncToken: String?): ChangePage {
        // The query-parameter lock is the reason this is not simply "list with filters":
        // once a syncToken is in play Google rejects timeMin/timeMax/q/orderBy/updatedMin
        // with a 400, and singleEvents must stay the same or the token is invalidated. So
        // the token call sends *only* the token plus the flags, and a full listing sends a
        // different parameter set entirely.
        val url = if (syncToken == null) {
            buildString {
                append("$BASE/calendars/$calendarId/events")
                append("?singleEvents=true&showDeleted=true&orderBy=startTime")
                append("&maxResults=$PAGE_SIZE")
                // Both ends, from one window value. A full listing with no lower bound would
                // return a Google account's entire history, and the user would have to undo
                // the import by hand.
                append("&timeMin=${importWindow.startInclusive(clock.now())}")
                importWindow.endExclusive(clock.now())?.let { append("&timeMax=$it") }
            }
        } else {
            "$BASE/calendars/$calendarId/events" +
                "?singleEvents=true&showDeleted=true&maxResults=$PAGE_SIZE&syncToken=$syncToken"
        }

        val response = getJson(url)
        if (response.status == HttpStatusCode.Gone) {
            // Normal branch, not a failure: the stored token is dead and the caller must
            // fall back to a full listing. Reported rather than thrown so the caller can
            // decide, since re-listing a large calendar is expensive.
            return ChangePage(events = emptyList(), requiresFullResync = true)
        }
        val obj = response.requireSuccess().asJsonObject()

        val events = (obj["items"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { element ->
                // Items arrive as JsonElement, and the mapper is a JsonObject extension, so
                // the cast is explicit rather than assumed.
                (element as? JsonObject)?.toGoogleEventOrNull()
            }
            .orEmpty()
        val nextPage = obj.stringOrNull("nextPageToken")
        // nextSyncToken arrives only with the final page. Handing it back earlier would
        // make the caller persist a cursor for a window it has not finished reading, and
        // the unread pages' changes would be skipped permanently.
        val nextSync = if (nextPage == null) obj.stringOrNull("nextSyncToken") else null

        return ChangePage(events = events, nextPageToken = nextPage, nextSyncToken = nextSync)
    }

    override suspend fun insert(calendarId: String, event: GoogleEvent): GoogleEvent {
        // `httpClient.post`, not a bare `post`: this class has a member called `patch`, and
        // a bare `patch(...)` would resolve to it rather than to Ktor's verb.
        val token = requireToken()
        val response = httpClient.post("$BASE/calendars/$calendarId/events") {
            authorize(token)
            contentType(ContentType.Application.Json)
            setBody(event.toRequestJson())
        }
        return response.requireSuccess().asJsonObject().toGoogleEvent()
    }

    override suspend fun patch(
        calendarId: String,
        eventId: GoogleEventId,
        etag: String?,
        event: GoogleEvent,
    ): GoogleEvent {
        val token = requireToken()
        val response = httpClient.patch("$BASE/calendars/$calendarId/events/${eventId.value}") {
            authorize(token)
            contentType(ContentType.Application.Json)
            // If-Match is what turns a lost race into a 412 instead of a silent overwrite of
            // an edit the app never saw. Without it, two devices writing the same event in
            // the same second would produce a merge that never happened.
            if (etag != null) header(HttpHeaders.IfMatch, etag)
            setBody(event.toRequestJson())
        }
        return response.requireSuccess().asJsonObject().toGoogleEvent()
    }

    override suspend fun get(calendarId: String, eventId: GoogleEventId): GoogleEvent =
        getJson("$BASE/calendars/$calendarId/events/${eventId.value}")
            .requireSuccess().asJsonObject().toGoogleEvent()

    override suspend fun cancel(calendarId: String, eventId: GoogleEventId, etag: String?) {
        val token = requireToken()
        val response = httpClient.delete("$BASE/calendars/$calendarId/events/${eventId.value}") {
            authorize(token)
            if (etag != null) header(HttpHeaders.IfMatch, etag)
        }
        if (response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.OK) return
        response.requireSuccess()
    }

    // ─── HTTP ───────────────────────────────────────────────────────────

    private suspend fun getJson(url: String): HttpResponse {
        val token = requireToken()
        return httpClient.get(url) { authorize(token) }
    }

    private suspend fun HttpResponse.requireSuccess(): String {
        val text = bodyAsText()
        if (status.value in 200..299) return text
        throw translate(status, text)
    }

    /**
     * Sets the bearer header.
     *
     * Takes the token as a parameter rather than suspending for it: this runs inside a
     * Ktor request builder, which is not a suspending context. Callers resolve the token
     * first, which also means a missing credential fails before a request is ever built.
     */
    private fun io.ktor.client.request.HttpRequestBuilder.authorize(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun requireToken(): String =
        credentials.load(userId)?.accessToken
            ?: throw CalendarSyncException.PermissionRevokedException(
                "No Google credential for this profile — the user has to connect again",
            )

    /**
     * Maps a Google failure onto the project's own error hierarchy.
     *
     * The classification is the point: a caller must be able to ask "retry?" without
     * parsing a status code, because getting that wrong either gives up on a recoverable
     * condition or hammers a rate limit.
     */
    private fun translate(status: HttpStatusCode, body: String): CalendarSyncException {
        val reason = runCatching {
            json.parseToJsonElement(body).jsonObject
                .let { it["error"]?.jsonObject?.stringOrNull("message") }
        }.getOrNull()

        return when (status) {
            HttpStatusCode.Unauthorized ->
                CalendarSyncException.PermissionRevokedException(
                    "Google rejected the access token" + (reason?.let { ": $it" } ?: ""),
                )

            HttpStatusCode.Forbidden ->
                if (body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded")) {
                    CalendarSyncException.TransientSyncException("Google rate limit reached", null)
                } else {
                    CalendarSyncException.PermissionRevokedException(
                        "Google refused the request" + (reason?.let { ": $it" } ?: ""),
                    )
                }

            HttpStatusCode.NotFound ->
                CalendarSyncException.CalendarNotFoundException(status.description)

            // 412 is the etag guard doing its job. Transient by design: the caller's
            // response is to re-read the event and re-plan, which converges.
            HttpStatusCode.PreconditionFailed ->
                CalendarSyncException.TransientSyncException("The event changed on another device", null)

            HttpStatusCode.Gone ->
                CalendarSyncException.TransientSyncException("The sync token expired", null)

            HttpStatusCode.Conflict ->
                CalendarSyncException.TransientSyncException("Conflicting event version", null)

            else ->
                if (status.value >= 500) {
                    CalendarSyncException.TransientSyncException(
                        "Google returned ${status.value}" + (reason?.let { ": $it" } ?: ""),
                        null,
                    )
                } else {
                    CalendarSyncException.NetworkSyncException(
                        "Google returned ${status.value}" + (reason?.let { ": $it" } ?: ""),
                        null,
                    )
                }
        }
    }

    private companion object {
        const val BASE = "https://www.googleapis.com/calendar/v3"
        const val PAGE_SIZE = 250

        val WRITER_ROLES = setOf("writer", "owner")
    }
}
