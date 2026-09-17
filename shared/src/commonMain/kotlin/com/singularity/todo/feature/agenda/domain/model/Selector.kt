package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.feature.agenda.domain.model.SelectorSerializer.deserialize
import com.singularity.todo.feature.agenda.domain.model.SelectorSerializer.serialize
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * Relative date buckets for [DateBucket] selector.
 *
 * Used for preset sections like "Today", "This Week", "Overdue", etc.
 * The actual date range for each bucket is computed by [RelativeBucket.toDateRange]
 * using the provided `today`.
 */
@Serializable
enum class RelativeBucket {
    Today,
    Yesterday,
    Tomorrow,
    ThisWeek,
    NextWeek,
    ThisMonth,
    NextMonth,
    Overdue,
    NoDate,
}

/**
 * A selector predicate that filters tasks for a section.
 *
 * All selectors are pure predicates — they carry no UI metadata (badges, colors, etc.).
 * Metadata (e.g. "12 tasks overdue") is computed from the result by [AgendaEvaluator].
 *
 * @see AgendaEvaluator.matches
 */
@Serializable(with = SelectorSerializer::class)
@SerialName("Selector")
sealed interface Selector {

    /**
     * Tasks bucketed by a relative date period.
     * - [RelativeBucket.Overdue] — dueDate < today
     * - [RelativeBucket.NoDate] — dueDate is null
     * All other buckets use the range computed by [RelativeBucket.toDateRange].
     */
    @Serializable
    @SerialName("DateBucket")
    data class DateBucket(val bucket: RelativeBucket) : Selector

    /**
     * Tasks with dueDate in the inclusive range [from]..[to].
     * Used by the Calendar screen and custom date-range presets.
     */
    @Serializable
    @SerialName("DateRange")
    data class DateRange(val from: kotlinx.datetime.LocalDate, val to: kotlinx.datetime.LocalDate) : Selector

    /** Tasks filtered by completion status. */
    @Serializable
    @SerialName("Statuses")
    data class Statuses(val statuses: Set<TaskStatus>) : Selector

    /**
     * Tasks filtered by priority.
     * @param atMost If true (default), matches tasks with priority IN [priorities].
     *               If false, matches tasks with priority NOT in [priorities].
     */
    @Serializable
    @SerialName("Priorities")
    data class Priorities(val priorities: Set<TaskPriority>, val atMost: Boolean = true) : Selector

    /**
     * Tasks that have the given tag (directly, not inherited).
     *
     * @deprecated Use [Tags] for new code. This variant is kept for MR1 JSON backwards
     * compatibility. The [SelectorSerializer] shim accepts both `"Tag"` and `"Tags"`
     * discriminator values for one release cycle.
     */
    @Serializable
    @SerialName("Tag")
    @Deprecated("Use Tags(setOf(id)) instead", ReplaceWith("Tags(setOf(id))"))
    data class Tag(val id: TagId) : Selector

    /**
     * Tasks that are tagged with any of the given [TagId]s.
     *
     * @param ids The set of tag IDs to match against.
     * @param matchAll When true (default false in DSL), a task must have all of the
     *                 given tags. When false, a task matching any one tag is included.
     */
    @Serializable
    @SerialName("Tags")
    data class Tags(val ids: Set<TagId>, val matchAll: Boolean = false) : Selector

    /** Tasks that belong to any of the given projects. */
    @Serializable
    @SerialName("Projects")
    data class Projects(val ids: Set<ProjectId>) : Selector

    /** Pinned (starred) tasks. */
    @Serializable
    @SerialName("Pinned")
    data object Pinned : Selector

    /** Completed tasks (completedAt != null). */
    @Serializable
    @SerialName("Completed")
    data object Completed : Selector

    /**
     * Overdue tasks — dueDate < today AND not completed.
     * A shortcut for `DateBucket(Overdue)` combined with `Statuses(setOf(Active))`.
     */
    @Serializable
    @SerialName("Overdue")
    data object Overdue : Selector

    /** Tasks whose title matches the given regular expression (case-insensitive). */
    @Serializable
    @SerialName("Regexp")
    data class Regexp(val query: String) : Selector

    /** All children must match. */
    @Serializable
    @SerialName("AllOf")
    data class AllOf(val children: List<Selector>) : Selector

    /** At least one child must match. */
    @Serializable
    @SerialName("AnyOf")
    data class AnyOf(val children: List<Selector>) : Selector

    /** The child must NOT match. */
    @Serializable
    @SerialName("Not")
    data class Not(val child: Selector) : Selector

    /** Matches every task (used as base filter). */
    @Serializable
    @SerialName("Anything")
    data object Anything : Selector
}

/**
 * JSON migration shim for the [Selector.Tag] → [Selector.Tags] rename.
 *
 * MR1 snapshots stored `"_type":"Tag"` with a single `id` field.
 * MR2+ encodes `"_type":"Tags"` with an `ids: Set<TagId>` field.
 *
 * This serializer is a plain [KSerializer] — it overrides [serialize] and [deserialize]
 * directly rather than relying on [JsonContentPolymorphicSerializer.selectDeserializer],
 * which would cause infinite recursion (calling [serializer] for [Selector] returns this
 * same serializer, which calls [serializer] again, etc.).
 *
 * For non-Tag types, it calls the generated serializer for each concrete subtype directly.
 * Those generated serializers do NOT have `@Serializable(with = ...)` so there is no
 * further recursion.
 */
object SelectorSerializer : kotlinx.serialization.KSerializer<Selector> {
    // Cannot use serializer<Selector>().descriptor — it returns this same serializer
    // (because Selector is @Serializable(with = SelectorSerializer::class)),
    // causing infinite recursion. Use buildClassSerialDescriptor instead.
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Selector")

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Selector) {
        val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder
            ?: throw SerializationException("Selector serialization requires a JSON encoder")

        val element: JsonElement = when (value) {
            is Selector.Tag -> JsonObject(
                mapOf("_type" to JsonPrimitive("Tag"), "id" to JsonPrimitive(value.id.value))
            )
            is Selector.DateBucket -> JsonObject(
                mapOf("_type" to JsonPrimitive("DateBucket"), "bucket" to JsonPrimitive(value.bucket.name))
            )
            is Selector.DateRange -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("DateRange"),
                    "from" to JsonPrimitive(value.from.toString()),
                    "to" to JsonPrimitive(value.to.toString()),
                )
            )
            is Selector.Tags -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("Tags"),
                    "ids" to JsonArray(value.ids.map { JsonPrimitive(it.value) }),
                    "matchAll" to JsonPrimitive(value.matchAll),
                )
            )
            is Selector.Statuses -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("Statuses"),
                    "statuses" to JsonArray(value.statuses.map { JsonPrimitive(it.name) }),
                )
            )
            is Selector.Priorities -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("Priorities"),
                    "priorities" to JsonArray(value.priorities.map { JsonPrimitive(it.name) }),
                    "atMost" to JsonPrimitive(value.atMost),
                )
            )
            is Selector.Projects -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("Projects"),
                    "ids" to JsonArray(value.ids.map { JsonPrimitive(it.value) }),
                )
            )
            is Selector.Pinned -> JsonObject(mapOf("_type" to JsonPrimitive("Pinned")))
            is Selector.Completed -> JsonObject(mapOf("_type" to JsonPrimitive("Completed")))
            is Selector.Overdue -> JsonObject(mapOf("_type" to JsonPrimitive("Overdue")))
            is Selector.Regexp -> JsonObject(
                mapOf("_type" to JsonPrimitive("Regexp"), "query" to JsonPrimitive(value.query))
            )
            is Selector.AllOf -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("AllOf"),
                    "children" to JsonArray(value.children.map { child ->
                        serializeToElement(jsonEncoder, child)
                    }),
                )
            )
            is Selector.AnyOf -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("AnyOf"),
                    "children" to JsonArray(value.children.map { child ->
                        serializeToElement(jsonEncoder, child)
                    }),
                )
            )
            is Selector.Not -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("Not"),
                    "child" to serializeToElement(jsonEncoder, value.child),
                )
            )
            is Selector.Anything -> JsonObject(mapOf("_type" to JsonPrimitive("Anything")))
        }
        jsonEncoder.encodeJsonElement(element)
    }

    /**
     * Serializes [value] to a [JsonElement] using this serializer's logic,
     * bypassing the token-stream API so nested selectors can be serialized independently.
     */
    private fun serializeToElement(jsonEncoder: kotlinx.serialization.json.JsonEncoder, value: Selector): JsonElement {
        return when (value) {
            is Selector.Tag -> JsonObject(
                mapOf("_type" to JsonPrimitive("Tag"), "id" to JsonPrimitive(value.id.value))
            )
            is Selector.DateBucket -> JsonObject(
                mapOf("_type" to JsonPrimitive("DateBucket"), "bucket" to JsonPrimitive(value.bucket.name))
            )
            is Selector.DateRange -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("DateRange"),
                    "from" to JsonPrimitive(value.from.toString()),
                    "to" to JsonPrimitive(value.to.toString()),
                )
            )
            is Selector.Tags -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("Tags"),
                    "ids" to JsonArray(value.ids.map { JsonPrimitive(it.value) }),
                    "matchAll" to JsonPrimitive(value.matchAll),
                )
            )
            is Selector.Statuses -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("Statuses"),
                    "statuses" to JsonArray(value.statuses.map { JsonPrimitive(it.name) }),
                )
            )
            is Selector.Priorities -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("Priorities"),
                    "priorities" to JsonArray(value.priorities.map { JsonPrimitive(it.name) }),
                    "atMost" to JsonPrimitive(value.atMost),
                )
            )
            is Selector.Projects -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("Projects"),
                    "ids" to JsonArray(value.ids.map { JsonPrimitive(it.value) }),
                )
            )
            is Selector.Pinned -> JsonObject(mapOf("_type" to JsonPrimitive("Pinned")))
            is Selector.Completed -> JsonObject(mapOf("_type" to JsonPrimitive("Completed")))
            is Selector.Overdue -> JsonObject(mapOf("_type" to JsonPrimitive("Overdue")))
            is Selector.Regexp -> JsonObject(
                mapOf("_type" to JsonPrimitive("Regexp"), "query" to JsonPrimitive(value.query))
            )
            is Selector.AllOf -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("AllOf"),
                    "children" to JsonArray(value.children.map { serializeToElement(jsonEncoder, it) }),
                )
            )
            is Selector.AnyOf -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("AnyOf"),
                    "children" to JsonArray(value.children.map { serializeToElement(jsonEncoder, it) }),
                )
            )
            is Selector.Not -> JsonObject(
                mapOf(
                    "_type" to JsonPrimitive("Not"),
                    "child" to serializeToElement(jsonEncoder, value.child),
                )
            )
            is Selector.Anything -> JsonObject(mapOf("_type" to JsonPrimitive("Anything")))
        }
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Selector {
        // kotlinx uses two decoder patterns:
        // 1. StreamingJsonDecoder: token-by-token JSON stream. decodeJsonElement() reads the
        //    current element from the stream. Used when Selector is a top-level type.
        // 2. TreeJsonDecoder: wraps a JsonElement. decodeJsonElement() returns that element.
        //    Used when Selector is nested (e.g. AllOf.children) — kotlinx decodes each child
        //    by calling decodeSerializableValue on the element's JSON string.
        //
        // Both implement JsonDecoder. For StreamingJsonDecoder, decodeJsonElement() reads from
        // the stream. For TreeJsonDecoder, it returns the wrapped element directly.
        val element = (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement()
        val obj = element.jsonObject
        val type = obj["_type"]?.jsonPrimitive?.content
            ?: throw SerializationException("Missing '_type' discriminator")

        // Tag: MR1 legacy format — deserialize manually.
        if (type == "Tag") {
            val id = obj["id"]?.jsonPrimitive?.content
                ?: throw SerializationException("Missing 'id' field for Selector.Tag")
            return Selector.Tag(TagId(id))
        }

        // All MR2+ types: construct manually from the JSON object fields.
        // The generated deserializers (e.g. Selector$Tags$$serializer) don't accept
        // the "_type" discriminator in their input, so we extract fields individually.
        return when (type) {
            "DateBucket" -> Selector.DateBucket(
                bucket = Json.decodeFromJsonElement(
                    serializer<RelativeBucket>(),
                    obj.getValue("bucket"),
                ),
            )
            "DateRange" -> Selector.DateRange(
                from = Json.decodeFromJsonElement(serializer(), obj.getValue("from")),
                to = Json.decodeFromJsonElement(serializer(), obj.getValue("to")),
            )
            "Tags" -> Selector.Tags(
                ids = obj.getValue("ids").let { idsJson ->
                    Json.decodeFromJsonElement(serializer(), idsJson)
                },
                matchAll = obj["matchAll"]?.let {
                    Json.decodeFromJsonElement(serializer(), it)
                } ?: false,
            )
            "Statuses" -> Selector.Statuses(
                statuses = obj.getValue("statuses").let {
                    Json.decodeFromJsonElement(serializer(), it)
                },
            )
            "Priorities" -> Selector.Priorities(
                priorities = obj.getValue("priorities").let {
                    Json.decodeFromJsonElement(serializer(), it)
                },
                atMost = obj["atMost"]?.let {
                    Json.decodeFromJsonElement(serializer(), it)
                } ?: true,
            )
            "Projects" -> Selector.Projects(
                ids = obj.getValue("ids").let {
                    Json.decodeFromJsonElement(serializer(), it)
                },
            )
            "Pinned" -> Selector.Pinned
            "Completed" -> Selector.Completed
            "Overdue" -> Selector.Overdue
            "Regexp" -> Selector.Regexp(
                query = obj.getValue("query").jsonPrimitive.content,
            )
            "AllOf" -> Selector.AllOf(
                children = obj.getValue("children").let {
                    Json.decodeFromJsonElement(serializer(), it)
                },
            )
            "AnyOf" -> Selector.AnyOf(
                children = obj.getValue("children").let {
                    Json.decodeFromJsonElement(serializer(), it)
                },
            )
            "Not" -> Selector.Not(
                child = Json.decodeFromJsonElement(serializer(), obj.getValue("child")),
            )
            "Anything" -> Selector.Anything
            else -> throw SerializationException("Unknown Selector type: $type")
        }
    }
}
