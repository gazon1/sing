package com.singularity.todo.feature.search.query

// Provenance: REWRITTEN from Orgzly (GPL-3.0) — this type was part of a ported sealed
//   hierarchy; reimplemented as this project's own domain model against
//   docs/specs/search-query-grammar.md per option A1. See docs/legal/PROVENANCE.md

/**
 * Thrown by [SingularityQueryParser] when the input string cannot be parsed.
 *
 * @param message Human-readable description of the parse error.
 * @param position The character index in the input string where the error was detected.
 */
class QueryParseException(override val message: String, val position: Int) :
    RuntimeException("Parse error at position $position: $message")
