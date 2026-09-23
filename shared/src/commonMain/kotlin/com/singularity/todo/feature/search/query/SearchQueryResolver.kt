package com.singularity.todo.feature.search.query

import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

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
 */
class DefaultSearchQueryResolver(private val tagLookup: TagLookup, private val projectLookup: ProjectLookup) :
    SearchQueryResolver {

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
            needsPostFilter = ctx.needsPostFilter,
            postFilter = postFilter,
            isOrPostFilter = ctx.isOrPostFilter,
        )
    }

    // ─── Context ─────────────────────────────────────────────────────────────

    private class ResolveContext(val userId: String) {
        var freeText: String = ""
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

        fun addText(t: String) {
            if (freeText.isNotBlank()) freeText += " "
            freeText += t
        }

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
            is Condition.HasText -> ctx.addText(c.text)

            is Condition.HasStatus -> {
                val status = c.status
                ctx.addPostFilter { task ->
                    when (status) {
                        TaskStatus.Active -> task.completedAt == null
                        TaskStatus.Completed -> task.completedAt != null
                        TaskStatus.All -> true
                    }
                }
            }

            is Condition.HasPriority -> {
                ctx.addPostFilter { task -> task.priority == c.priority }
            }

            is Condition.HasTag -> {
                val entity = tagLookup.findByName(ctx.userId, c.tagName)
                if (entity != null) {
                    ctx.resolvedTagIds.add(entity.id)
                    // Always add post-filter — DAO filter used when !hasNegation, else post-filter
                    ctx.addPostFilter { task ->
                        task.tags.any { it.value == entity.id }
                    }
                } else {
                    ctx.unknownTagNames.add(c.tagName)
                    // No tasks can match an unknown tag → always-fail filter
                    ctx.addPostFilter { false }
                }
            }

            is Condition.HasAllTags -> {
                val ids = mutableSetOf<String>()
                for (tagName in c.tagNames) {
                    val entity = tagLookup.findByName(ctx.userId, tagName)
                    if (entity != null) {
                        ids.add(entity.id)
                    } else {
                        ctx.unknownTagNames.add(tagName)
                    }
                }
                if (ids.isNotEmpty()) {
                    ctx.resolvedTagIds.addAll(ids)
                    // Always add post-filter
                    ctx.addPostFilter { task ->
                        ids.all { id -> task.tags.any { it.value == id } }
                    }
                } else if (c.tagNames.isNotEmpty()) {
                    // All tag names are unknown → no tasks can match
                    ctx.addPostFilter { false }
                }
            }

            is Condition.InProject -> {
                val entity = projectLookup.findByName(ctx.userId, c.name)
                if (entity != null) {
                    ctx.resolvedProjectId = entity.id
                    // Always add post-filter
                    ctx.addPostFilter { task -> task.projectId?.value == entity.id }
                } else {
                    ctx.unknownProjectNames.add(c.name)
                    ctx.addPostFilter { false }
                }
            }

            is Condition.Due -> {
                if (!c.interval.isNone) {
                    val today = todayInSystemZone()
                    val targetDate = today.plus(c.interval.days.toLong(), DateTimeUnit.DAY)
                    val range = computeDateRange(c.relation, targetDate)
                    if (range != null) {
                        ctx.dateRange = range
                    }
                }
                // Due date can't be expressed in DAO (only ByDateRange for scheduled date)
                // Post-filter by exact date when interval is precise
                if (!c.interval.isNone && c.relation == Relation.EQ) {
                    val today = todayInSystemZone()
                    val targetDate = today.plus(c.interval.days.toLong(), DateTimeUnit.DAY)
                    ctx.addPostFilter { task ->
                        task.dueDate == targetDate
                    }
                } else if (!c.interval.isNone) {
                    val today = todayInSystemZone()
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

            is Condition.Scheduled -> {
                // Scheduled date is not stored separately in our model — treat as dueDate
                if (!c.interval.isNone) {
                    val today = todayInSystemZone()
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

            is Condition.HasDescription -> {
                ctx.addPostFilter { task -> !task.description.isNullOrBlank() }
            }

            is Condition.IsPinned -> {
                ctx.addPostFilter { task -> task.isPinned }
            }

            is Condition.IsArchived -> {
                ctx.addPostFilter { task -> task.archivedAt != null }
            }

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
                // OR can't be expressed in DAO (DAO only has AND via multiple filters)
                // → all parts go to post-filter; mark OR so we combine with ANY semantics
                for (part in c.parts) {
                    resolveCondition(part, ctx)
                }
                ctx.isOrPostFilter = true
                ctx.needsPostFilter = true
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
