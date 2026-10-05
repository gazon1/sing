package com.singularity.todo.feature.genui.function

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * One piece of a parsed string template.
 *
 * A parsed template is kept rather than a rendered string because the data model is filled in after
 * the components arrive: a title that reads `${/user/name}` is correct as a template long before
 * `updateData` has told it whose name that is.
 */
sealed interface TemplateSegment {

    /** Text that is shown as written. */
    data class Literal(val text: String) : TemplateSegment

    /** A data-model path, absolute or relative. */
    data class Path(val path: String) : TemplateSegment

    /** A call to a client function. */
    data class Call(val name: String, val args: List<TemplateArg>) : TemplateSegment
}

/** One argument of a [TemplateSegment.Call]: either a literal or a nested expression. */
sealed interface TemplateArg {
    data class Literal(val value: JsonElement) : TemplateArg
    data class Nested(val segment: TemplateSegment) : TemplateArg
}

/** A template that could not be read, with the reason. */
data class TemplateError(val reason: String)

/**
 * The outcome of reading a template.
 *
 * A sealed type rather than `Result`, because the failure here is a description rather than an
 * exception: a model that wrote `${formatDat}` needs to be told the function does not exist, and
 * nothing about that is exceptional enough to belong in a Throwable.
 */
sealed interface TemplateParse {
    data class Ok(val segments: List<TemplateSegment>) : TemplateParse
    data class Invalid(val error: TemplateError) : TemplateParse
}

/**
 * Reads and renders the `${…}` templates that appear in string properties.
 *
 * The grammar is small on purpose — a path, or a call with positional or named arguments — because
 * every rule added here is a rule the model has to be told about, and a template language is the
 * easiest place to invent one that works on the happy path and surprises on the rest.
 *
 * ```
 * "Due ${formatRelative(/task/due)}"
 * "${upper(/user/name)} has ${countOf(/tasks)} tasks"
 * ```
 *
 * Arguments are positional. A named-argument form was considered and dropped: it doubles the
 * grammar to describe, lets a call bind its arguments in an order the callee does not see, and
 * every function here has at most three parameters — a model that misplaces one argument is a bug
 * worth catching at the client, not a case to be clever about.
 *
 * Parsing and rendering are separate steps for the same reason: a template is checked when the
 * message arrives, so an unknown function is reported to the model, and resolved when it is drawn,
 * because the data it reads has usually not arrived yet.
 */
object FormatString {

    /**
     * Splits [template] into segments.
     *
     * `$${` is an escape for a literal `${`. A regular expression cannot do this: the delimiters
     * nest, arguments may themselves be templates, and a message may contain a brace inside a
     * quoted argument.
     */
    fun parse(template: String): TemplateParse = Reader(template).read()

    /**
     * The scanner's mutable state, gathered so the loop below reads as the two decisions it is.
     *
     * A single function holding the buffer, the index and the accumulated segments has to branch
     * on the marker and then decide again what to do with it, which is how a hand-written scanner
     * ends up with a jump out of a nested conditional in four places.
     */
    private class Reader(private val template: String) {

        private val segments: MutableList<TemplateSegment> = mutableListOf()
        private val literal: StringBuilder = StringBuilder()
        private var index: Int = 0

        fun read(): TemplateParse {
            while (index < template.length) {
                val rejection: TemplateParse.Invalid? = if (atSubstitution()) readSubstitution() else readLiteral()
                if (rejection != null) return rejection
            }
            flush()
            return TemplateParse.Ok(segments)
        }

        /** One ordinary character into the pending literal. Never fails. */
        private fun readLiteral(): TemplateParse.Invalid? {
            literal.append(template[index])
            index += 1
            return null
        }

        /**
         * A `${`, or the `$${` escape for one.
         *
         * Returns null when the template is so far well-formed, and the reason when it is not —
         * the caller turns that into the whole outcome, so neither branch invents a value.
         */
        private fun readSubstitution(): TemplateParse.Invalid? {
            if (template[index + 1] == '$') {
                literal.append("\${")
                index += 3
                return null
            }
            flush()
            val end: Int = findClosingBrace(template, index + 1)
            if (end < 0) return TemplateParse.Invalid(TemplateError("unclosed \${ at position $index"))
            return when (val expression: Expression = readExpression(template.substring(index + 2, end))) {
                is Expression.Invalid -> TemplateParse.Invalid(expression.error)

                else -> {
                    segments += asSegment(expression)
                    index = end + 1
                    null
                }
            }
        }

        /** Whether the cursor is on `${`, or on the `$${` that escapes one. */
        private fun atSubstitution(): Boolean {
            if (template[index] != '$' || index + 1 >= template.length) return false
            val next: Char = template[index + 1]
            return next == '{' || (next == '$' && index + 2 < template.length && template[index + 2] == '{')
        }

        private fun flush() {
            if (literal.isNotEmpty()) segments += TemplateSegment.Literal(literal.toString())
            literal.clear()
        }
    }

    /** Returns true when [template] contains no template markers at all. */
    fun isPlain(template: String): Boolean = !template.contains("\${")

    /**
     * Checks a template against the function names a client actually has.
     *
     * Called when the message is parsed, so that a call to something that does not exist is
     * reported to the model instead of rendering as an empty string in front of a user who will
     * believe it.
     */
    fun validate(template: String, knownFunctions: Set<String>): TemplateError? {
        val parsed: TemplateParse = parse(template)
        if (parsed is TemplateParse.Invalid) return parsed.error
        val segments: List<TemplateSegment> = (parsed as TemplateParse.Ok).segments
        for (segment in segments) {
            if (segment !is TemplateSegment.Call) continue
            if (segment.name !in knownFunctions) {
                return TemplateError("'${segment.name}' is not a function of this client")
            }
        }
        return null
    }

    /** The index of the `}` closing the `{` at [open], or -1. */
    private fun findClosingBrace(text: String, open: Int): Int {
        var depth: Int = 0
        var index: Int = open
        while (index < text.length) {
            when (text[index]) {
                '{' -> depth += 1

                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
            index += 1
        }
        return -1
    }

    private fun readExpression(body: String): Expression {
        val trimmed: String = body.trim()
        if (trimmed.isEmpty()) return Expression.Invalid(TemplateError("empty \${}"))
        if (trimmed.contains('(')) {
            return readCall(trimmed)
        }
        return Expression.Path(trimmed)
    }

    private fun readCall(text: String): Expression {
        val open: Int = text.indexOf('(')
        if (open <= 0 || !text.endsWith(")")) {
            return Expression.Invalid(TemplateError("malformed call in '$text'"))
        }
        val name: String = text.substring(0, open).trim()
        if (!name.all { it.isLetterOrDigit() || it == '_' }) {
            return Expression.Invalid(TemplateError("'$name' is not a function name"))
        }
        val inner: String = text.substring(open + 1, text.length - 1)
        val args: List<TemplateArg> = splitArguments(inner).map { raw: String ->
            val trimmed: String = raw.trim()
            when {
                trimmed.startsWith("\${") && trimmed.endsWith("}") -> {
                    val nested: Expression = readExpression(trimmed.substring(2, trimmed.length - 1))
                    if (nested is Expression.Invalid) {
                        return Expression.Invalid(nested.error)
                    }
                    TemplateArg.Nested(asSegment(nested))
                }

                else -> TemplateArg.Literal(readLiteral(trimmed))
            }
        }
        return Expression.Call(name, args)
    }

    /** Splits on commas that are not inside a nested template or a quoted string. */
    private fun splitArguments(text: String): List<String> {
        val parts: MutableList<String> = mutableListOf()
        val current: StringBuilder = StringBuilder()
        var depth: Int = 0
        var quoted: Boolean = false

        for (character in text) {
            when {
                character == '"' -> {
                    quoted = !quoted
                    current.append(character)
                }

                character == '\$' && !quoted -> {
                    // A nested template keeps its own commas to itself.
                    depth += 1
                    current.append(character)
                }

                character == '}' && depth > 0 -> {
                    depth -= 1
                    current.append(character)
                }

                character == ',' && depth == 0 && !quoted -> {
                    parts += current.toString()
                    current.clear()
                }

                else -> current.append(character)
            }
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts
    }

    private fun readLiteral(text: String): JsonElement = when {
        text.startsWith("\"") && text.endsWith("\"") && text.length >= 2 ->
            JsonPrimitive(text.substring(1, text.length - 1))

        else -> JsonPrimitive(text)
    }

    private fun asSegment(expression: Expression): TemplateSegment = when (expression) {
        is Expression.Path -> TemplateSegment.Path(expression.path)
        is Expression.Call -> TemplateSegment.Call(expression.name, expression.args)
        is Expression.Invalid -> TemplateSegment.Literal("")
    }

    private sealed interface Expression {
        data class Path(val path: String) : Expression
        data class Call(val name: String, val args: List<TemplateArg>) : Expression
        data class Invalid(val error: TemplateError) : Expression
    }
}
