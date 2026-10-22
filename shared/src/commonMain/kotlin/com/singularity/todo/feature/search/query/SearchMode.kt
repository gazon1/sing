package com.singularity.todo.feature.search.query

import com.singularity.todo.core.search.TextMatch
import com.singularity.todo.core.search.TextMatchPlan
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.Task

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
 * ## Why the mode is here and not in the UI
 *
 * The surface states the mode; the rule is the domain's business. The screen
 * owns the switch and the sentence that says what the mode costs; this type
 * owns the rule that answers the question, and every kind of record is
 * answered by it — a task by its title and its description, a note by its
 * title, a project and a tag by their name (F3).
 *
 * See `docs/decisions/2026-10-22-the-substring-search-is-a-mode-the-surface-states.md`.
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
     * True when [term] matches [task] under this mode's rule.
     *
     * A task is searched by its title and its description (F3) — the surfaces
     * the task domain names, so that the rule of the mode and the rule of the
     * task list cannot drift apart on what a task is searched by.
     *
     * @param task the task to look at.
     * @param term the term to look for.
     */
    fun matches(task: Task, term: String): Boolean =
        TaskDomain.searchableText(task).any { text -> matches(text, term) }

    /**
     * What the full-text index can do for [rule] under this mode.
     *
     * F6 — the mode is stated by the surface, and it changes the rule for every term.
     *
     * The word mode is answered by the index, and the rule itself says what
     * the index can answer ([FreeTextRule.plan]). The contains mode is
     * answered by nobody: the index stores words, not character positions, so
     * a search in this mode reads the rows — the plan that says so is
     * [TextMatchPlan.BroadScan], the same plan the negated-only rule uses.
     *
     * @param rule the free-text rule of the query.
     */
    fun plan(rule: FreeTextRule): TextMatchPlan = when (this) {
        Word -> rule.plan()
        Contains -> TextMatchPlan.BroadScan
    }
}
