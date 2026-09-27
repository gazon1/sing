package com.singularity.todo.core.ui.featureSlot

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Type-safe [combine] for more than five flows.
 *
 * `kotlinx.coroutines` ships typed overloads for two to five flows. Past five, the only
 * option is the `vararg` form, which returns `Flow<Array<Any?>>` and pushes an unchecked
 * cast into every call site. A coordinator merging eight slot states would otherwise need
 * either eight `@Suppress("UNCHECKED_CAST")` blocks or an intermediate data class per
 * grouping level — the nesting that made `TaskDetailViewModel` hard to read.
 *
 * These overloads keep the transform fully typed at the call site and confine the unchecked
 * cast to one private helper.
 *
 * ## The transform is deliberately non-suspending
 *
 * Every overload takes `transform: (…) -> R`, not `suspend (…) -> R`. A non-suspending
 * transform makes it impossible to call a suspending repository write from inside a
 * projection, which is the failure mode the `NoCombineSideEffect` detekt rule reports.
 * Combining inside a transform re-runs it on every upstream emission, so a write there
 * re-fires and leaves stale state behind. Put writes in a `collect { }` block instead.
 *
 * ## Example
 *
 * ```kotlin
 * combineStates(
 *     taskFlow, entity.state, draft.state, children.state,
 *     reminders.state, lifecycle.state, ai.state, backlinks.state,
 * ) { task, entity, draft, children, reminders, lifecycle, ai, backlinks ->
 *     TaskDetailUi(...)
 * }
 * ```
 *
 * Note: this shadows `androidx.lifecycle.compose.combineStates` if a file imports both.
 * ViewModels never import Compose, so the ambiguity does not arise in practice; a
 * Composable that needs both should qualify the call.
 *
 * @see FeatureSlot
 * @see docs/decisions/2026-09-27-feature-slot-pattern.md
 */
fun <S1, S2, R> combineStates(f1: Flow<S1>, f2: Flow<S2>, transform: (S1, S2) -> R): Flow<R> =
    combine(f1, f2) { a, b -> transform(a, b) }

/**
 * Combines three flows. See [combineStates].
 */
fun <S1, S2, S3, R> combineStates(f1: Flow<S1>, f2: Flow<S2>, f3: Flow<S3>, transform: (S1, S2, S3) -> R): Flow<R> =
    combine(f1, f2, f3) { a, b, c -> transform(a, b, c) }

/**
 * Combines four flows. See [combineStates].
 */
fun <S1, S2, S3, S4, R> combineStates(
    f1: Flow<S1>,
    f2: Flow<S2>,
    f3: Flow<S3>,
    f4: Flow<S4>,
    transform: (S1, S2, S3, S4) -> R,
): Flow<R> = combine(f1, f2, f3, f4) { a, b, c, d -> transform(a, b, c, d) }

/**
 * Combines five flows. See [combineStates].
 */
fun <S1, S2, S3, S4, S5, R> combineStates(
    f1: Flow<S1>,
    f2: Flow<S2>,
    f3: Flow<S3>,
    f4: Flow<S4>,
    f5: Flow<S5>,
    transform: (S1, S2, S3, S4, S5) -> R,
): Flow<R> = combine(f1, f2, f3, f4, f5) { a, b, c, d, e -> transform(a, b, c, d, e) }

/**
 * Combines six flows. See [combineStates].
 */
fun <S1, S2, S3, S4, S5, S6, R> combineStates(
    f1: Flow<S1>,
    f2: Flow<S2>,
    f3: Flow<S3>,
    f4: Flow<S4>,
    f5: Flow<S5>,
    f6: Flow<S6>,
    transform: (S1, S2, S3, S4, S5, S6) -> R,
): Flow<R> = combinePacked(arrayOf(f1, f2, f3, f4, f5, f6)) { it.pack(transform) }

/**
 * Combines seven flows. See [combineStates].
 */
fun <S1, S2, S3, S4, S5, S6, S7, R> combineStates(
    f1: Flow<S1>,
    f2: Flow<S2>,
    f3: Flow<S3>,
    f4: Flow<S4>,
    f5: Flow<S5>,
    f6: Flow<S6>,
    f7: Flow<S7>,
    transform: (S1, S2, S3, S4, S5, S6, S7) -> R,
): Flow<R> = combinePacked(arrayOf(f1, f2, f3, f4, f5, f6, f7)) { it.pack(transform) }

/**
 * Combines eight flows. See [combineStates].
 */
fun <S1, S2, S3, S4, S5, S6, S7, S8, R> combineStates(
    f1: Flow<S1>,
    f2: Flow<S2>,
    f3: Flow<S3>,
    f4: Flow<S4>,
    f5: Flow<S5>,
    f6: Flow<S6>,
    f7: Flow<S7>,
    f8: Flow<S8>,
    transform: (S1, S2, S3, S4, S5, S6, S7, S8) -> R,
): Flow<R> = combinePacked(arrayOf(f1, f2, f3, f4, f5, f6, f7, f8)) { it.pack(transform) }

/**
 * The single place in this file that erases element types.
 *
 * `Flow` is covariant, so widening `Flow<S1>` to `Flow<Any?>` needs no cast — the cast is
 * only needed on the way back out, when the packed array is unpacked into the typed
 * transform. Keeping it here means every call site above stays type-safe.
 */
private fun <R> combinePacked(flows: Array<Flow<Any?>>, transform: (Array<Any?>) -> R): Flow<R> =
    combine(flows.toList()) { packed -> transform(packed) }

/**
 * Unpacks the elements of [Flow.combine]'s `Array<Any?>` into the transform's parameters.
 *
 * Each position is known to hold the type the caller declared for that flow, so the cast
 * is an assertion about the array the helper just built, not an unverified guess.
 */
@Suppress("UNCHECKED_CAST")
private inline fun <S1, S2, S3, S4, S5, S6, R> Array<Any?>.pack(transform: (S1, S2, S3, S4, S5, S6) -> R): R =
    transform(
        this[0] as S1,
        this[1] as S2,
        this[2] as S3,
        this[3] as S4,
        this[4] as S5,
        this[5] as S6,
    )

/** Unpacks six elements. See [pack]. */
@Suppress("UNCHECKED_CAST")
private inline fun <S1, S2, S3, S4, S5, S6, S7, R> Array<Any?>.pack(transform: (S1, S2, S3, S4, S5, S6, S7) -> R): R =
    transform(
        this[0] as S1,
        this[1] as S2,
        this[2] as S3,
        this[3] as S4,
        this[4] as S5,
        this[5] as S6,
        this[6] as S7,
    )

/** Unpacks seven elements. See [pack]. */
@Suppress("UNCHECKED_CAST")
private inline fun <S1, S2, S3, S4, S5, S6, S7, S8, R> Array<Any?>.pack(
    transform: (S1, S2, S3, S4, S5, S6, S7, S8) -> R,
): R = transform(
    this[0] as S1,
    this[1] as S2,
    this[2] as S3,
    this[3] as S4,
    this[4] as S5,
    this[5] as S6,
    this[6] as S7,
    this[7] as S8,
)
