package com.singularity.todo.feature.search.query

/**
 * Thrown by [SingularityQueryParser] when the input string cannot be parsed.
 *
 * @param message Human-readable description of the parse error.
 * @param position The character index in the input string where the error was detected.
 */
class QueryParseException(
    override val message: String,
    val position: Int,
) : RuntimeException("Parse error at position $position: $message")
