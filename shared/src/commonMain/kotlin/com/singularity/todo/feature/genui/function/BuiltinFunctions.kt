package com.singularity.todo.feature.genui.function

import com.singularity.todo.core.ui.components.formatDueChip
import com.singularity.todo.core.ui.components.formatRussianDueDate
import com.singularity.todo.core.ui.formatFileSize
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The functions a model may call, by name.
 *
 * A registry rather than a `when` over names, so that a deployment can extend the set and so that
 * the check "did you send something I do not have" is one lookup instead of a branch that a new
 * function has to be remembered in.
 */
class A2uiFunctionRegistry(functions: Map<String, A2uiFunction> = BuiltinFunctions.ALL) {

    private val functions: Map<String, A2uiFunction> = functions

    /** Returns the function called [name], or null when this client has no such function. */
    fun resolve(name: String): A2uiFunction? = functions[name]

    /** The names available, for prompt generation and for template validation. */
    val names: Set<String> get() = functions.keys

    /**
     * Calls [name] with [args].
     *
     * Returns null for an unknown function and for arguments that do not fit, so that a template
     * with a bad call renders as nothing visible rather than as a fabricated value. Returning a
     * plausible-looking wrong date is worse than returning nothing, and the model is told about
     * both cases by the parse-time check.
     */
    fun call(name: String, args: List<JsonElement>, ctx: A2uiFunctionContext): JsonElement? =
        resolve(name)?.call(args, ctx)
}

/**
 * The built-in function set.
 *
 * Split by what the functions *do to their argument* — dates and numbers on one side, strings on
 * the other — because that is the distinction a model has to learn anyway when it picks a name, and
 * because a single flat list of nine implementations is a list where every addition is somebody's
 * problem.
 *
 * Every date function here delegates to a formatter that already exists in the project, which is the
 * whole point: "Today, 09:00" is already written once, in `core/ui/components/Formatters.kt`, and a
 * second copy in the catalogue would be a third.
 */
object BuiltinFunctions {

    /** All built-ins by name, for [A2uiFunctionRegistry]. */
    val ALL: Map<String, A2uiFunction> = A2uiDateFunctions.ALL + A2uiTextFunctions.ALL
}

/**
 * Dates, numbers and sizes.
 *
 * These are the functions where a wrong answer looks right: a date formatted by a second code path
 * disagrees with the one the rest of the app uses, and the user sees two different "tomorrow"s in
 * one screen. Each one therefore calls an existing formatter rather than formatting anything itself.
 */
internal object A2uiDateFunctions {

    val ALL: Map<String, A2uiFunction> = mapOf(
        "formatDate" to A2uiFunction { args, ctx -> formatDate(args, ctx) },
        "formatRelative" to A2uiFunction { args, ctx -> formatRelative(args, ctx) },
        "formatNumber" to A2uiFunction { args, _ -> formatNumber(args) },
        "formatBytes" to A2uiFunction { args, _ -> formatBytes(args) },
    )

    /** `formatDate(value, style)` with style one of absolute, relative, both. */
    private fun formatDate(args: List<JsonElement>, ctx: A2uiFunctionContext): JsonElement? {
        val date: LocalDate = localDate(args, 0) ?: return null
        val style: String = text(args, 1) ?: "absolute"
        return when (style) {
            "relative" -> relative(date, ctx.today)?.let(::JsonPrimitive)

            "both" -> {
                val relativeText: String = relative(date, ctx.today) ?: return null
                val absoluteText: String = formatRussianDueDate(date) ?: return null
                JsonPrimitive("$relativeText, $absoluteText")
            }

            else -> JsonPrimitive(formatRussianDueDate(date) ?: date.toString())
        }
    }

    /** `formatRelative(value)` — "Today", "Tomorrow", or a date. */
    private fun formatRelative(args: List<JsonElement>, ctx: A2uiFunctionContext): JsonElement? {
        val date: LocalDate = localDate(args, 0) ?: return null
        return relative(date, ctx.today)?.let(::JsonPrimitive)
    }

    private fun relative(date: LocalDate, today: LocalDate): String? = formatDueChip(date, null, today)?.text

    /**
     * Groups thousands with spaces, the way the rest of the app writes them.
     *
     * Not `NumberFormat`: a grouping separator is a locale decision, and picking one here would mean
     * this function and every other number in the app disagreeing in whichever locale they disagree
     * about.
     */
    private fun formatNumber(args: List<JsonElement>): JsonElement? {
        val number: Double = args.firstOrNull()?.jsonPrimitive?.doubleOrNull ?: return null
        val rounded: Long = number.toLong()
        val grouped: String = rounded.toString()
            .reversed()
            .chunked(3)
            .joinToString(" ")
            .reversed()
        return JsonPrimitive(grouped)
    }

    private fun formatBytes(args: List<JsonElement>): JsonElement? {
        val number: Double = args.firstOrNull()?.jsonPrimitive?.doubleOrNull ?: return null
        return JsonPrimitive(formatFileSize(number.toLong()))
    }

    private fun localDate(args: List<JsonElement>, index: Int): LocalDate? =
        text(args, index)?.let { raw -> runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() }
}

/**
 * Strings and collections.
 *
 * Two of these are the same function under two names. `default` and `ifEmpty` both read "the first
 * argument, or the second if the first is empty" — they are declared separately because the catalog
 * tells a model about them separately, and a model that reaches for one gets the behaviour it
 * expected. They are implemented together on purpose: two spellings of one rule that disagreed
 * later would be a defect neither name would have pointed at.
 */
internal object A2uiTextFunctions {

    val ALL: Map<String, A2uiFunction> = mapOf(
        "upper" to A2uiFunction { args, _ -> text(args, 0)?.uppercase()?.let(::JsonPrimitive) },
        "lower" to A2uiFunction { args, _ -> text(args, 0)?.lowercase()?.let(::JsonPrimitive) },
        "join" to A2uiFunction { args, _ -> join(args) },
        "default" to A2uiFunction { args, _ -> firstOrFallback(args) },
        "ifEmpty" to A2uiFunction { args, _ -> firstOrFallback(args) },
    )

    private fun join(args: List<JsonElement>): JsonElement? {
        val array: JsonArray = args.firstOrNull() as? JsonArray ?: return null
        val separator: String = text(args, 1) ?: ", "
        val parts: List<String> = array.mapNotNull { it.asText() }
        return JsonPrimitive(parts.joinToString(separator))
    }

    /** `default(value, fallback)` and `ifEmpty(value, whenEmpty, otherwise)` — the same rule. */
    private fun firstOrFallback(args: List<JsonElement>): JsonElement? {
        val first: JsonElement = args.firstOrNull() ?: return null
        return if (first.isBlank()) args.getOrNull(1) ?: JsonPrimitive("") else first
    }
}

/** The argument reader every function shares: a positional string, or nothing. */
internal fun text(args: List<JsonElement>, index: Int): String? =
    args.getOrNull(index)?.jsonPrimitive?.contentOrNull

private fun JsonElement.asText(): String? = jsonPrimitive.contentOrNull

private fun JsonElement.isBlank(): Boolean = jsonPrimitive.contentOrNull.isNullOrBlank()
