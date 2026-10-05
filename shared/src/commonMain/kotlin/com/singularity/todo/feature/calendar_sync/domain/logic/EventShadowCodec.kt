package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/**
 * Persists an [EventShadow] to a single string column.
 *
 * ## Why the shadow is stored as JSON and not as columns
 *
 * The shadow is a value only the merge reads, and it will gain fields as the mapped set
 * grows. One JSON column means a new field does not need a migration, a DAO change, or an
 * `AutoMigrationSpec` — the merge already treats each field independently, so a new one is
 * additive there. The alternative (one nullable column per field) would put a schema
 * migration in front of every future field the merge learns to reason about.
 *
 * ## Why round-tripping is exact, including "absent"
 *
 * The merge distinguishes a field that was never recorded from one recorded as absent, so a
 * lossy format would silently turn "unknown" into "deleted" and produce conflicts — or,
 * worse, pushes — the user never asked for. Every field is therefore written explicitly as
 * a JSON key, using [JsonNull] rather than omission, so `null` and "key not present" stay
 * distinguishable on the way back in.
 */
object EventShadowCodec {

    private const val KEY_TITLE = "title"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_STARTS_AT = "startsAt"
    private const val KEY_ENDS_AT = "endsAt"
    private const val KEY_ALL_DAY = "allDay"
    private const val KEY_LOCATION = "location"
    private const val KEY_RECURRENCE = "recurrenceRule"

    /** Encodes [shadow] for storage. [decodeOrEmpty] of the result equals the input. */
    fun encode(shadow: EventShadow): String = buildJsonObject {
        put(KEY_TITLE, shadow.title.toJsonElement())
        put(KEY_DESCRIPTION, shadow.description.toJsonElement())
        put(KEY_STARTS_AT, shadow.startsAt?.toEpochMilliseconds().toJsonElement())
        put(KEY_ENDS_AT, shadow.endsAt?.toEpochMilliseconds().toJsonElement())
        put(KEY_ALL_DAY, shadow.allDay.toJsonElement())
        put(KEY_LOCATION, shadow.location.toJsonElement())
        put(KEY_RECURRENCE, shadow.recurrenceRule.toJsonElement())
    }.toString()

    /**
     * Decodes a stored shadow, or returns [EventShadow.emptyShadow] when it cannot be read.
     *
     * An unreadable shadow deliberately becomes *unknown* rather than *empty-but-known*.
     * The merge treats the two differently: unknown refuses to push, whereas an
     * all-fields-absent-but-known shadow would read as "Google has no description and we
     * have no description" and could conclude the user deleted it.
     */
    fun decodeOrEmpty(json: String?): EventShadow {
        if (json.isNullOrBlank()) return EventShadow.emptyShadow()
        val obj = runCatching { StableJson.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return EventShadow.emptyShadow()
        return EventShadow(
            title = obj.readString(KEY_TITLE),
            description = obj.readString(KEY_DESCRIPTION),
            startsAt = obj.readLong(KEY_STARTS_AT)?.let(Instant::fromEpochMilliseconds),
            endsAt = obj.readLong(KEY_ENDS_AT)?.let(Instant::fromEpochMilliseconds),
            allDay = obj.readBoolean(KEY_ALL_DAY),
            location = obj.readString(KEY_LOCATION),
            recurrenceRule = obj.readString(KEY_RECURRENCE),
        )
    }

    /** The shadow to store after writing [event] to the provider. */
    fun shadowOf(event: GoogleEvent): EventShadow = event.toShadow()

    private fun String?.toJsonElement(): JsonPrimitive =
        this?.let(::JsonPrimitive) ?: JsonNull

    private fun Long?.toJsonElement(): JsonPrimitive =
        this?.let(::JsonPrimitive) ?: JsonNull

    private fun Boolean?.toJsonElement(): JsonPrimitive =
        this?.let(::JsonPrimitive) ?: JsonNull

    private fun JsonObject.primitiveOrNull(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.readString(key: String): String? = primitiveOrNull(key)?.content

    private fun JsonObject.readLong(key: String): Long? = primitiveOrNull(key)?.longOrNull

    private fun JsonObject.readBoolean(key: String): Boolean? = primitiveOrNull(key)?.booleanOrNull
}
