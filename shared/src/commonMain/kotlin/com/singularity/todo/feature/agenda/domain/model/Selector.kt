package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
@Serializable
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
 * This serializer routes to the correct concrete type based on the `_type` discriminator:
 * - `"Tag"` → [Selector.Tag] (MR1 legacy, manually constructed)
 * - all other types → standard polymorphic deserialization via generated serializer
 */
object SelectorSerializer : JsonContentPolymorphicSerializer<Selector>(Selector::class) {
    @Suppress("UNCHECKED_CAST")
    override fun selectDeserializer(element: JsonElement): kotlinx.serialization.DeserializationStrategy<out Selector> {
        val type = element.jsonObject["_type"]?.jsonPrimitive?.content
        return if (type == "Tag") {
            // MR1 legacy: deserialize Selector.Tag manually.
            TagSerializer as kotlinx.serialization.DeserializationStrategy<Selector>
        } else {
            // All MR2+ types use the standard generated polymorphic serializer.
            serializer<Selector>()
        }
    }

    private object TagSerializer : kotlinx.serialization.KSerializer<Selector.Tag> {
        override val descriptor = serializer<Selector.Tag>().descriptor
        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Selector.Tag {
            val jsonDecoder = decoder as? JsonDecoder
                ?: throw SerializationException("TagSerializer requires a JSON decoder")
            val json = jsonDecoder.decodeJsonElement().jsonObject
            val idString = json["id"]?.jsonPrimitive?.content
                ?: throw SerializationException("Missing 'id' field for Selector.Tag")
            return Selector.Tag(TagId(idString))
        }

        @Suppress("DEPRECATION")
        override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Selector.Tag) {
            serializer<Selector.Tag>().serialize(encoder, value)
        }
    }
}
