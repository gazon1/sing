package com.singularity.todo.core.sync

import com.singularity.todo.core.error.AppError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

// ── Building ─────────────────────────────────────────────────────────────────

/**
 * The wire vocabulary of the sync protocol, in one place.
 *
 * ## Why the names live apart from the transport
 *
 * The client does two jobs — calling a function, and agreeing with the server
 * about what the bytes mean. They have different reasons to change: the first
 * when the SDK does, the second when the schema does. Keeping the names here
 * means a schema change is a diff in this file, and "what does `updated` look
 * like on the wire" is answered by reading one file rather than by tracing a
 * `when` through a request builder.
 *
 * ## Why a malformed response is a refusal rather than a default
 *
 * Every reader here throws [AppError] instead of substituting a value, and the
 * defaults are the dangerous ones. A result with no `ok` is not `true` — that
 * would confirm a patch the server never took. A missing `profileId` is empty,
 * which is safe, because empty means "applies to whichever scope pulls it" and
 * an event that *names* a profile is never applied to a different one.
 *
 * `"p:c:n"` in the model, `{p, c, n}` on the wire.
 */
internal fun Hlc.toWire(): JsonObject = buildJsonObject {
    put("p", JsonPrimitive(physical))
    put("c", JsonPrimitive(counter))
    put("n", JsonPrimitive(node))
}

internal val FieldOp.wireName: String
    get() = when (this) {
        FieldOp.SET -> "set"
        FieldOp.UNSET -> "unset"
        FieldOp.APPEND -> "append"
        FieldOp.REMOVE -> "remove"
    }

/**
 * Whether the operation states a value, as opposed to clearing the field.
 *
 * `unset` and `remove` name a field to clear and carry none. Sending an explicit
 * `null` instead would state that the field's *value* is null, which is a
 * different claim about the row, and one the merge would take literally.
 */
internal val FieldOp.carriesValue: Boolean
    get() = this == FieldOp.SET || this == FieldOp.APPEND

internal val SyncEventType.wireName: String
    get() = when (this) {
        SyncEventType.CREATED -> "created"
        SyncEventType.UPDATED -> "updated"
        SyncEventType.DELETED -> "deleted"
        SyncEventType.RESTORED -> "restored"
    }

internal fun docTypeOrNull(key: String): DocType? = DocType.entries.firstOrNull { it.key == key }

internal fun eventTypeOrNull(key: String): SyncEventType? = SyncEventType.entries.firstOrNull { it.wireName == key }

// ── Reading ──────────────────────────────────────────────────────────────────

/**
 * Reads an event out of one log entry.
 *
 * An unknown document or event type is refused rather than skipped. Skipping
 * would drop the event and move the cursor past it, and the data would be gone
 * with only a pull summary to show for it. A genuine future schema is carried by
 * `protocolVersion` — a declared, versioned break — rather than by a type name
 * this build happens not to recognise.
 */
internal fun JsonObject.toSyncEvent(): SyncEvent {
    val entityId = requiredString("entityId", "an event")
    val typeKey = requiredString("entityType", "the event for $entityId")
    val docType = docTypeOrNull(typeKey) ?: throw AppError.Network(
        "The server sent an event of unknown type '$typeKey' for entity $entityId; " +
            "this build understands ${DocType.entries.joinToString { it.key }}.",
        code = "sync.unknown_entity_type",
    )
    val eventKey = requiredString("eventType", "the event for $entityId")
    val eventType = eventTypeOrNull(eventKey) ?: throw AppError.Network(
        "The server sent an event of unknown type '$eventKey' for entity $entityId.",
        code = "sync.unknown_event_type",
    )
    return SyncEvent(
        serverLsn = requiredLong("serverLsn", "an event"),
        entityId = entityId,
        entityType = docType,
        eventType = eventType,
        data = this["data"],
        createdAt = optionalLong("serverTs") ?: 0L,
        protocolVersion = optionalLong("protocolVersion")?.toInt() ?: 1,
        profileId = optionalString("profileId") ?: "",
    )
}

internal fun JsonObject.toPatchResult(): PatchResult = PatchResult(
    patchId = requiredString("patchId", "a push result"),
    // Absent `ok` means the server did not report this patch as applied.
    // Defaulting it to true would let a malformed result confirm a patch the
    // server never took, and the client's next diff would be computed against
    // that fiction.
    ok = optionalBoolean("ok") ?: false,
    cached = optionalBoolean("cached") ?: false,
    conflict = optionalBoolean("conflict") ?: false,
    newVersion = optionalLong("newVersion"),
    newState = this["newState"],
    serverState = this["serverState"],
    error = optionalString("error"),
)

internal fun String.readJson(what: String): JsonElement = try {
    Json.parseToJsonElement(this)
} catch (e: SerializationException) {
    throw AppError.Network(
        "$what was not JSON: ${e.message ?: e::class.simpleName}",
        code = "sync.malformed_response",
        cause = e,
    )
}

internal fun String.readObject(what: String): JsonObject =
    readJson(what) as? JsonObject ?: throw malformed("$what was not an object")

internal fun malformed(message: String) = AppError.Network(message, code = "sync.malformed_response")

internal fun JsonObject.requiredArray(key: String, what: String): List<JsonElement> {
    val value = this[key] ?: throw malformed("$what has no '$key'")
    return (value as? JsonArray)?.toList() ?: throw malformed("$what has a '$key' that is not a list")
}

internal fun JsonObject.requiredString(key: String, what: String): String =
    optionalString(key) ?: throw malformed("$what has no string '$key'")

internal fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.optionalBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.requiredLong(key: String, what: String): Long =
    optionalLong(key) ?: throw malformed("$what has no integer '$key'")

/**
 * Reads a 64-bit integer from the JSON *literal*.
 *
 * `longOrNull` parses `content` as text and is exact for every value a `bigint`
 * column can hold. `jsonPrimitive.double.toLong()` is the obvious alternative and
 * is wrong above 2^53, where two different log positions collapse into one and
 * the client resumes from a position the server has never issued.
 */
internal fun JsonObject.optionalLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull
