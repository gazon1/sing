package com.singularity.todo.core.backup

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Deserializes a JSON array column (`"[]"`, `"["a","b"]"`) into a list.
 *
 * The links of a task and of a note are stored as a JSON array in a text column,
 * and both directions go through here: the column is read into a list, and the
 * list is written back into the column.
 *
 * Tolerates legacy/corrupt payloads instead of aborting a whole backup restore.
 */
internal fun decodeLinkList(raw: String): List<String> =
    runCatching {
        Json.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrDefault(emptyList())

/** Serializes a link list back into the JSON array column representation. */
internal fun encodeLinkList(links: List<String>): String =
    Json.encodeToString(ListSerializer(String.serializer()), links)
