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

/**
 * The in-memory stand-in for an FTS `MATCH` expression.
 *
 * The expression the production code builds is a conjunction of prefix tokens
 * (`quarterly* report*`), or a disjunction of such conjunctions
 * (`quarterly* OR annual*`). This reads it back the way the index would:
 * every token of a group has to be the beginning of one of the record's words,
 * and one group matching is enough.
 */
internal fun matchesFtsExpression(expression: String, texts: List<String?>): Boolean {
    val groups = expression.split(" OR ").map { group ->
        group.split(" ").filter { it.isNotEmpty() }.map { token -> token.removeSuffix("*") }
    }
    return groups.any { group ->
        group.all { token ->
            texts.any { text ->
                TextMatch.tokens(text.orEmpty()).any { word -> word.startsWith(token) }
            }
        }
    }
}
