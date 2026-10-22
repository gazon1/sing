package com.singularity.todo.core.ai

import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a reader was in a list, so that they can pick it up where they left it.
 *
 * ## Why the cursor is opaque
 *
 * A cursor is a promise about a list, not a description of one. It says "you have seen
 * the first [offset] entries of the list identified by [fingerprint]". A reader that
 * hands it back gets the next entries of *that* list — and a reader that hands it back
 * for a different list is told so, rather than being given the wrong page.
 *
 * That is what [fingerprint] is for. Without it, a cursor from the overdue list would
 * silently page through the inbox, and the reader would have no way to tell that the
 * second page belonged to something else.
 */
@Serializable
data class TaskPageCursor(
    /** How many entries the reader has already seen. */
    val offset: Int,
    /** What the reader was reading: the selection and its narrowing. */
    val fingerprint: String,
)

private val cursorJson = Json { encodeDefaults = true }

private val urlSafeNoPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

/**
 * Encodes this cursor into a string the reader can carry.
 *
 * URL-safe and unpadded, so the cursor survives being put in a query parameter, a
 * message, or a shell command without being escaped into something unreadable.
 */
fun TaskPageCursor.encode(): String = urlSafeNoPadding.encode(
    cursorJson.encodeToString(TaskPageCursor.serializer(), this).encodeToByteArray(),
)

/**
 * Decodes a cursor, or returns null if the string is not one.
 *
 * A malformed cursor is not an error to report: the reader handed over something that
 * is not a cursor, and the honest answer is that there is no page to continue from.
 */
fun String.decodeTaskPageCursor(): TaskPageCursor? = runCatching {
    cursorJson.decodeFromString(
        TaskPageCursor.serializer(),
        urlSafeNoPadding.decode(this).decodeToString(),
    )
}.getOrNull()
