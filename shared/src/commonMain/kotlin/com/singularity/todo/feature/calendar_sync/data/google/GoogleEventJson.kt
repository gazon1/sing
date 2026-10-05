package com.singularity.todo.feature.calendar_sync.data.google

import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventId
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEventStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

/*
 * Translation between Google's event JSON and [GoogleEvent].
 *
 * A block comment rather than a KDoc: it describes the file rather than the declaration
 * below it, and a `/** */` here would attach itself to `stringOrNull` and quietly promise
 * that a two-character accessor translates Google's payload.
 *
 * Kept apart from the HTTP client so it can be tested against literal JSON with no
 * transport, and so a change to Google's payload shape is confined to one file.
 *
 * ## Two things that are easy to get wrong here
 *
 * **`status` is not `deleted`.** A cancelled event still arrives in an incremental listing
 * with `showDeleted=true`, and it is still a record. Reading "cancelled" as "gone" makes a
 * sync drop the mapping on a transient cancellation and then re-create the event.
 *
 * **`recurrence` is opaque.** The app has no RFC 5545 implementation, so the string is
 * carried through untouched in both directions. Reformatting it here would silently change
 * how many occurrences a series has.
 */

/** Reads a string field, or null for both "absent" and an explicit JSON null. */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toBooleanStrictOrNull()

internal fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toLongOrNull()

private fun JsonObject.objOrNull(key: String): JsonObject? =
    (this[key] as? JsonObject)

/** Parses a body that is expected to be a JSON object, or fails with the raw text. */
internal fun String.asJsonObject(): JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(this).jsonObject

/** Parses a body that is expected to be a JSON array. */
internal fun String.asJsonArray(): JsonArray =
    kotlinx.serialization.json.Json.parseToJsonElement(this) as? JsonArray ?: JsonArray(emptyList())

/**
 * Google timestamps: RFC 3339. A date-only value is an all-day event, which is why
 * [toGoogleEventOrNull] reads the trailing `Z`-less shape rather than assuming a time.
 */
private fun JsonObject.parseInstant(key: String): Instant? {
    val raw = stringOrNull(key) ?: return null
    return runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { Instant.parse("${raw}Z") }.getOrNull()
}

/**
 * Maps one item from an `events.list` response.
 *
 * Returns null for an item with no id, which is the one thing that makes it unusable: every
 * later call addresses an event by id, so an event without one could not be updated,
 * cancelled, or correlated with a task.
 */
internal fun JsonObject.toGoogleEventOrNull(): GoogleEvent? {
    val id = stringOrNull("id") ?: return null
    val start = objOrNull("start")
    val end = objOrNull("end")

    // `date` (all-day) and `dateTime` (timed) are mutually exclusive on one object. The
    // presence of `date` is what marks an all-day event, and reading only `dateTime` would
    // silently drop every all-day event the user has.
    val allDay = start?.containsKey("date") == true
    val startsAt = start?.let {
        it.stringOrNull("dateTime")?.let(::parseInstantText) ?: it.stringOrNull("date")?.let(::parseDateText)
    }
    val endsAt = end?.let {
        it.stringOrNull("dateTime")?.let(::parseInstantText) ?: it.stringOrNull("date")?.let(::parseDateText)
    }

    return GoogleEvent(
        id = GoogleEventId(id),
        calendarId = stringOrNull("calendarId") ?: "",
        title = stringOrNull("summary"),
        description = stringOrNull("description"),
        startsAt = startsAt,
        endsAt = endsAt,
        allDay = allDay,
        location = stringOrNull("location"),
        // Verbatim. See the file comment.
        recurrenceRule = readRecurrenceRule(),
        status = when (stringOrNull("status")) {
            "cancelled" -> GoogleEventStatus.Cancelled
            "tentative" -> GoogleEventStatus.Tentative
            else -> GoogleEventStatus.Confirmed
        },
        etag = stringOrNull("etag"),
        updatedAt = parseInstant("updated"),
        taskId = objOrNull("extendedProperties")
            ?.objOrNull("private")
            ?.stringOrNull(TASK_ID_PROPERTY),
    )
}

/** Maps a single-event response. Throws-free: an event with no id is not representable. */
internal fun JsonObject.toGoogleEvent(): GoogleEvent = toGoogleEventOrNull()
    ?: throw IllegalStateException("Google returned an event with no id; it cannot be addressed")

private fun parseInstantText(text: String): Instant? =
    runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { Instant.parse("${text}Z") }.getOrNull()

/** An all-day `date` is a bare `yyyy-MM-dd`; read it as the start of that day in UTC. */
private fun parseDateText(text: String): Instant? = runCatching {
    Instant.parse("${text}T00:00:00Z")
}.getOrNull()

/**
 * Reads the repeat rule out of Google's `recurrence` field.
 *
 * That field is a **list of strings**, not an object: `["RRULE:FREQ=WEEKLY;BYDAY=MO"]`.
 * Reading it as an object is how the rule silently came back null on the first run — and a
 * null rule is read as "this event does not repeat", so a weekly series would be flattened
 * into a single occurrence with no error anywhere.
 *
 * Only the `RRULE:` line is taken. `EXDATE:` and `RDATE:` lines exist too, and the app has
 * no way to express them, so they are not reinterpreted — see the file comment on why
 * Google keeps ownership of recurrence.
 */
internal fun JsonObject.readRecurrenceRule(): String? {
    (this["recurrence"] as? JsonArray)?.forEach { line ->
        val text = (line as? JsonPrimitive)?.content ?: return@forEach
        if (text.startsWith(RRULE_PREFIX)) return text.removePrefix(RRULE_PREFIX)
    }
    // Fall back for a payload that already holds a bare rule, which is what our own request
    // body looks like before Google has normalised it.
    return stringOrNull("recurrenceRule")
}

/** Google's `recurrence` entries are prefixed; the app stores the rule without it. */
internal const val RRULE_PREFIX = "RRULE:"

/** The private extended property carrying our task id. */
internal const val TASK_ID_PROPERTY = "singularityTaskId"

/** The deep link left in the description, for a user tapping the event in Google. */
internal const val DEEP_LINK_PREFIX = "singularity://task/"

/**
 * The request body for an insert or a patch.
 *
 * Only the fields this app owns are sent. A patch is partial by design: a whole-event write
 * would clear any field the app has no model for — a colour, a conference link, a guest
 * list — and Google charges by the fields sent.
 */
internal fun GoogleEvent.toRequestJson(): String = buildJsonObject {
    // Every owned field is written once, as an explicit null when cleared. Omitting a
    // cleared field would leave the old value on Google's side, so a user deleting a
    // description in the app would watch it reappear on the next sync.
    // Read from the event's own properties — `stringOrNull` is a JsonObject extension and
    // has nothing to say about a GoogleEvent.
    put("summary", title?.let(::JsonPrimitive) ?: JsonNull)
    put("description", description?.let(::JsonPrimitive) ?: JsonNull)
    put("location", location?.let(::JsonPrimitive) ?: JsonNull)

    startsAt?.let { start ->
        endsAt?.let { end ->
            put(
                "start",
                buildJsonObject {
                    if (allDay == true) {
                        put("date", JsonPrimitive(start.toDateText()))
                    } else {
                        put("dateTime", JsonPrimitive(start.toIsoText()))
                    }
                },
            )
            put(
                "end",
                buildJsonObject {
                    if (allDay == true) {
                        put("date", JsonPrimitive(end.toDateText()))
                    } else {
                        put("dateTime", JsonPrimitive(end.toIsoText()))
                    }
                },
            )
        }
    }

    recurrenceRule?.let { rule ->
        // A list of strings, with the `RRULE:` prefix — the same shape `readRecurrenceRule`
        // reads back. Writing an object here instead would be silently ignored by Google,
        // and a series would quietly stop repeating.
        put(
            "recurrence",
            buildJsonArray {
                add(JsonPrimitive(RRULE_PREFIX + rule))
            },
        )
    }

    taskId?.let { id ->
        put(
            "extendedProperties",
            buildJsonObject {
                put("private", buildJsonObject { put(TASK_ID_PROPERTY, JsonPrimitive(id)) })
            },
        )
    }
}.toString()

/** `yyyy-MM-dd` for an all-day boundary, in UTC. */
private fun Instant.toDateText(): String = toString().substring(0, 10)

/** RFC 3339 with a `Z`, which is what Google stores. */
private fun Instant.toIsoText(): String = toString()
