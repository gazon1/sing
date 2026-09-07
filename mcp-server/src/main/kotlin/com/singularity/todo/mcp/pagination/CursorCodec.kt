package com.singularity.todo.mcp.pagination

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Opaque cursor for list-tool pagination.
 *
 * Encode: [Cursor] → URL-safe Base64 string (no padding)
 * Decode: string → [Cursor]? (returns null on malformed input)
 *
 * Page size: 50 items. If a list returns exactly 50 items, the next page cursor
 * is returned. An absent/empty cursor means "no more pages".
 */
@Serializable
data class Cursor(
    val offset: Int,
    val sortKey: String,
)

private val codecJson = Json { encodeDefaults = true }

/** Encodes this [Cursor] to a URL-safe Base64 string with no trailing padding. */
fun Cursor.encode(): String = Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(codecJson.encodeToString(Cursor.serializer(), this).toByteArray())

/**
 * Decodes a URL-safe Base64 cursor string (with or without trailing padding) to a [Cursor].
 *
 * Returns null if the string is malformed or the deserialization fails.
 */
fun String.decodeCursor(): Cursor? = runCatching {
    val padded = this + "=".repeat((4 - length % 4) % 4)
    val bytes = Base64.getUrlDecoder().decode(padded)
    codecJson.decodeFromString(Cursor.serializer(), String(bytes))
}.getOrNull()

/**
 * Calculates the next page cursor given the current offset and sort key.
 *
 * Returns null if fewer than [PAGE_SIZE] items were returned (no further pages).
 */
fun nextCursorIfMorePages(currentOffset: Int, itemsReturned: Int, sortKey: String): String? {
    return if (itemsReturned == PAGE_SIZE) {
        Cursor(offset = currentOffset + PAGE_SIZE, sortKey = sortKey).encode()
    } else {
        null
    }
}

/** Standard page size for all list tools. */
const val PAGE_SIZE = 50
