package com.singularity.todo.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How long a tool-call summary may be.
 *
 * The summary is a label, not a document. Past this length it stops being scannable and
 * starts being a second copy of the payload.
 */
const val TOOL_CALL_SUMMARY_MAX_LENGTH: Int = 200

/** The longest single value the summary will quote. */
private const val MAX_VALUE_LENGTH: Int = 60

/** How many fields the summary will name. */
private const val MAX_FIELDS: Int = 6

private val summaryJson = Json { ignoreUnknownKeys = true }

/**
 * A short, sanitized description of what a tool call touched.
 *
 * ## Why this is not "the arguments"
 *
 * The arguments to a tool call are where the user's content lives. A call that updates a
 * note carries the note's text; a call that creates a task carries the task's title. The
 * reader of a card needs to know *what* was touched, not *the whole thing* — and a card
 * that shows a note body puts that content on a screen that was about to be shared,
 * photographed or screenshotted.
 *
 * So the summary names the fields and quotes the values that are too short to be prose:
 * identifiers, ids, titles, numbers, flags. A value that is long, or that spans lines, is
 * replaced by an ellipsis — it is either free text or a document, and neither belongs in a
 * label.
 *
 * ## Why it is a function and not UI code
 *
 * The rule "what may be quoted" is a rule about data, not about layout. Keeping it here
 * means the same rule applies wherever a summary is built — the built-in agent's loop and
 * the external agent's adapter — and a card cannot quietly widen it.
 *
 * @param toolName the tool that was called
 * @param argumentsJson the arguments, as JSON; may be absent or unreadable
 * @return a summary of at most [TOOL_CALL_SUMMARY_MAX_LENGTH] characters
 */
fun toolCallSummary(toolName: String, argumentsJson: String?): String {
    val head = toolName.ifBlank { "tool" }
    if (argumentsJson.isNullOrBlank()) return head

    val parsed = runCatching { summaryJson.parseToJsonElement(argumentsJson) }.getOrNull()
    if (parsed == null) return "$head (arguments not readable)"
    val fields = parsed as? JsonObject
    if (fields == null || fields.isEmpty()) return head

    val described = fields.entries.take(MAX_FIELDS).map { (name, value) ->
        "$name=${describe(value)}"
    }.joinToString(", ")

    val overflow = fields.size - MAX_FIELDS
    val tail = if (overflow > 0) ", +$overflow more" else ""

    return bound("$head($described$tail)")
}

/**
 * One field's value, quoted only if it is too short to be prose.
 */
private fun describe(value: kotlinx.serialization.json.JsonElement): String {
    val primitive = value as? JsonPrimitive ?: return "…"
    val text = primitive.content
    return when {
        !primitive.isString -> if (text.length <= MAX_VALUE_LENGTH) text else "…"
        text.isEmpty() -> "∅"
        text.isDocument() -> "…"
        else -> text
    }
}

/**
 * Whether the value is a document rather than a label.
 *
 * A value that spans lines is a document: quoting a paragraph into a card is noise, and
 * a value longer than the limit is one whatever it looks like.
 */
private fun String.isDocument(): Boolean = contains('\n') || length > MAX_VALUE_LENGTH

/** Trims to the limit, at a word boundary where one is available. */
private fun bound(summary: String): String {
    if (summary.length <= TOOL_CALL_SUMMARY_MAX_LENGTH) return summary
    val cut = summary.take(TOOL_CALL_SUMMARY_MAX_LENGTH)
    val lastSpace = cut.lastIndexOf(' ')
    val trimmed = if (lastSpace > TOOL_CALL_SUMMARY_MAX_LENGTH / 2) cut.take(lastSpace) else cut
    return "$trimmed…"
}
