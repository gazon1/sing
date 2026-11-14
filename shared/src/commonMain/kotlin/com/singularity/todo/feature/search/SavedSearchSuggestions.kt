package com.singularity.todo.feature.search

import com.singularity.todo.core.search.TextMatch
import com.singularity.todo.feature.search.domain.SavedSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * The saved searches offered beside the search field for [query].
 *
 * A blank query offers all of them: the field holds nothing, so there is nothing
 * to narrow them by. A stated query narrows them by the rule the contains mode
 * states — the term occurs, case-insensitively, in the saved search's name or in
 * the query it stores. The term is compared with the query *text*, not with the
 * parsed query: the suggestion answers "which of my searches is this", and the
 * user is typing the words of a name as readily as the words of a query.
 *
 * The same rule is what makes `is:overdue` offered for `over` and `Due this week`
 * offered for `week`.
 *
 * The work is a read of the list the screen already observes — no repository, no
 * query per keystroke, nothing to wait for. The order is the list's own (the
 * repository reads them by name), so a suggestion never moves while the user
 * types.
 *
 * @param savedSearches every saved search of the profile, as [SearchUiState] holds them.
 * @param query the text in the search field, or the one a saved search seeded.
 */
fun savedSearchSuggestions(
    savedSearches: ImmutableList<SavedSearch>,
    query: String,
): ImmutableList<SavedSearch> {
    val term = query.trim()
    if (term.isEmpty()) return savedSearches
    return savedSearches
        .filter { saved -> TextMatch.contains(saved.name, term) || TextMatch.contains(saved.queryString, term) }
        .toImmutableList()
}
