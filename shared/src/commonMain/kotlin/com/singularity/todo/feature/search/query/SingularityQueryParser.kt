package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus

/**
 * Concrete query parser for the Singularity Todo search syntax.
 *
 * Grammar (operator precedence: NOT > AND > OR):
 * ```
 * query    → sortDirective* conditionExpr
 * conditionExpr → orExpr
 * orExpr   → andExpr (OR andExpr)*
 * andExpr  → atom (AND? atom)*    -- AND is optional between atoms
 * atom     → NOT? (word | quoted | '(' conditionExpr ')')
 * word     → state:val | priority:val | tag:val | tags:val | project:val
 *           | due:val | scheduled:val | has:description | pinned | archived
 *           | bare-text
 * ```
 *
 * Sort and option directives are extracted from **all positions** in the token stream,
 * then the remaining tokens are parsed as a condition expression.
 *
 * @param input Raw user-supplied query string.
 */
class SingularityQueryParser(input: String) : QueryParser(QueryTokenizer(input).tokens()) {

    override val conditionMatches = listOf(
        // ── Status ──────────────────────────────────────────────────────────
        ConditionMatch(Regex("""^-?state:(\w+)$""", RegexOption.IGNORE_CASE)) { m ->
            val status = parseStatus(m.groupValues[1])
            if (m.groupValues[0].startsWith("-")) Condition.Not(Condition.HasStatus(status))
            else Condition.HasStatus(status)
        },

        // ── Priority ─────────────────────────────────────────────────────────
        ConditionMatch(Regex("""^-?priority:(\w+)$""", RegexOption.IGNORE_CASE)) { m ->
            val priority = parsePriority(m.groupValues[1])
            if (m.groupValues[0].startsWith("-")) Condition.Not(Condition.HasPriority(priority))
            else Condition.HasPriority(priority)
        },

        // ── Single tag ────────────────────────────────────────────────────────
        ConditionMatch(Regex("""^-?tag:(\S+)$""")) { m ->
            val tagName = m.groupValues[1]
            if (m.groupValues[0].startsWith("-")) Condition.Not(Condition.HasTag(tagName))
            else Condition.HasTag(tagName)
        },

        // ── Multi-tag (AND) ────────────────────────────────────────────────────
        ConditionMatch(Regex("""^-?tags:(.+)$""")) { m ->
            val tagNames = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (m.groupValues[0].startsWith("-")) {
                Condition.Not(Condition.HasAllTags(tagNames))
            } else {
                Condition.HasAllTags(tagNames)
            }
        },

        // ── Project ───────────────────────────────────────────────────────────
        ConditionMatch(Regex("""^project:(\S+)$""")) { m ->
            Condition.InProject(m.groupValues[1])
        },

        // ── Due date ─────────────────────────────────────────────────────────
        ConditionMatch(Regex("""^due:(.+)$""", RegexOption.IGNORE_CASE)) { m ->
            parseDateCondition("due", m.groupValues[1])
        },

        // ── Scheduled date ────────────────────────────────────────────────────
        ConditionMatch(Regex("""^scheduled:(.+)$""", RegexOption.IGNORE_CASE)) { m ->
            parseDateCondition("scheduled", m.groupValues[1])
        },

        // ── Has description ───────────────────────────────────────────────────
        ConditionMatch(Regex("""^has:description$""", RegexOption.IGNORE_CASE)) {
            Condition.HasDescription
        },

        // ── Pinned ─────────────────────────────────────────────────────────────
        ConditionMatch(Regex("""^pinned$""", RegexOption.IGNORE_CASE)) {
            Condition.IsPinned
        },

        // ── Archived ───────────────────────────────────────────────────────────
        ConditionMatch(Regex("""^archived$""", RegexOption.IGNORE_CASE)) {
            Condition.IsArchived
        },
    )

    override val sortOrderMatches = listOf(
        SortOrderMatch(Regex("""^sort:priority$""", RegexOption.IGNORE_CASE), SortOrder.PRIORITY),
        SortOrderMatch(Regex("""^sort:due$""", RegexOption.IGNORE_CASE), SortOrder.DUE),
        SortOrderMatch(Regex("""^sort:title$""", RegexOption.IGNORE_CASE), SortOrder.TITLE),
        SortOrderMatch(Regex("""^sort:created$""", RegexOption.IGNORE_CASE), SortOrder.CREATED),
        SortOrderMatch(Regex("""^sort:updated$""", RegexOption.IGNORE_CASE), SortOrder.UPDATED),
    )

    override val optionMatches = listOf(
        OptionMatch(Regex("""^limit:(\d+)$""", RegexOption.IGNORE_CASE)) { m, opts ->
            opts.copy(limit = m.groupValues[1].toIntOrNull()?.coerceIn(1, 1000) ?: opts.limit)
        },
    )

    override fun parse(): Query {
        if (tokens.isEmpty()) return Query.EMPTY

        // ── Phase 1: scan all tokens, extract sort/option/desc ─────────────────
        var sortOrder = SortOrder.DUE
        var sortDescending = false
        var options = Options()
        val consumedIndices = mutableSetOf<Int>()

        for ((idx, tok) in tokens.withIndex()) {
            if (tok !is QueryTokenizer.Token.Word) continue

            // Check sort directives
            for (match in sortOrderMatches) {
                if (match.regex.matches(tok.text)) {
                    sortOrder = match.sortOrder
                    consumedIndices.add(idx)
                    break
                }
            }

            // Check desc flag
            if (idx !in consumedIndices && tok.text.equals("desc", ignoreCase = true)) {
                sortDescending = true
                consumedIndices.add(idx)
            }

            // Check option directives
            if (idx !in consumedIndices) {
                for (match in optionMatches) {
                    val mr = match.regex.find(tok.text)
                    if (mr != null) {
                        options = match.apply(mr, options)
                        consumedIndices.add(idx)
                        break
                    }
                }
            }
        }

        // ── Phase 2: parse condition from remaining tokens ─────────────────────
        val conditionTokens = tokens.filterIndexed { idx, _ -> idx !in consumedIndices }
        val conditionParser = ConditionExprParser(conditionTokens, conditionMatches) { prefix, spec ->
            parseDateCondition(prefix, spec)
        }
        val condition = conditionParser.parse()

        return Query(
            condition = condition,
            sortOrder = sortOrder,
            sortDescending = sortDescending,
            options = options,
        )
    }

    // ─── Value parsers ───────────────────────────────────────────────────────

    private fun parseStatus(s: String): TaskStatus = when (s.lowercase()) {
        "active" -> TaskStatus.Active
        "completed", "done" -> TaskStatus.Completed
        "all" -> TaskStatus.All
        else -> throw QueryParseException("Unknown status: '$s'. Expected: active, completed, all", 0)
    }

    private fun parsePriority(s: String): TaskPriority = when (s.lowercase()) {
        "none" -> TaskPriority.None
        "low" -> TaskPriority.Low
        "medium" -> TaskPriority.Medium
        "high" -> TaskPriority.High
        "urgent" -> TaskPriority.Urgent
        else -> throw QueryParseException("Unknown priority: '$s'. Expected: none, low, medium, high, urgent", 0)
    }

    private fun parseDateCondition(prefix: String, spec: String): Condition {
        val (relationStr, valueStr) = when {
            spec.startsWith(">=") -> ">=" to spec.substring(2)
            spec.startsWith("<=") -> "<=" to spec.substring(2)
            spec.startsWith(">") -> ">" to spec.substring(1)
            spec.startsWith("<") -> "<" to spec.substring(1)
            spec.startsWith("=") -> "=" to spec.substring(1)
            spec.startsWith("!=") -> "!=" to spec.substring(2)
            else -> "<=" to spec // default: "within N days"
        }

        val rel = when (relationStr) {
            ">" -> Relation.GT
            ">=" -> Relation.GE
            "<" -> Relation.LT
            "<=" -> Relation.LE
            "=" -> Relation.EQ
            "!=" -> Relation.NE
            else -> Relation.LE
        }

        val interval = QueryInterval.parse(valueStr)
            ?: throw QueryParseException("Unknown $prefix interval: '$valueStr'", 0)

        // `none` alias means "no date constraint" — skip the condition entirely
        if (interval.isNone) return Condition.And(emptyList())

        return when (prefix) {
            "due" -> Condition.Due(interval, rel)
            "scheduled" -> Condition.Scheduled(interval, rel)
            else -> Condition.And(emptyList())
        }
    }
}
