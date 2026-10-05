package com.singularity.todo.feature.genui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.feature.genui.function.A2uiFunctionContext
import com.singularity.todo.feature.genui.function.A2uiFunctionRegistry
import com.singularity.todo.feature.genui.function.FormatString
import com.singularity.todo.feature.genui.function.TemplateArg
import com.singularity.todo.feature.genui.function.TemplateParse
import com.singularity.todo.feature.genui.function.TemplateSegment
import com.singularity.todo.feature.genui.schema.DataModel
import com.singularity.todo.feature.genui.schema.UiPath
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The client functions available to the surface being drawn.
 *
 * A composition local rather than a constructor argument because the functions are needed at the
 * point of rendering, three or four frames below the composable that owns the surface, and threading
 * them through every container renderer to get there would be the same mistake as threading a whole
 * surface through every renderer.
 *
 * The default is the built-in set, so a surface rendered on its own — a preview, a test — behaves
 * exactly like one drawn by the application, with no wiring at all.
 */
val LocalGenuiFunctions = staticCompositionLocalOf { A2uiFunctionRegistry() }

/**
 * Resolves `${…}` templates, and the data the surface's model holds.
 *
 * Deliberately not a composable: a template has to be resolved at press time as well as at draw
 * time, because a button's payload is assembled when the user presses it — and by then nothing is
 * being composed. Capturing the two things that come from the composition (the functions and
 * today's date) and taking everything else as an argument is what lets one implementation serve
 * both, instead of two that drift.
 */
class TemplateResolver(
    private val functions: A2uiFunctionRegistry,
    private val today: LocalDate,
    private val read: (String) -> JsonElement?,
) {
    /**
     * Renders [template] as text.
     *
     * A value that is missing renders as nothing. Inventing a placeholder would put text the model
     * never wrote in front of a user who has no way to tell it apart from what was asked for.
     */
    fun text(template: String): String {
        if (FormatString.isPlain(template)) return template
        val parsed: TemplateParse = FormatString.parse(template)
        if (parsed is TemplateParse.Invalid) return template
        return (parsed as TemplateParse.Ok).segments.joinToString("") { resolve(it) }
    }

    /**
     * Resolves every template inside a JSON value.
     *
     * This is what makes "fill it in and send it" work: a button's payload is the only place a
     * surface assembles values the *user* produced, and without this the payload could only ever
     * contain what the model already knew. Fields the user typed exist in the data model and
     * nowhere else, so a surface that could not read them back could not submit them.
     *
     * A string that is exactly one path keeps the type that path holds — a number bound to
     * `data.value` submits as a number, not as its digits in a string. A string that *mixes* text
     * and a value has no such type and becomes text.
     */
    fun json(element: JsonElement?): JsonElement? = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value: JsonElement) -> json(value) ?: JsonNull })
        is JsonArray -> JsonArray(element.map { json(it) ?: JsonNull })
        is JsonPrimitive -> resolvePrimitive(element)
        else -> element
    }

    private fun resolvePrimitive(primitive: JsonPrimitive): JsonElement {
        if (!primitive.isString) return primitive
        val template: String = primitive.content
        if (FormatString.isPlain(template)) return primitive
        solePathOf(template)?.let { path: String -> return read(path) ?: JsonNull }
        return JsonPrimitive(text(template))
    }

    /** The path when [template] is nothing but one path reference, and null otherwise. */
    private fun solePathOf(template: String): String? {
        val parsed: TemplateParse = FormatString.parse(template)
        if (parsed !is TemplateParse.Ok || parsed.segments.size != 1) return null
        val segment: TemplateSegment = parsed.segments.single()
        return (segment as? TemplateSegment.Path)?.path
    }

    private fun resolve(segment: TemplateSegment): String = when (segment) {
        is TemplateSegment.Literal -> segment.text

        is TemplateSegment.Path -> read(segment.path).asText()

        is TemplateSegment.Call -> {
            val args: List<JsonElement> = segment.args.map { argument: TemplateArg ->
                when (argument) {
                    is TemplateArg.Literal -> argument.value
                    is TemplateArg.Nested -> JsonPrimitive(resolve(argument.segment))
                }
            }
            functions.call(segment.name, args, context()).asText()
        }
    }

    private fun context(): A2uiFunctionContext = A2uiFunctionContext(today, EMPTY_MODEL)
}

/**
 * Builds a resolver for this context.
 *
 * The read goes through [DataContext.dataModel] on every call rather than capturing one model, so
 * that a payload assembled after an edit sees the value the user just typed.
 */
@Composable
fun DataContext.templateResolver(): TemplateResolver {
    val functions: A2uiFunctionRegistry = LocalGenuiFunctions.current
    val today: LocalDate = remember(clock) { todayAt(clock, TimeZone.currentSystemDefault()) }
    return remember(functions, today, dataModel) {
        TemplateResolver(functions, today) { path: String -> dataModel?.get(UiPath.parse(path)) }
    }
}

/**
 * Renders a string that may contain `${…}` templates against this surface's data model.
 *
 * Resolution happens at draw time, not at parse time, because the data a template reads arrives in a
 * later message than the component that displays it. Parsing still happens at both ends: the syntax
 * and the function names are checked when the message arrives, so an unknown function is reported to
 * the model, and this is the second reading of a template already known to be well formed.
 */
@Composable
fun DataContext.resolveText(template: String): String = templateResolver().text(template)

private fun JsonElement?.asText(): String = this?.jsonPrimitive?.contentOrNull.orEmpty()

/** Shared read-only model for a function call, which reads no paths of its own. */
private val EMPTY_MODEL: DataModel = DataModel()
