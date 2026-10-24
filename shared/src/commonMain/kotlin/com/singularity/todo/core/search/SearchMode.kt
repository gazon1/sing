package com.singularity.todo.core.search

/**
 * The mode the free text of a query is matched in — stated by the surface that
 * runs the search, and not by the query.
 *
 * ## The two modes
 *
 * - **Word** — a term matches a word of the searchable text by its beginning
 *   (F2a). "arch" finds "architecture"; "port" does not find "airport". This
 *   is the mode the full-text index answers, and the default.
 * - **Contains** — a term matches when it occurs *anywhere* in the searchable
 *   text, case-insensitively. "port" finds "airport". The index cannot answer
 *   this question — it stores words, not character positions — so the mode
 *   reads the rows and filters them in memory.
 *
 * ## Why the mode is not part of the query
 *
 * The query language has no marker for the mode, and a query never carries
 * one. A marker would mean two things at once — `*` is the index's own prefix
 * syntax — and it would let one query mix the two rules, one term by
 * beginning and another by occurrence. The mode changes the rule for *every*
 * term of the query at once: a query asks one question, and the mode says
 * which question.
 *
 * ## Why the mode lives here and not in the screen
 *
 * The mode used to live in `feature/search/query`, because the screen that
 * owns the switch owned the type. But the rule has to know the mode: the
 * question "what can the index do with this rule?" has a different answer in
 * the contains mode than in the word mode, and the rule is the one that
 * answers it — see `FreeTextRule.plan(mode)`. A type the rule needs cannot
 * live outside the rule's own package.
 *
 * The switch stays with the screen. The screen owns the switch and the
 * sentence that says what the mode costs; this type owns the rule that
 * answers the question, and every kind of record is answered by it — a task
 * by its title and its description, a note by its title, a project and a tag
 * by their name (F3).
 *
 * F6 — the mode is stated by the surface, and it changes the rule for every term.
 *
 * ## Why the mode is a property of every surface
 *
 * The mode was a property of one screen. The other surfaces — the note
 * search, the link autocompletion, the task filter — went on without one,
 * and the difference between them was silent: the search screen could answer
 * a question the others could not, and nothing said so.
 *
 * F8 — every search surface states the mode it searches in.
 *
 * Every surface states one, and the rule of the mode is one rule for every
 * kind of record, so a surface that states a mode states the same mode the
 * search screen states. A surface that does not offer the switch states the
 * mode it searches in; that is a different thing from a surface whose mode is
 * an accident of where it happens to be written.
 *
 * See `docs/decisions/2026-10-22-the-substring-search-is-a-mode-the-surface-states.md`
 * and `docs/decisions/2026-10-23-the-mode-is-a-property-of-every-search-surface.md`.
 */
enum class SearchMode {

    /** The word-prefix rule — the rule of the index. */
    Word,

    /** The escape-hatch: the term matches wherever it occurs. */
    Contains,
    ;

    /**
     * True when [term] matches [text] under this mode's rule.
     *
     * @param text the searchable text of a record; `null` matches nothing.
     * @param term the term to look for.
     */
    fun matches(text: String?, term: String): Boolean = when (this) {
        Word -> TextMatch.matches(text, term)
        Contains -> TextMatch.contains(text, term)
    }

    /**
     * True when [term] matches [record] under this mode's rule.
     *
     * The record says which of its surfaces it is searched by (F3), and the
     * mode says how a surface is matched — so a caller asks one question
     * instead of two, and cannot answer it about the wrong text.
     *
     * @param record the record to match.
     * @param term the term to look for.
     */
    fun matches(record: Searchable, term: String): Boolean =
        matchesAny(record.searchableSurfaces, term)

    /**
     * True when [term] matches any of [texts] under this mode's rule.
     *
     * A record has several searchable surfaces (F3), and a match on any of
     * them is a match.
     *
     * @param texts the searchable texts of a record.
     * @param term the term to look for.
     */
    fun matchesAny(texts: Iterable<String?>, term: String): Boolean =
        texts.any { text -> matches(text, term) }
}
