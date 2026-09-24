package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.model.Selector.AllOf
import com.singularity.todo.feature.agenda.domain.model.Selector.AnyOf
import com.singularity.todo.feature.agenda.domain.model.Selector.Not

/**
 * Builds a composite [Selector] using a DSL with receivers.
 *
 * Example:
 * ```
 * selector {
 *     allOf(
 *         Selector.tags(ids = setOf(tag1, tag2), matchAll = true),
 *         Selector.dateBucket(RelativeBucket.Overdue),
 *     )
 * }
 * ```
 *
 * For single selectors, use the constructors directly:
 * ```
 * Selector.dateBucket(RelativeBucket.Today)
 * Selector.tags(setOf(tagId))
 * ```
 */
@DslMarker
annotation class SelectorDslMarker

class SelectorScope internal constructor() {
    internal val children: MutableList<Selector> = mutableListOf()

    fun allOf(vararg children: Selector) {
        this.children.add(AllOf(children.toList()))
    }
    fun allOf(children: List<Selector>) {
        this.children.add(AllOf(children))
    }
    fun anyOf(vararg children: Selector) {
        this.children.add(AnyOf(children.toList()))
    }
    fun anyOf(children: List<Selector>) {
        this.children.add(AnyOf(children))
    }
    fun not(child: Selector) {
        this.children.add(Not(child))
    }

    internal fun build(): Selector = when (children.size) {
        0 -> error("Empty selector DSL block — call at least one of allOf/anyOf/not")
        1 -> children.single()
        else -> AllOf(children.toList())
    }
}

fun selector(block: SelectorScope.() -> Unit): Selector = SelectorScope().apply(block).build()
