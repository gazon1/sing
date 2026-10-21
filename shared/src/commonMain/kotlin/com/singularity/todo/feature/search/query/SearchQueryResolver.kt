package com.singularity.todo.feature.search.query

import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.time.Clock

/**
 * Interface for resolving a parsed [Query] against the database.
 * Implementations lookup tag/project names to IDs and build [TaskFilter] variants.
 */
interface SearchQueryResolver {
    /**
     * Resolves tag and project names in [query] to IDs and returns
     * a [ResolvedSearchQuery] ready for execution.
     *
     * @param query The parsed query to resolve.
     * @param userId The current user's scoped ID.
     */
    suspend fun resolve(query: Query, userId: String): ResolvedSearchQuery
}

/**
 * Looks up a tag by name for a specific user. Used by [SearchQueryResolver].
 */
interface TagLookup {
    suspend fun findByName(userId: String, name: String): TagLookupResult?
}

/** Result of a tag lookup — only the fields needed by the resolver. */
data class TagLookupResult(val id: String, val userId: String, val name: String)

/**
 * Looks up a project by name for a specific user. Used by [SearchQueryResolver].
 */
interface ProjectLookup {
    suspend fun findByName(userId: String, name: String): ProjectLookupResult?
}

/** Result of a project lookup — only the fields needed by the resolver. */
data class ProjectLookupResult(val id: String, val userId: String, val name: String)

/**
 * Default implementation that looks up names via [TagLookup]/[ProjectLookup]
 * and builds [TaskFilter] variants for the task repository.
 *
 * Every condition the task repository cannot express becomes an in-memory
 * post-filter, free text included: a word that matches no condition rule is a
 * `HasText` condition on tasks, and a resolver that translated it for notes only
 * left the task list unfiltered.
 *
 * This resolver is also the owner of the free-text rule. It collects the free-text
 * terms of the query into a [FreeTextRule] — the terms, their polarity, and whether
 * the query makes them alternatives — and hands that one object to the use case, so
 * tasks, notes, projects and tags are all matched by the same rule. The rule used
 * to be per kind: tasks were matched per term, the other kinds against the joined
 * phrase.
 *
 * @param clock and [timeZone] are required, with no default. A `due:` condition is
 *   resolved against *today*, so a resolver that read the system clock would make
 *   every date search a function of the day the suite happened to run — which is
 *   what made `SEARCH-01` (#170 slice 7) unwritable rather than merely untested.
 *   A default here would be a default nobody overrides, because the one caller
 *   that can (`TasksDiModule`) is not the one that suffers.
 */
class DefaultSearchQueryResolver(
    private val tagLookup: TagLookup,
    private val projectLookup: ProjectLookup,
    private val clock: Clock,
    private val timeZone: TimeZoneProvider,
) : SearchQueryResolver {

    override suspend fun resolve(query: Query, userId: String): ResolvedSearchQuery {
        val condition = query.condition ?: return ResolvedSearchQuery(
            taskFilter = null,
            dateRange = null,
            sortOrder = query.sortOrder,
            sortDescending = query.sortDescending,
            options = query.options,
        )

        val ctx = ResolveContext(userId)
        resolveCondition(condition, ctx)

        // The free-text rule is assembled after the walk, because whether the terms
        // are alternatives (an `OR` somewhere in the query) is only known then.
        val freeTextRule = ctx.freeTextRule()
        if (!freeTextRule.isEmpty) {
            // A free-text word is a condition on tasks, not only on notes, projects
            // and tags. The resolver used to translate it into nothing for tasks,
            // so the task flow came back unfiltered and the search screen listed
            // every task for a query that matched nothing. `today` is part of the
            // shared [TaskDomain.matchesFilter] signature and plays no part in a
            // text filter.
            val today = todayAt(clock, timeZone.current())
            ctx.addPostFilter { task ->
                freeTextRule.selects { term ->
                    TaskDomain.matchesFilter(task, TaskFilter.Search(term), today)
                }
            }
        }

        val taskFilter = buildTaskFilter(ctx)
        val dateRange = ctx.dateRange
        val postFilter = buildPostFilter(ctx)

        return ResolvedSearchQuery(
            taskFilter = taskFilter,
            dateRange = dateRange,
            resolvedTagIds = ctx.resolvedTagIds,
            unknownTagNames = ctx.unknownTagNames,
            resolvedProjectId = ctx.resolvedProjectId,
            unknownProjectNames = ctx.unknownProjectNames,
            sortOrder = query.sortOrder,
            sortDescending = query.sortDescending,
            options = query.options,
            freeText = ctx.freeText.takeIf { it.isNotBlank() },
            freeTextRule = freeTextRule,
            textMatch = textMatchFor(freeTextRule),
            needsPostFilter = ctx.needsPostFilter,
            postFilter = postFilter,
            isOrPostFilter = ctx.isOrPostFilter,
        )
    }

    /**
     * The narrowing the full-text index can do for [rule] — or `null` when it
     * cannot narrow it.
     *
     * The index answers positive questions only: it finds the records that carry
     * a term, and it has no way of expressing "must not carry". Three rules
     * follow from that:
     *
     * - a rule with no positive term has nothing to look up;
     * - a rule that *disjoins* a negated term with a positive one has nothing
     *   to look up either, because the records that match by *not* carrying the
     *   negated term are exactly the ones a positive lookup throws away;
     * - a rule that *requires* a negated term can still be narrowed: the index
     *   finds the records that carry the positive terms, and the rule then
     *   removes the ones that carry the negated one.
     */
    private fun textMatchFor(rule: FreeTextRule): TextMatchQuery? {
        val positive = rule.terms.filter { !it.negated }.map { it.text }
        if (positive.isEmpty()) return null
        if (rule.combinesWithOr && rule.terms.any { it.negated }) return null
        return TextMatchQuery(terms = positive, combinesWithOr = rule.combinesWithOr)
    }

    // ─── Context ─────────────────────────────────────────────────────────────

    private class ResolveContext(val userId: String) {
        var freeText: String = ""
        val freeTextTerms: MutableList<FreeTextRule.Term> = mutableListOf()
        val unknownTagNames: MutableSet<String> = mutableSetOf()
        val unknownProjectNames: MutableSet<String> = mutableSetOf()
        val resolvedTagIds: MutableSet<String> = mutableSetOf()
        var resolvedProjectId: String? = null
        var dateRange: ResolvedSearchQuery.DateRange? = null

        var needsPostFilter: Boolean = false
        val postFilterParts: MutableList<(Task) -> Boolean> = mutableListOf()

        /** Set to true when the top-level condition is an OR. */
        var isOrPostFilter: Boolean = false

        /** Nesting depth of Not conditions — used to negate post-filters correctly. */
        var negationDepth: Int = 0

        fun addText(t: String, negated: Boolean) {
            // F1 — the free text of a query is a list of terms. One term is one
            // `HasText` condition, and the terms are collected here and handed to the
            // rule as one object, so every kind of record is matched by the same
            // terms.
            if (freeText.isNotBlank()) freeText += " "
            freeText += t
            freeTextTerms += FreeTextRule.Term(text = t, negated = negated)
        }

        /**
         * The free-text rule this query states.
         *
         * The terms are alternatives when the query contains an `OR` (F4) — the same
         * connective the task post-filter uses to combine its predicates, so tasks
         * and the other kinds are combined the same way.
         */
        fun freeTextRule(): FreeTextRule =
            FreeTextRule(terms = freeTextTerms.toList(), combinesWithOr = isOrPostFilter)

        fun addPostFilter(p: (Task) -> Boolean) {
            // If inside a Not, negate the predicate before adding
            if (negationDepth > 0) {
                postFilterParts.add { task -> !p(task) }
            } else {
                postFilterParts.add(p)
            }
            needsPostFilter = true
        }
    }

    // ─── Condition → Context ─────────────────────────────────────────────────

    private suspend fun resolveCondition(c: Condition, ctx: ResolveContext) {
        when (c) {
            is Condition.HasText -> {
                // The word is free text, and the free-text rule is built from all of
                // them once the walk is over. The task predicate comes from the same
                // rule (see `resolve`), so a task and a note are matched by the same
                // terms — the polarity is the query's `NOT`, applied to the term.
                ctx.addText(c.text, negated = ctx.negationDepth > 0)
            }

            is Condition.HasStatus -> handleHasStatus(c, ctx)

            is Condition.HasPriority -> ctx.addPostFilter { task -> task.priority == c.priority }

            is Condition.HasTag -> handleHasTag(c, ctx)

            is Condition.HasAllTags -> handleHasAllTags(c, ctx)

            is Condition.InProject -> handleInProject(c, ctx)

            is Condition.Due -> handleDue(c, ctx)

            is Condition.Scheduled -> handleScheduled(c, ctx)

            is Condition.HasDescription -> ctx.addPostFilter { task -> !task.description.isNullOrBlank() }

            is Condition.IsPinned -> ctx.addPostFilter { task -> task.isPinned }

            is Condition.IsArchived -> ctx.addPostFilter { task -> task.archivedAt != null }

            is Condition.Not -> {
                ctx.negationDepth++
                resolveCondition(c.inner, ctx)
                ctx.negationDepth--
            }

            is Condition.And -> {
                for (part in c.parts) {
                    resolveCondition(part, ctx)
                }
            }

            is Condition.Or -> {
                for (part in c.parts) {
                    resolveCondition(part, ctx)
                }
                ctx.isOrPostFilter = true
                ctx.needsPostFilter = true
            }
        }
    }

    private suspend fun handleHasStatus(c: Condition.HasStatus, ctx: ResolveContext) {
        val status = c.status
        ctx.addPostFilter { task ->
            when (status) {
                TaskStatus.Active -> task.completedAt == null
                TaskStatus.Completed -> task.completedAt != null
                TaskStatus.All -> true
            }
        }
    }

    private suspend fun handleHasTag(c: Condition.HasTag, ctx: ResolveContext) {
        val entity = tagLookup.findByName(ctx.userId, c.tagName)
        if (entity != null) {
            ctx.resolvedTagIds.add(entity.id)
            ctx.addPostFilter { task -> task.tags.any { it.value == entity.id } }
        } else {
            // C3 — an unresolvable name is not a parse error. It resolves to no rows.
            ctx.unknownTagNames.add(c.tagName)
            ctx.addPostFilter { false }
        }
    }

    /**
     * C1 — `tag:` accumulates. Every name has to be on the task.
     *
     * C3 decides what an unresolvable name means here. The name is not a parse error,
     * but it *is* a name that cannot be satisfied, so the condition selects no rows —
     * not the rows that carry the names that did resolve. A query that names one tag
     * that exists and one that does not asks for tasks that carry both, and there are
     * none.
     *
     * The alternative — matching the tags that did resolve — is a filter that answers a
     * question the user did not ask, and it is the reason the single-tag path and the
     * accumulating path have to agree: `tag:work tag:missing` and `tags:work,missing`
     * are the same query, and one of them matching work-tasks while the other does not
     * would be a rule with two answers.
     */
    private suspend fun handleHasAllTags(c: Condition.HasAllTags, ctx: ResolveContext) {
        val ids = mutableSetOf<String>()
        var unresolved = false
        for (tagName in c.tagNames) {
            val entity = tagLookup.findByName(ctx.userId, tagName)
            if (entity != null) {
                ids.add(entity.id)
            } else {
                ctx.unknownTagNames.add(tagName)
                unresolved = true
            }
        }
        if (ids.isNotEmpty()) {
            ctx.resolvedTagIds.addAll(ids)
            ctx.addPostFilter { task -> ids.all { id -> task.tags.any { it.value == id } } }
        }
        if (unresolved) {
            // C3 — an unresolvable name is not a parse error. One unresolvable name
            // is enough: the condition matches nothing.
            ctx.addPostFilter { false }
        }
    }

    private suspend fun handleInProject(c: Condition.InProject, ctx: ResolveContext) {
        val entity = projectLookup.findByName(ctx.userId, c.name)
        if (entity != null) {
            ctx.resolvedProjectId = entity.id
            ctx.addPostFilter { task -> task.projectId?.value == entity.id }
        } else {
            // C3 — an unresolvable name is not a parse error. It resolves to no rows.
            ctx.unknownProjectNames.add(c.name)
            ctx.addPostFilter { false }
        }
    }

    private fun handleDue(c: Condition.Due, ctx: ResolveContext) {
        if (!c.interval.isNone) {
            val today = todayAt(clock, timeZone.current())
            val targetDate = today.plus(c.interval.days.toLong(), DateTimeUnit.DAY)
            val range = computeDateRange(c.relation, targetDate)
            if (range != null) {
                ctx.dateRange = range
            }
        }
        if (!c.interval.isNone && c.relation == Relation.EQ) {
            val today = todayAt(clock, timeZone.current())
            val targetDate = today.plus(c.interval.days.toLong(), DateTimeUnit.DAY)
            ctx.addPostFilter { task -> task.dueDate == targetDate }
        } else if (!c.interval.isNone) {
            val today = todayAt(clock, timeZone.current())
            val targetDate = today.plus(c.interval.days.toLong(), DateTimeUnit.DAY)
            ctx.addPostFilter { task ->
                val due = task.dueDate ?: return@addPostFilter false
                when (c.relation) {
                    Relation.LT -> due < targetDate
                    Relation.LE -> due <= targetDate
                    Relation.GT -> due > targetDate
                    Relation.GE -> due >= targetDate
                    Relation.EQ -> due == targetDate
                    Relation.NE -> due != targetDate
                }
            }
        }
    }

    private fun handleScheduled(c: Condition.Scheduled, ctx: ResolveContext) {
        if (!c.interval.isNone) {
            val today = todayAt(clock, timeZone.current())
            val targetDate = today.plus(c.interval.days.toLong(), DateTimeUnit.DAY)
            ctx.addPostFilter { task ->
                val due = task.dueDate ?: return@addPostFilter false
                when (c.relation) {
                    Relation.LT -> due < targetDate
                    Relation.LE -> due <= targetDate
                    Relation.GT -> due > targetDate
                    Relation.GE -> due >= targetDate
                    Relation.EQ -> due == targetDate
                    Relation.NE -> due != targetDate
                }
            }
        }
    }

    // ─── Date range helpers ──────────────────────────────────────────────────

    private fun computeDateRange(relation: Relation, targetDate: LocalDate): ResolvedSearchQuery.DateRange? =
        when (relation) {
            Relation.LE -> {
                // dueDate <= targetDate → range from 1970-01-01 to targetDate
                ResolvedSearchQuery.DateRange(
                    from = LocalDate(1970, 1, 1),
                    to = targetDate,
                )
            }

            Relation.GE -> {
                // dueDate >= targetDate → range from targetDate to far future
                ResolvedSearchQuery.DateRange(
                    from = targetDate,
                    to = LocalDate(2099, 12, 31),
                )
            }

            Relation.EQ -> {
                // dueDate == targetDate → single-day range
                ResolvedSearchQuery.DateRange(
                    from = targetDate,
                    to = targetDate,
                )
            }

            Relation.GT, Relation.LT, Relation.NE -> {
                // These need precise comparison which the DAO's range query can't express
                null
            }
        }

    // ─── TaskFilter builder ──────────────────────────────────────────────────

    private fun buildTaskFilter(ctx: ResolveContext): TaskFilter? {
        // DAO filter can always be built — negation only affects post-filter predicates
        val tagIds = ctx.resolvedTagIds.toSet()
        val projectId = ctx.resolvedProjectId
        val dateRange = ctx.dateRange

        return when {
            dateRange != null && projectId == null && tagIds.isEmpty() ->
                TaskFilter.ByDateRange(dateRange.from, dateRange.to)

            projectId != null -> TaskFilter.ByProject(
                com.singularity.todo.feature.projects.domain.model.ProjectId.fromString(projectId),
            )

            tagIds.isNotEmpty() -> {
                // Single tag → ByTag; multiple tags → ByTags matchAll
                if (tagIds.size == 1) {
                    TaskFilter.ByTag(TagId(tagIds.first()))
                } else {
                    TaskFilter.ByTags(tagIds.map { TagId(it) }.toSet(), matchAll = true)
                }
            }

            else -> null
        }
    }

    // ─── Post-filter builder ────────────────────────────────────────────────

    private fun buildPostFilter(ctx: ResolveContext): (Sequence<Task>) -> Sequence<Task> {
        val parts = ctx.postFilterParts.toList()
        if (parts.isEmpty()) return { it }

        val combiner: (Task) -> Boolean = if (ctx.isOrPostFilter) {
            { task -> parts.any { it(task) } }
        } else {
            { task -> parts.all { it(task) } }
        }
        return { tasks -> tasks.filter(combiner) }
    }
}
