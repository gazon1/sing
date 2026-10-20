package com.singularity.todo.feature.search

/**
 * What the search screen shows under the query field.
 *
 * Three states, not two. "Nothing has been searched yet" and "the search found
 * nothing" are different products: the first invites the user to type, the second
 * tells them the search ran and the answer was empty. Collapsing them into one
 * blank state loses the answer, and the Maestro flows assert them separately
 * (`search_empty_placeholder` vs `search_empty_results`).
 */
sealed interface SearchContent {

    /** Nothing has been typed yet — the screen invites the user to search. */
    data object IdlePrompt : SearchContent

    /** The query was executed and matched no task, note, project or tag. */
    data object NoResults : SearchContent

    /** The query matched at least one task, note, project or tag. */
    data object Results : SearchContent
}

/**
 * Derives what the search screen shows from the query and the results.
 *
 * The idle prompt wins over the results: a blank query is not a query, and the
 * results of the previous one must not linger under it. A non-empty query with no
 * results is [SearchContent.NoResults] — never the idle prompt, which would tell
 * the user to type a query they have already typed.
 *
 * @param query The raw query string as entered.
 * @param results The results of the last executed search.
 */
fun searchContent(query: String, results: SearchResults): SearchContent = when {
    query.isBlank() -> SearchContent.IdlePrompt
    results.isEmpty -> SearchContent.NoResults
    else -> SearchContent.Results
}
