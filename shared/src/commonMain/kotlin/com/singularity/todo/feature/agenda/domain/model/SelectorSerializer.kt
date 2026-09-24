/*
 * Copyright 2025 New Vector Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.feature.tags.TagId
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * The serialized discriminator tag for a [Selector] variant.
 * Kept as a plain `when` expression (14 cases) so it requires no reflection
 * and works across all KMP targets.
 */
val Selector.typeTag: String
    get() = when (this) {
        is Selector.DateBucket -> "DateBucket"
        is Selector.DateRange -> "DateRange"
        is Selector.Tags -> "Tags"
        is Selector.Statuses -> "Statuses"
        is Selector.Priorities -> "Priorities"
        is Selector.Projects -> "Projects"
        is Selector.Pinned -> "Pinned"
        is Selector.Completed -> "Completed"
        is Selector.Overdue -> "Overdue"
        is Selector.Regexp -> "Regexp"
        is Selector.AllOf -> "AllOf"
        is Selector.AnyOf -> "AnyOf"
        is Selector.Not -> "Not"
        is Selector.Anything -> "Anything"
    }

/**
 * A encode/decode pair for one [Selector] variant.
 */
private data class SelectorEntry(val encode: (Selector) -> JsonObject, val decode: (JsonObject) -> Selector)

/**
 * Holds the fully-constructed registry at call time (not capture time).
 *
 * Encode lambdas of composite selectors are created during `SelectorSerializer` object
 * initialization, before `compositeEntries` and `leafEntries` are fully self-consistent.
 * To avoid forward-reference errors, they capture only `RegistryHolder`, which provides
 * access to the complete `allEntries` map at call/encode time.
 */
private class RegistryHolder {
    // Initialized in declaration order: leafEntries → compositeEntries → allEntries
    val leafEntries: Map<String, SelectorEntry> = buildMap {
        put(
            "DateBucket",
            SelectorEntry(
            encode = { s ->
                JsonObject(
                    mapOf(
                    "_type" to JsonPrimitive("DateBucket"),
                    "bucket" to JsonPrimitive((s as Selector.DateBucket).bucket.name),
                )
                )
            },
            decode = { obj ->
                Selector.DateBucket(
                    bucket = Json.decodeFromJsonElement(serializer(), obj.getValue("bucket")),
                )
            },
        )
        )

        put(
            "DateRange",
            SelectorEntry(
            encode = { s ->
                JsonObject(
                    mapOf(
                    "_type" to JsonPrimitive("DateRange"),
                    "from" to JsonPrimitive((s as Selector.DateRange).from.toString()),
                    "to" to JsonPrimitive(s.to.toString()),
                )
                )
            },
            decode = { obj ->
                Selector.DateRange(
                    from = Json.decodeFromJsonElement(serializer(), obj.getValue("from")),
                    to = Json.decodeFromJsonElement(serializer(), obj.getValue("to")),
                )
            },
        )
        )

        put(
            "Tags",
            SelectorEntry(
            encode = { s ->
                JsonObject(
                    mapOf(
                    "_type" to JsonPrimitive("Tags"),
                    "ids" to JsonArray((s as Selector.Tags).ids.map { JsonPrimitive(it.value) }),
                    "matchAll" to JsonPrimitive(s.matchAll),
                )
                )
            },
            decode = { obj ->
                Selector.Tags(
                    ids = Json.decodeFromJsonElement(serializer(), obj.getValue("ids")),
                    matchAll = obj["matchAll"]?.let { Json.decodeFromJsonElement(serializer(), it) } ?: false,
                )
            },
        )
        )

        put(
            "Statuses",
            SelectorEntry(
            encode = { s ->
                JsonObject(
                    mapOf(
                    "_type" to JsonPrimitive("Statuses"),
                    "statuses" to JsonArray((s as Selector.Statuses).statuses.map { JsonPrimitive(it.name) }),
                )
                )
            },
            decode = { obj ->
                Selector.Statuses(
                    statuses = Json.decodeFromJsonElement(serializer(), obj.getValue("statuses")),
                )
            },
        )
        )

        put(
            "Priorities",
            SelectorEntry(
            encode = { s ->
                JsonObject(
                    mapOf(
                    "_type" to JsonPrimitive("Priorities"),
                    "priorities" to JsonArray((s as Selector.Priorities).priorities.map { JsonPrimitive(it.name) }),
                    "atMost" to JsonPrimitive(s.atMost),
                )
                )
            },
            decode = { obj ->
                Selector.Priorities(
                    priorities = Json.decodeFromJsonElement(serializer(), obj.getValue("priorities")),
                    atMost = obj["atMost"]?.let { Json.decodeFromJsonElement(serializer(), it) } ?: true,
                )
            },
        )
        )

        put(
            "Projects",
            SelectorEntry(
            encode = { s ->
                JsonObject(
                    mapOf(
                    "_type" to JsonPrimitive("Projects"),
                    "ids" to JsonArray((s as Selector.Projects).ids.map { JsonPrimitive(it.value) }),
                )
                )
            },
            decode = { obj ->
                Selector.Projects(
                    ids = Json.decodeFromJsonElement(serializer(), obj.getValue("ids")),
                )
            },
        )
        )

        put(
            "Pinned",
            SelectorEntry(
            encode = { JsonObject(mapOf("_type" to JsonPrimitive("Pinned"))) },
            decode = { Selector.Pinned },
        )
        )

        put(
            "Completed",
            SelectorEntry(
            encode = { JsonObject(mapOf("_type" to JsonPrimitive("Completed"))) },
            decode = { Selector.Completed },
        )
        )

        put(
            "Overdue",
            SelectorEntry(
            encode = { JsonObject(mapOf("_type" to JsonPrimitive("Overdue"))) },
            decode = { Selector.Overdue },
        )
        )

        put(
            "Regexp",
            SelectorEntry(
            encode = { s ->
                JsonObject(
                    mapOf(
                    "_type" to JsonPrimitive("Regexp"),
                    "query" to JsonPrimitive((s as Selector.Regexp).query),
                )
                )
            },
            decode = { obj ->
                Selector.Regexp(
                    query = obj.getValue("query").jsonPrimitive.content,
                )
            },
        )
        )

        put(
            "Anything",
            SelectorEntry(
            encode = { JsonObject(mapOf("_type" to JsonPrimitive("Anything"))) },
            decode = { Selector.Anything },
        )
        )
    }.also { require(it.size == 11) { "Expected 11 leaf entries, got ${it.size}" } }

    // compositeEntries encode lambdas capture `this` (the RegistryHolder instance) and look up
    // from `allEntries` at CALL time, not at capture/creation time. This avoids the
    // "forward reference" issue: at creation time, only `leafEntries` is initialized;
    // `allEntries` is computed lazily and available by the time any encode is called.
    val compositeEntries: Map<String, SelectorEntry> = buildMap {
        put(
            "AllOf",
            SelectorEntry(
            encode = { s ->
                val allOf = s as Selector.AllOf
                JsonObject(
                    mapOf(
                    "_type" to JsonPrimitive("AllOf"),
                    "children" to JsonArray(
                        allOf.children.map { child ->
                        allEntries.getValue(child.typeTag).encode(child)
                    }
                    ),
                )
                )
            },
            decode = { obj ->
                Selector.AllOf(
                    children = Json.decodeFromJsonElement(serializer(), obj.getValue("children")),
                )
            },
        )
        )

        put(
            "AnyOf",
            SelectorEntry(
            encode = { s ->
                val anyOf = s as Selector.AnyOf
                JsonObject(
                    mapOf(
                    "_type" to JsonPrimitive("AnyOf"),
                    "children" to JsonArray(
                        anyOf.children.map { child ->
                        allEntries.getValue(child.typeTag).encode(child)
                    }
                    ),
                )
                )
            },
            decode = { obj ->
                Selector.AnyOf(
                    children = Json.decodeFromJsonElement(serializer(), obj.getValue("children")),
                )
            },
        )
        )

        put(
            "Not",
            SelectorEntry(
            encode = { s ->
                val not = s as Selector.Not
                JsonObject(
                    mapOf(
                    "_type" to JsonPrimitive("Not"),
                    "child" to allEntries.getValue(not.child.typeTag).encode(not.child),
                )
                )
            },
            decode = { obj ->
                Selector.Not(
                    child = Json.decodeFromJsonElement(serializer(), obj.getValue("child")),
                )
            },
        )
        )
    }.also { require(it.size == 3) { "Expected 3 composite entries, got ${it.size}" } }

    val allEntries: Map<String, SelectorEntry> = leafEntries + compositeEntries
}

/**
 * JSON migration shim for the [Selector.Tag] → [Selector.Tags] rename.
 *
 * MR1 snapshots stored `"_type":"Tag"` with a single `id` field.
 * MR2+ encodes `"_type":"Tags"` with an `ids: Set<TagId>` field.
 *
 * Uses a Map-based registry: each Selector variant is registered once.
 * Adding a new variant requires only 2 changes:
 * 1. The `@SerialName` annotation on the variant class (always required).
 * 2. One entry in [RegistryHolder.leafEntries] or [RegistryHolder.compositeEntries].
 *
 * The [RegistryHolder] class avoids Kotlin's val initialization order restriction:
 * composite encode lambdas are created during `SelectorSerializer` object init, before
 * `compositeEntries` is available. They capture `this` (the holder) and look up from
 * `allEntries` at call time — by which point `allEntries` is fully initialized.
 */
object SelectorSerializer : KSerializer<Selector> {
    // Cannot use serializer<Selector>().descriptor — it returns this same serializer
    // (because Selector is @Serializable(with = SelectorSerializer::class)),
    // causing infinite recursion. Use buildClassSerialDescriptor instead.
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Selector")

    private val registry = RegistryHolder()

    /** All 14 expected typeTag values — used for the construction assertion. */
    private val expectedTypeTags: Set<String> by lazy {
        setOf(
            Selector.DateBucket(RelativeBucket.Today),
            Selector.DateRange(LocalDate(2000, 1, 1), LocalDate(2000, 1, 1)),
            Selector.Tags(setOf()),
            Selector.Statuses(setOf()),
            Selector.Priorities(setOf()),
            Selector.Projects(setOf()),
            Selector.Pinned,
            Selector.Completed,
            Selector.Overdue,
            Selector.Regexp(""),
            Selector.AllOf(emptyList()),
            Selector.AnyOf(emptyList()),
            Selector.Not(Selector.Anything),
            Selector.Anything,
        ).map { it.typeTag }.toSet()
    }

    init {
        val missing = expectedTypeTags - registry.allEntries.keys
        check(registry.allEntries.size == expectedTypeTags.size && missing.isEmpty()) {
            "SelectorSerializer registry is incomplete. Missing: $missing"
        }
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Selector) {
        val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder
            ?: throw SerializationException("Selector serialization requires a JSON encoder")
        jsonEncoder.encodeJsonElement(registry.allEntries.getValue(value.typeTag).encode(value))
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Selector {
        val element = (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement()
        val obj = element.jsonObject
        val type = obj["_type"]?.jsonPrimitive?.content
            ?: throw SerializationException("Missing '_type' discriminator")

        // Legacy migration shim: MR1 "Tag" → MR2 "Tags"
        if (type == "Tag") {
            val id = obj["id"]?.jsonPrimitive?.content
                ?: throw SerializationException("Missing 'id' field for Selector.Tag legacy")
            return Selector.Tags(setOf(TagId(id)))
        }

        return registry.allEntries.getValue(type).decode(obj)
    }
}
