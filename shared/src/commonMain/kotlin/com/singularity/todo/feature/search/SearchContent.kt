package com.singularity.todo.feature.search

/**
 * What the search screen shows under the query field.
 *
 * Four states, not two. "Nothing has been searched yet", "the search is still
 * running" and "the search found nothing" are different products: the first
 * invites the user to type, the second says the question is being asked, and
 * the third tells them the search ran and the answer was empty. Collapsing them
 * into one blank state loses the answer, and the Maestro flows assert them
 * separately (`search_empty_placeholder` vs `search_empty_results`).
 */
sealed interface SearchContent {
    /** Nothing has been typed yet — the screen invites the user to search. */
    data object IdlePrompt : SearchContent

    /**
     * The query has been stated and the search has not answered yet.
     *
     * The search is debounced and then run, and the screen knows the difference
     * between "the answer is empty" and "the answer has not come yet". Without
     * this state the screen answers a question nobody asked: the first letter
     * of a query would be reported as a search that found nothing.
     */
    data object Searching : SearchContent

    /** The query was executed and matched no task, note, project or tag. */
    data object NoResults : SearchContent

    /** The query matched at least one task, note, project or tag. */
    data object Results : SearchContent
}

/**
 * Derives what the search screen shows from the query, the results and whether
 * a search is in flight.
 *
 * The idle prompt wins over everything else: a blank query is not a query, and
 * the results of the previous one must not linger under it.
 *
 * The pending search wins over the results: the results of the *previous* query
 * are not the answer to the query on the screen, and neither is an empty result
 * set — the query has not run yet, so there is no answer to report. A
 * non-empty query whose search has run and matched nothing is
 * [SearchContent.NoResults] — never the idle prompt, which would tell the user
 * to type a query they have already typed.
 *
 * @param query The raw query string as entered.
 * @param results The results of the last executed search.
 * @param isSearching True while a search for the current query is in flight.
 */
fun searchContent(
    query: String,
    results: SearchResults,
    isSearching: Boolean,
): SearchContent =
    when {
        query.isBlank() -> SearchContent.IdlePrompt
        isSearching -> SearchContent.Searching
        results.isEmpty -> SearchContent.NoResults
        else -> SearchContent.Results
    }
