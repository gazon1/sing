package com.singularity.todo.feature.search.query

/**
 * The records that carry the word in their title come before the ones that
 * carry it only in their body.
 *
 * ## Why the index cannot say this
 *
 * FTS4 has no `bm25` and no column weights: the index says *whether* a record
 * carries the word, not *how well* it matches. A record whose title is
 * "Architecture" and a record whose description mentions architecture are the
 * same match to the index, and they are not the same answer to the user — the
 * first one is what the user was looking for.
 *
 * So the ranking is done where the knowledge is: the rule knows which records
 * carry the word in their title, and the rule is the last word anyway.
 *
 * ## What it does to the order the user chose
 *
 * The partition is stable, and it happens after the sort. So the records that
 * carry the word in their title come first *as a group*, and inside each group
 * the order the user asked for is the order they get. A user who sorts by due
 * date still gets the due dates in order — inside the group of the records
 * that carry the word in their title, and inside the group of the ones that
 * do not.
 *
 * @param items the records, already in the order the user asked for.
 * @param carriesTheWordInItsTitle whether the record carries the word in its
 *   title, rather than only in its body.
 */
internal fun <T> titleMatchesFirst(
    items: List<T>,
    carriesTheWordInItsTitle: (T) -> Boolean,
): List<T> {
    val (first, rest) = items.partition { carriesTheWordInItsTitle(it) }
    return first + rest
}
