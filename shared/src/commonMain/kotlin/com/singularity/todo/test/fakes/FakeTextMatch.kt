package com.singularity.todo.test.fakes

import com.singularity.todo.core.search.TextMatch
import com.singularity.todo.core.search.TextMatchQuery

/**
 * The in-memory stand-in for the full-text index.
 *
 * The fake database has no index, so it answers a text query by reading the
 * text: a term matches a record when one of the record's words starts with the
 * term, and the terms are combined the way the query says — every term, or any
 * of them.
 *
 * This is the same rule the production index implements
 * ([com.singularity.todo.core.search.TextMatch]), so a test that passes here
 * is a test about the behaviour and not about the fake.
 *
 * ## Why there is no second implementation
 *
 * The fake used to answer a `MATCH` *expression* as well: it split the
 * expression back into tokens and matched those. That was a second
 * implementation of the rule — in a place where nothing compared it with the
 * first one — and it could only agree with the rule for as long as nobody
 * changed either. The fake answers the query surface now, and the expression
 * surface of the fake says loudly that it is not implemented.
 */
internal fun matchesTextMatch(query: TextMatchQuery, vararg texts: String?): Boolean {
    if (query.isEmpty) return false
    val results = query.terms.map { term -> TextMatch.matchesAny(texts.asList(), term) }
    return if (query.combinesWithOr) {
        results.any { it }
    } else {
        results.all { it }
    }
}
