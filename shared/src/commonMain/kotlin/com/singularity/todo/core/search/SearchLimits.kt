package com.singularity.todo.core.search

/**
 * The ceilings a search answers with.
 *
 * The index has them in its SQL and the in-memory branches had them in their
 * code, and the two copies were the same number written twice — which is how
 * a search ends up answering twenty notes on one path and thirty on the other.
 *
 * The number belongs to the search, so it lives with the search.
 */
object SearchLimits {

    /**
     * How many notes a search answers with.
     *
     * The index's note query carries the same ceiling, and the in-memory
     * branch reads the rows itself — so it keeps the ceiling the index keeps.
     * A mode that says how the rows are matched must not say what the answer
     * looks like.
     */
    const val NOTES = 20
}
