package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import com.singularity.todo.feature.tags.TagId

/**
 * A section type the editor can offer, before its parameters are chosen.
 *
 * A [Selector] like `Selector.Tags(ids = …)` is meaningless without those ids —
 * an empty set matches nothing, so a section built from one renders as a
 * permanently empty header. That is why the editor could not offer these types at
 * all: it had a way to pick a *kind* of section but no way to pick the *values*.
 *
 * So the editor picks a [SelectorTemplate] first, and only templates that
 * [requiresParameters] open a second step where the user chooses values. The
 * result is a concrete [Selector] via [resolve].
 *
 * This is a pure domain type with no UI and no repositories: the options come
 * from the ViewModel, the resolution happens here, and both are testable
 * without a device.
 */
sealed interface SelectorTemplate {
    val label: String

    /**
     * Whether picking this template has to open a value picker.
     *
     * False for templates that are fully determined by their type — a date
     * bucket has no free parameters. Those add the section immediately.
     */
    val requiresParameters: Boolean

    /**
     * Builds the concrete selector from the ids the user chose.
     *
     * @param chosenIds raw ids from the option list. An empty selection resolves
     *   to `null` so the UI can refuse to add a section that would match nothing
     *   — `Selector.Tags(emptySet())` matches no task, and a section header that
     *   can never have content is worse than not offering it.
     */
    fun resolve(chosenIds: Set<String>): Selector?

    /** Fixed types — picking one adds the section with no second step. */
    data class Fixed(override val label: String, val selector: Selector) : SelectorTemplate {
        override val requiresParameters: Boolean = false
        override fun resolve(chosenIds: Set<String>): Selector = selector
    }

    /** Tags: any of the chosen tags (matchAny, the default). */
    data class ByTags(val matchAll: Boolean = false) : SelectorTemplate {
        override val label: String = "By tag"
        override val requiresParameters: Boolean = true
        override fun resolve(chosenIds: Set<String>): Selector? {
            val ids = chosenIds.mapNotNull { TagId.fromString(it) }.toSet()
            return if (ids.isEmpty()) null else Selector.Tags(ids, matchAll)
        }
    }

    /** Projects: tasks in any of the chosen projects. */
    data object ByProjects : SelectorTemplate {
        override val label: String = "By project"
        override val requiresParameters: Boolean = true
        override fun resolve(chosenIds: Set<String>): Selector? {
            val ids = chosenIds.mapNotNull { ProjectId.fromString(it) }.toSet()
            return if (ids.isEmpty()) null else Selector.Projects(ids)
        }
    }

    /** Priority threshold: at most (or at least) the chosen levels. */
    data class ByPriority(val atMost: Boolean = true) : SelectorTemplate {
        override val label: String = "By priority"
        override val requiresParameters: Boolean = true
        override fun resolve(chosenIds: Set<String>): Selector? {
            val levels = chosenIds.mapNotNull { key -> TaskPriority.entries.firstOrNull { it.name == key } }.toSet()
            return if (levels.isEmpty()) null else Selector.Priorities(levels, atMost)
        }
    }

    /** Status: active or completed. */
    data object ByStatus : SelectorTemplate {
        override val label: String = "By status"
        override val requiresParameters: Boolean = true
        override fun resolve(chosenIds: Set<String>): Selector? {
            val statuses = chosenIds
                .mapNotNull { key -> TaskStatus.entries.firstOrNull { it.name == key } }
                .toSet()
            return if (statuses.isEmpty()) null else Selector.Statuses(statuses)
        }
    }

    companion object {
        /**
         * The catalogue the editor offers, fixed types first.
         *
         * Kept in one place so the sheet and any future entry point cannot
         * disagree about what the editor can build.
         */
        val catalogue: List<SelectorTemplate> = listOf(
            Fixed("Active tasks", Selector.Statuses(setOf(TaskStatus.Active))),
            Fixed("Completed tasks", Selector.Statuses(setOf(TaskStatus.Completed))),
            Fixed("Due today", Selector.DateBucket(RelativeBucket.Today)),
            Fixed("Overdue", Selector.DateBucket(RelativeBucket.Overdue)),
            Fixed("No date", Selector.DateBucket(RelativeBucket.NoDate)),
            Fixed("This week", Selector.DateBucket(RelativeBucket.ThisWeek)),
            Fixed("Next week", Selector.DateBucket(RelativeBucket.NextWeek)),
            ByTags(),
            ByProjects,
            ByPriority(),
            ByStatus,
        )
    }
}

/** One selectable value in a template's parameter picker. */
data class SelectorOption(val id: String, val label: String)

/**
 * A [SelectorTemplate] together with the values available for it.
 *
 * The option list is the validator, not just the UI's data: [resolve] drops any
 * chosen id that is not in [options]. Without that, a stale picker — the user
 * had the sheet open when a tag was deleted, say — would build a selector
 * referencing an id that no longer resolves to anything, producing a section
 * that silently matches nothing.
 */
data class ConfigurableSelector(val template: SelectorTemplate, val options: List<SelectorOption>) {
    /** Resolves the template against the chosen values, or `null` if unusable. */
    fun resolve(chosen: Set<String>): Selector? =
        template.resolve(chosen.filterTo(mutableSetOf()) { it in options.mapTo(mutableSetOf()) { o -> o.id } })
}
