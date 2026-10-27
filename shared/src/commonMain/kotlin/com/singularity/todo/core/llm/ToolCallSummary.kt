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
 * The field names whose values may be quoted.
 *
 * ## Why a list and not a length rule
 *
 * A length rule cannot tell a note body from a task title: "Buy milk" is four characters
 * either way. So the rule was "quote anything short and single-line", and a one-line note
 * body — the exact content the summary exists to keep off the screen — was quoted into the
 * card. FR-020 says the summary must contain *only identifying information*, and a bound on
 * the value cannot express that.
 *
 * So the decision is field-based: a value is quoted only when its **field** identifies rather
 * than describes. Everything else is named and elided (`body=…`), because the reader needs to
 * know *what kind of thing* was touched, not what it says. The list is explicit so that a new
 * free-text argument cannot leak by being short; it has to be added here, where a reviewer
 * sees it, and the reason it is identifying written next to it.
 *
 * ## Why numbers and flags have no entry
 *
 * A JSON number or boolean is not free text, whatever its field is called: it cannot carry a
 * paragraph. [describe] quotes those by the value rule alone, before this list is consulted,
 * so a boolean or a number in this list would be an entry nothing reads.
 *
 * The list is maintained by hand against the `*Input` models under `feature/ai/tools/`. It has
 * to be re-derived when a tool gains a string argument: a new free-text argument is elided by
 * default, and a new identifying one has to be added here.
 */
private val IDENTIFYING_FIELDS: Set<String> = setOf(
    // Identifiers: the name of a row, which is all a card needs to say what was touched.
    "id", "taskId", "noteId", "projectId", "tagId", "tagGroupId", "parentId", "parentNoteId",
    "parentTaskId", "itemId", "attachmentId", "seriesId", "proposalId", "callId", "userId",
    "profileId", "dependsOnTaskId", "dependsOn", "supersededBy",
    // Titles and names: the label of the thing, not its content.
    "title", "name", "label", "slug", "icon", "projectName",
    // Categorical values: one word from a closed set.
    "kind", "status", "priority", "tone", "verb", "operation", "field", "mode", "scope",
    // Dates.
    "dueDate", "dueTime", "startDate", "endDate", "startsAt", "endsAt", "date", "time",
    // A colour is a hex string in some tools and a number in others; the number is quoted by
    // shape, the string needs its name here.
    "colorHex",
)

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
 * So the summary names the fields, quotes the values of fields that **identify** (see
 * [IDENTIFYING_FIELDS]) and that are short and single-line, and replaces every other value
 * with an ellipsis. A free-text value — a note body, a description, a query — is elided
 * whether it is long or short, because its length says nothing about whether it is the
 * reader's content.
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
        "$name=${describe(name, value)}"
    }.joinToString(", ")

    val overflow = fields.size - MAX_FIELDS
    val tail = if (overflow > 0) ", +$overflow more" else ""

    return bound("$head($described$tail)")
}

/**
 * One field's value, quoted only if the field identifies and the value is a label.
 *
 * Numbers and flags are quoted by the value rule alone: they cannot carry free text. A
 * string is quoted only when its field is in [IDENTIFYING_FIELDS] — the field decides, not
 * the length, because a short value is exactly as likely to be the reader's own words.
 */
private fun describe(name: String, value: kotlinx.serialization.json.JsonElement): String {
    val primitive = value as? JsonPrimitive ?: return "…"
    val text = primitive.content
    return when {
        !primitive.isString -> if (text.length <= MAX_VALUE_LENGTH) text else "…"
        text.isEmpty() -> "∅"
        name !in IDENTIFYING_FIELDS -> "…"
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
