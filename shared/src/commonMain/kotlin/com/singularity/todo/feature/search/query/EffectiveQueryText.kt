package com.singularity.todo.feature.search.query

/**
 * The query text a saved search should carry.
 *
 * The user's own text is kept whenever it still says the same thing as the
 * query the screen runs — a query typed in full is saved as it was typed, with
 * its `due:3d` and its `limit:` intact, because the text language says more
 * than a filter can hold.
 *
 * When the text and the running query have come apart — the filters were
 * cleared, so the screen runs the free text alone — the text is written from
 * the filter that runs, so that the saved search says what the screen does.
 *
 * @param raw the text in the field.
 * @param executed the query the screen runs, or `null` when it runs none yet.
 * @param activeFilter the filter that produced the running query, if any.
 * @param parse reads a query out of text.
 */
fun effectiveQueryText(
    raw: String,
    executed: Query?,
    activeFilter: SimpleFilter?,
    parse: (String) -> Query,
): String {
    if (executed == null) return raw
    val fromText = runCatching { parse(raw) }.getOrNull()
    if (fromText == executed) return raw
    return activeFilter?.toQueryText() ?: raw
}
