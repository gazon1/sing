package com.singularity.todo.feature.search.query

/**
 * Maps between the structured AST [Query] and the flat UI [SimpleFilter].
 *
 * Round-trip limitations (documented in [SimpleFilter]):
 * - `Not(...)` conditions are dropped
 * - `Or` trees are not representable
 * - `IsArchived` has no UI toggle
 * - `Scheduled` conditions are dropped
 * - Complex date relations (`GT`, `LT`, `NE`) are rounded to TODAY
 */
class SimpleFilterMapper {

    // ─── Query → SimpleFilter ───────────────────────────────────────────────

    /**
     * Converts a [Query] to a [SimpleFilter].
     * Returns [SimpleFilter.isEmpty] (all defaults) when the query has no mappable conditions.
     *
     * @throws UnsupportedSimpleFilterException when the query contains conditions
     *         that cannot be expressed in [SimpleFilter] (OR trees, negation, archived, etc.)
     */
    fun fromQuery(query: Query): Result<SimpleFilter> = runCatching {
        val c = query.condition ?: return@runCatching SimpleFilter(
            sortOrder = query.sortOrder,
            sortDescending = query.sortDescending,
        )

        val filter = SimpleFilterBuilder()
        var hadUnsupported = false

        flattenCondition(c).forEach { atom ->
            when (atom) {
                is Condition.HasText -> {
                    val existing = filter.build().freeText
                    filter.freeText(listOfNotNull(existing, atom.text).joinToString(" ").trim().ifEmpty { null })
                }

                is Condition.HasStatus -> {
                    filter.state(atom.status)
                }

                is Condition.HasPriority -> {
                    filter.priority(atom.priority)
                }

                is Condition.HasTag -> {
                    filter.tag(atom.tagName)
                }

                is Condition.HasAllTags -> {
                    atom.tagNames.forEach { filter.tag(it, matchAll = true) }
                }

                is Condition.InProject -> {
                    filter.project(atom.name)
                }

                is Condition.Due -> {
                    val mapped = mapDueCondition(atom.interval, atom.relation)
                    if (mapped != null) {
                        filter.due(mapped)
                    } else {
                        // Complex relation → downgrade to TODAY
                        filter.due(SimpleFilter.DueCondition.TODAY)
                    }
                }

                is Condition.Scheduled -> {
                    hadUnsupported = true
                }

                is Condition.HasDescription -> {
                    filter.hasDescription(true)
                }

                is Condition.IsPinned -> {
                    filter.pinned(true)
                }

                is Condition.IsArchived -> {
                    hadUnsupported = true
                }

                is Condition.Not -> {
                    hadUnsupported = true
                }

                is Condition.And -> {
                    // Already flattened; should not appear here
                }

                is Condition.Or -> {
                    hadUnsupported = true
                }
            }
        }

        if (hadUnsupported) {
            throw UnsupportedSimpleFilterException(
                "Query contains conditions not representable in SimpleFilter: " +
                    "OR, NOT, Archived, or Scheduled",
            )
        }

        filter.sortOrder(query.sortOrder)
        filter.sortDescending(query.sortDescending)
        filter.build()
    }

    private fun mapDueCondition(interval: QueryInterval, relation: Relation): SimpleFilter.DueCondition? {
        // Only EQ and simple relative intervals are representable
        if (relation != Relation.EQ) return null
        if (interval.isNone) return SimpleFilter.DueCondition.NONE

        val days = interval.days
        return when {
            days == 0 -> SimpleFilter.DueCondition.TODAY
            days == 1 -> SimpleFilter.DueCondition.TOMORROW
            days in 2..7 -> SimpleFilter.DueCondition.THIS_WEEK
            days < 0 -> SimpleFilter.DueCondition.OVERDUE
            else -> null
        }
    }

    // ─── SimpleFilter → Query ──────────────────────────────────────────────

    /**
     * Converts a [SimpleFilter] to a [Query].
     *
     * @throws UnsupportedSimpleFilterException when [SimpleFilter.matchAllTags] is true
     *         with more than one tag (UI doesn't expose matchAll for multiple tags yet),
     *         or when [SimpleFilter.due] is [SimpleFilter.DueCondition.CUSTOM]
     *         (date ranges require custom interval syntax not yet supported).
     */
    fun toQuery(filter: SimpleFilter): Result<Query> = runCatching {
        val conditions = mutableListOf<Condition>()

        // States (OR of active/completed); null means no state filter
        filter.states?.forEach { status ->
            conditions.add(Condition.HasStatus(status))
        }

        // Priorities (OR between priorities)
        filter.priorities.forEach { priority ->
            conditions.add(Condition.HasPriority(priority))
        }

        // Tags
        if (filter.tagNames.isNotEmpty()) {
            if (filter.matchAllTags && filter.tagNames.size > 1) {
                conditions.add(Condition.HasAllTags(filter.tagNames))
            } else {
                filter.tagNames.forEach { tag ->
                    conditions.add(Condition.HasTag(tag))
                }
            }
        }

        // Project
        filter.projectName?.let { name ->
            conditions.add(Condition.InProject(name))
        }

        // Due
        val dueInterval = when (filter.due) {
            SimpleFilter.DueCondition.TODAY -> QueryInterval.NOW

            SimpleFilter.DueCondition.TOMORROW -> QueryInterval.TOMORROW

            SimpleFilter.DueCondition.THIS_WEEK -> QueryInterval(7)

            SimpleFilter.DueCondition.OVERDUE -> QueryInterval(-1)

            SimpleFilter.DueCondition.NONE -> QueryInterval.NONE

            SimpleFilter.DueCondition.CUSTOM -> {
                throw UnsupportedSimpleFilterException(
                    "Custom date ranges are not yet supported in Query syntax",
                )
            }
        }
        if (dueInterval != QueryInterval.NONE) {
            conditions.add(Condition.Due(dueInterval, Relation.EQ))
        }

        // Has description
        if (filter.hasDescription == true) {
            conditions.add(Condition.HasDescription)
        }

        // Pinned
        if (filter.pinned == true) {
            conditions.add(Condition.IsPinned)
        }

        // Free text
        filter.freeText?.takeIf { it.isNotBlank() }?.let { text ->
            conditions.add(Condition.HasText(text))
        }

        // Wrap multiple conditions in AND; null when no conditions (empty query)
        val condition: Condition? = when {
            conditions.isEmpty() -> null
            conditions.size == 1 -> conditions.first()
            else -> Condition.And(conditions)
        }

        Query(
            condition = condition,
            sortOrder = filter.sortOrder,
            sortDescending = filter.sortDescending,
            options = Options(),
        )
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    /**
     * Flattens a Condition tree into a list of "atomic" conditions,
     * expanding AND nodes recursively. OR/Not nodes are kept as-is.
     */
    private fun flattenCondition(c: Condition): List<Condition> = when (c) {
        is Condition.And -> c.parts.flatMap { flattenCondition(it) }
        else -> listOf(c)
    }
}

/**
 * Thrown when a [Query] or [SimpleFilter] contains conditions that cannot
 * be expressed in the target representation.
 */
class UnsupportedSimpleFilterException(message: String) : RuntimeException(message)
