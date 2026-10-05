package com.singularity.todo.feature.genui.function

import com.singularity.todo.feature.genui.schema.DataModel
import com.singularity.todo.feature.genui.schema.UiPath
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Templates are read twice: once when the message arrives, to tell a model that it called something
 * this client does not have, and once when the string is drawn, because the data it names arrives
 * later than the component that shows it.
 */
@Tag("fast")
class FormatStringTest {

    @Test
    fun aPlainStringHasNoSegments() {
        val segments = segmentsOf("just text")
        assertEquals(1, segments.size)
        assertEquals(TemplateSegment.Literal("just text"), segments.single())
    }

    @Test
    fun aPathIsRecognised() {
        val segments = segmentsOf("${'$'}{/task/due}")
        assertEquals(1, segments.size)
        assertEquals(TemplateSegment.Path("/task/due"), segments.single())
    }

    @Test
    fun textAroundAMarkerKeepsTheText() {
        val segments = segmentsOf("Due ${'$'}{/task/due}")
        assertEquals(2, segments.size, segments.toString())
        assertEquals(TemplateSegment.Literal("Due "), segments.first())
    }

    @Test
    fun aCallWithLiteralsIsRecognised() {
        val segments = segmentsOf("${'$'}{upper(hello)}")
        val call = segments.single() as TemplateSegment.Call
        assertEquals("upper", call.name)
        assertEquals(1, call.args.size)
        assertEquals(TemplateArg.Literal(JsonPrimitive("hello")), call.args.single())
    }

    @Test
    fun severalArgumentsAreKeptInOrder() {
        val segments = segmentsOf("${'$'}{join(one, two, three)}")
        val call = segments.single() as TemplateSegment.Call
        assertEquals(3, call.args.size, call.args.toString())
        assertEquals(TemplateArg.Literal(JsonPrimitive("one")), call.args[0])
        assertEquals(TemplateArg.Literal(JsonPrimitive("three")), call.args[2])
    }

    @Test
    fun aCommaInsideAnArgumentDoesNotSplitIt() {
        val segments = segmentsOf("${'$'}{join(\"a,b\", \", \")}")
        val call = segments.single() as TemplateSegment.Call
        assertEquals(2, call.args.size, call.args.toString())
    }

    @Test
    fun aNestedTemplateStaysOneArgument() {
        val segments = segmentsOf("${'$'}{formatRelative(${'$'}{/task/due})}")
        val call = segments.single() as TemplateSegment.Call
        val argument = call.args.single() as TemplateArg.Nested
        assertEquals(TemplateSegment.Path("/task/due"), argument.segment)
    }

    @Test
    fun aFunctionCallNextToTextKeepsBoth() {
        val segments = segmentsOf("Due ${'$'}{formatRelative(${'$'}{/due})} — hurry")
        assertEquals(3, segments.size, segments.toString())
    }

    @Test
    fun anEscapedMarkerIsLiteralText() {
        val segments = segmentsOf("cost: ${'$'}${'$'}{x}")
        assertEquals(1, segments.size, segments.toString())
        assertEquals(TemplateSegment.Literal("cost: \${x}"), segments.single())
    }

    @Test
    fun anUnclosedMarkerIsAnError() {
        val parsed = FormatString.parse("broken ${'$'}{upper(")
        assertTrue(parsed is TemplateParse.Invalid, parsed.toString())
    }

    @Test
    fun anUnknownFunctionIsReportedAtParseTime() {
        val error = FormatString.validate("${'$'}{formatDat(x)}", setOf("formatDate"))
        assertNotNull(error, "A function this client does not have must not reach the screen")
        assertTrue("formatDat" in error.reason, error.reason)
    }

    @Test
    fun aKnownFunctionPassesValidation() {
        assertNull(FormatString.validate("${'$'}{formatDate(${'$'}{/d})}", setOf("formatDate")))
    }

    private fun segmentsOf(template: String): List<TemplateSegment> {
        val parsed: TemplateParse = FormatString.parse(template)
        assertTrue(parsed is TemplateParse.Ok, "$template did not parse: $parsed")
        return (parsed as TemplateParse.Ok).segments
    }
}

/** The functions themselves, including the date ones that reuse the project's formatters. */
@Tag("fast")
class BuiltinFunctionsTest {

    private val registry = A2uiFunctionRegistry()
    private val today = LocalDate(2026, 10, 5)

    @Test
    fun theRegistryOffersExactlyWhatTheCatalogueDeclares() {
        val declared = com.singularity.todo.feature.genui.catalog.SingularityCatalog.functions.keys
        assertEquals(declared.sorted(), registry.names.sorted(), "A function the model is told about must exist")
    }

    @Test
    fun relativeFormattingUsesTheProjectsOwnWords() {
        val result = call("formatRelative", listOf(JsonPrimitive("2026-10-05")))
        assertEquals("Today", result)
    }

    @Test
    fun tomorrowIsNotToday() {
        assertEquals("Tomorrow", call("formatRelative", listOf(JsonPrimitive("2026-10-06"))))
    }

    @Test
    fun aDateThatCannotBeParsedYieldsNothingRatherThanAWrongDate() {
        assertNull(registry.call("formatDate", listOf(JsonPrimitive("not a date")), context()))
    }

    @Test
    fun numbersAreGrouped() {
        assertEquals("1 234 567", call("formatNumber", listOf(JsonPrimitive(1234567))))
    }

    @Test
    fun caseFoldingWorks() {
        assertEquals("ADA", call("upper", listOf(JsonPrimitive("Ada"))))
        assertEquals("ada", call("lower", listOf(JsonPrimitive("Ada"))))
    }

    @Test
    fun defaultFillsOnlyAnEmptyValue() {
        assertEquals("fallback", call("default", listOf(JsonPrimitive(""), JsonPrimitive("fallback"))))
        assertEquals("value", call("default", listOf(JsonPrimitive("value"), JsonPrimitive("fallback"))))
    }

    @Test
    fun anUnknownFunctionYieldsNothing() {
        assertNull(registry.call("nope", listOf(JsonPrimitive("x")), context()))
    }

    @Test
    fun aFunctionSeesTheDataModelItWasGiven() {
        val model = DataModel()
        model.set(UiPath.parse("/user/name"), JsonPrimitive("Ada"))
        val reads = A2uiFunctionRegistry(
            mapOf("whoami" to A2uiFunction { _, ctx -> ctx.dataModel.get(UiPath.parse("/user/name")) }),
        )
        val result = reads.call("whoami", emptyList(), A2uiFunctionContext(today, model))
        assertEquals("Ada", assertNotNull(result).jsonPrimitive.content)
    }

    private fun call(name: String, args: List<kotlinx.serialization.json.JsonElement>): String? =
        registry.call(name, args, context())?.let { (it as JsonPrimitive).content }

    private fun context(): A2uiFunctionContext = A2uiFunctionContext(today, DataModel())
}
