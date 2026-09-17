@file:Suppress("unused")

package com.singularity.todo.feature.agenda.domain.selector

/**
 * Per-variant functional extensions for [com.singularity.todo.feature.agenda.domain.model.Selector].
 *
 * Each Selector variant has four colocated pure functions:
 * - [matches]       — predicate: does this task match?
 * - [badgeFor]      — badge: what AgendaBadge (if any) applies?
 * - [description]   — UI: human-readable label for the selector type
 * - [toJsonElement] — serialization via the enclosing [SelectorSerializer]
 *
 * ## Rules
 *
 * **R1** (from `orgmode-functional-patterns`): New tree-traversal code must use
 * `traverseDepthFirst` or `cascadeUp` from `core.tree`. Ad-hoc recursive
 * `filter/map` chains are prohibited.
 *
 * **R5** (from `orgmode-functional-patterns`): Every sealed DSL hierarchy
 * must be simultaneously `@Serializable` and a pure predicate. No parallel DTOs.
 * New Selector variants MUST add all four extension functions here before use.
 *
 * **Cohesion**: `when (selector) { is X -> … }` is allowed ONLY as a single
 * dispatch site that calls into these extension functions. The following are
 * the only permitted dispatch sites in this codebase:
 * - [com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator.matches]
 *   (delegates to `selector.matches`)
 * - [com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator.computeBadge]
 *   (delegates to `selector.badgeFor` via SelectorTransformer fold)
 * - [com.singularity.todo.feature.agenda.domain.model.SelectorSerializer.serialize]
 *   (manual JSON building per variant — the sole remaining `when` site)
 * - [com.singularity.todo.feature.agenda.domain.model.SelectorSerializer.deserialize]
 *   (type-discriminator routing — one `when` per branch)
 *
 * All other `when (selector)` blocks must be replaced with dispatch to
 * the appropriate extension function.
 *
 * ## Adding a new Selector variant
 *
 * 1. Add the data class to [com.singularity.todo.feature.agenda.domain.model.Selector].
 * 2. Add the four extension functions in a file under this package
 *    (e.g. `DateBucketExtensions.kt`).
 * 3. Add the JSON serialization branch to [SelectorSerializer.serialize]
 *    and [SelectorSerializer.serializeToElement].
 * 4. Add the deserialization branch to [SelectorSerializer.deserialize].
 * 5. Add `when (selector) is NewVariant -> selector.x(...)` dispatch at EACH
 *    of the four dispatch sites above.
 * 6. Add tests in `commonTest`: round-trip JSON, matches predicate, badge,
 *    description.
 *
 * @see com.singularity.todo.feature.agenda.domain.model.Selector
 * @see com.singularity.todo.feature.agenda.domain.model.SelectorSerializer
 * @see com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
 * @see orgmode-functional-patterns.md
 */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
annotation class SelectorVariant
